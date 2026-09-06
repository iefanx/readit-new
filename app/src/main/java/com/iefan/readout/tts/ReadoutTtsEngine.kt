package com.iefan.readout.tts

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

enum class VoiceStatus {
    DOWNLOADED,
    NETWORK_REQUIRED,
    DOWNLOADABLE
}

data class VoiceInfo(
    val id: String,
    val displayName: String,
    val status: VoiceStatus,
    val locale: Locale
)

class ReadoutTtsEngine(private val context: Context, private val translator: (suspend (String, String) -> String?)? = null) : TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var instance: ReadoutTtsEngine? = null
            private set
    }

    var documentId: Long = -1L
        private set

    var documentTitle: String = ""
        private set

    var totalCharacters: Int = 0
        private set

    val currentCharacterIndex: Int
        get() = if (_isCompleted.value) totalCharacters else (sentences.getOrNull(_currentSentenceIndex.value)?.start ?: 0)

    val sentencesSize: Int
        get() = sentences.size

    private var tts: TextToSpeech? = null

    private var audioFocusRequest: Any? = null
    private var resumeOnFocusGain = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("ReadoutTtsEngine", "Audio focus loss permanent: pausing.")
                resumeOnFocusGain = false
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d("ReadoutTtsEngine", "Audio focus loss transient: pausing.")
                resumeOnFocusGain = _isPlaying.value
                invalidatePlaybackState(stopAudio = true)
                _isPlaying.value = false
                // Do not abandon audio focus so AUDIOFOCUS_GAIN can resume
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("ReadoutTtsEngine", "Audio focus gained.")
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    startPlayback()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = focusRequest
            val result = audioManager.requestAudioFocus(focusRequest)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = audioFocusRequest as? android.media.AudioFocusRequest
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex = _currentSentenceIndex.asStateFlow()

    private val _isCompleted = MutableStateFlow(false)
    val isCompleted = _isCompleted.asStateFlow()
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError = _playbackError.asStateFlow()
    private val _translationErrors = MutableStateFlow<Map<Int, String>>(emptyMap())
    val translationErrors = _translationErrors.asStateFlow()
    private var translationGeneration = 0L
    private var preparationJob: Job? = null
    private var queueJob: Job? = null
    private var offlineOnly = context.getSharedPreferences("readout_prefs", Context.MODE_PRIVATE).getBoolean("offline_only", true)

    fun clearPlaybackError() { _playbackError.value = null }

    fun setOfflineOnly(enabled: Boolean) {
        offlineOnly = enabled
        context.getSharedPreferences("readout_prefs", Context.MODE_PRIVATE).edit().putBoolean("offline_only", enabled).apply()
        if (_isPlaying.value) startPlayback()
        else configureVoiceForTier(_selectedModelTier.value)
    }

    private fun resetTranslations() {
        translationGeneration++
        preparationJob?.cancel()
        translationPrefetchJob?.cancel()
        translationClient.dispatcher.cancelAll()
        spokenSentenceTextMap.clear()
        _translatedSentences.value = emptyMap()
        _translationErrors.value = emptyMap()
    }

    fun retryTranslation(index: Int) {
        _translationErrors.value = _translationErrors.value - index
        preparationJob?.cancel()
        preparationJob = scope.launch { prepareSpokenText(index) }
    }

    private fun failPlayback(message: String) {
        pausePlayback()
        _playbackError.value = message
    }

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private val _selectedModelTier = MutableStateFlow("HIGH_FIDELITY")
    val selectedModelTier = _selectedModelTier.asStateFlow()

    private val _selectedVoiceId = MutableStateFlow("default")
    val selectedVoiceId = _selectedVoiceId.asStateFlow()

    private val _previewingVoiceId = MutableStateFlow<String?>(null)
    val previewingVoiceId = _previewingVoiceId.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val availableVoices = _availableVoices.asStateFlow()

    private val _translationTargetLang = MutableStateFlow("none")
    val translationTargetLang = _translationTargetLang.asStateFlow()

    private val _translatedSentences = MutableStateFlow<Map<Int, String>>(emptyMap())
    val translatedSentences = _translatedSentences.asStateFlow()

    private val spokenSentenceTextMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    private var sentences: List<SpeechSentence> = emptyList()

    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes = _sleepTimerMinutes.asStateFlow()

    private val _sleepTimerRemainingSeconds = MutableStateFlow(0)
    val sleepTimerRemainingSeconds = _sleepTimerRemainingSeconds.asStateFlow()

    private var timerJob: Job? = null
    private var watchdogJob: Job? = null
    private var translationPrefetchJob: Job? = null
    private val enqueueMutex = Mutex()

    @Volatile
    private var highestQueuedIndex = -1

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val playbackTokenCounter = AtomicLong(0L)

    @Volatile
    private var activePlaybackToken: Long = 0L

    private val translationCache = LinkedHashMap<String, String>(256, 0.75f, true)
    private val translationClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        instance = this
        initializeTts()
    }

    fun setSelectedVoiceId(id: String) {
        _selectedVoiceId.value = id
        if (id != "default" && id.isNotEmpty()) {
            downloadVoiceIfNeeded(id)
        }
        val targetLang = _translationTargetLang.value
        if (targetLang.isEmpty() || targetLang == "none") {
            configureVoiceForTier(_selectedModelTier.value)
        } else {
            configureVoiceForLanguage(targetLang)
        }
        if (_isPlaying.value) {
            restartPlaybackFromCurrentSentence()
        }
    }

    fun setTranslationTargetLang(langCode: String) {
        resetTranslations()
        _translationTargetLang.value = langCode
        if (langCode.isNotEmpty() && langCode != "none") {
            downloadLanguagePackIfNeeded(Locale.forLanguageTag(langCode))
        }
        configureVoiceForLanguage(langCode)
        if (_isPlaying.value) {
            restartPlaybackFromCurrentSentence()
        } else {
            // When paused, immediately translate current sentence & prefetch next sentences
            // so KaraokeView updates immediately for the user
            if (langCode.isNotEmpty() && langCode != "none" && sentences.isNotEmpty()) {
                val curIdx = _currentSentenceIndex.value
                preparationJob = scope.launch {
                    prepareSpokenText(curIdx)
                    prefetchTranslationsAhead(curIdx + 1)
                }
            }
        }
    }

    fun isLanguageDownloaded(langCode: String): Boolean {
        val currentTts = tts ?: return false
        if (langCode.isEmpty() || langCode == "none") return true
        val locale = Locale.forLanguageTag(langCode)
        return try {
            val res = currentTts.isLanguageAvailable(locale)
            res >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    fun downloadVoiceIfNeeded(voiceId: String) {
        val currentTts = tts ?: return
        try {
            val voice = currentTts.voices?.firstOrNull { it.name == voiceId }
            if (voice != null && voice.features != null &&
                voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            ) {
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to trigger voice download for $voiceId", e)
        }
    }

    fun downloadLanguagePackIfNeeded(locale: Locale) {
        val currentTts = tts ?: return
        try {
            val res = currentTts.isLanguageAvailable(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA) {
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to trigger language download for $locale", e)
        }
    }

    private var pendingStartPlayback = false

    private fun initializeTts() {
        val pm = context.packageManager
        val isGoogleTtsInstalled = try {
            pm.getPackageInfo("com.google.android.tts", 0) != null
        } catch (_: Exception) {
            false
        }
        tts = if (isGoogleTtsInstalled) {
            TextToSpeech(context, this, "com.google.android.tts")
        } else {
            TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val initialLocale = Locale.getDefault()
            val result = tts?.setLanguage(initialLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                _playbackError.value = "Install a text-to-speech voice for your device language in Android settings."
            } else {
                setupProgressListener()
                _isInitialized.value = true
                Log.d("ReadoutTtsEngine", "TTS initialized successfully.")
                updateAvailableVoicesForLocale(initialLocale)
                configureVoiceForTier(_selectedModelTier.value)
                if (pendingStartPlayback && sentences.isNotEmpty()) {
                    pendingStartPlayback = false
                    startPlayback()
                }
            }
        } else {
            _playbackError.value = "Speech engine unavailable. Install or enable a text-to-speech engine in Android settings."
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { scope.launch {
                if (utteranceId?.startsWith("preview:") == true) {
                    _previewingVoiceId.value = utteranceId.substringAfter("preview:")
                    return@launch
                }
                val state = parseUtteranceId(utteranceId) ?: return@launch
                if (state.token != activePlaybackToken) return@launch
                watchdogJob?.cancel()
                _currentSentenceIndex.value = state.sentenceIndex
                _isPlaying.value = true
                enqueueSentencesUpTo(state.sentenceIndex + 2, state.token)
                prefetchTranslationsAhead(state.sentenceIndex + 3)
            } }

            override fun onDone(utteranceId: String?) { scope.launch {
                if (utteranceId?.startsWith("preview:") == true) {
                    _previewingVoiceId.value = null
                    return@launch
                }
                val state = parseUtteranceId(utteranceId) ?: return@launch
                if (state.token != activePlaybackToken || !state.lastChunk) return@launch
                if (state.sentenceIndex >= sentences.lastIndex) {
                    _isCompleted.value = true
                    _isPlaying.value = false
                    stopPlaybackService()
                    abandonAudioFocus()
                } else {
                    watchdogJob?.cancel()
                    watchdogJob = scope.launch {
                        delay(15000)
                        if (state.token == activePlaybackToken && _isPlaying.value && _currentSentenceIndex.value <= state.sentenceIndex) {
                            failPlayback("Speech stopped responding. Tap play to retry this sentence.")
                        }
                    }
                }
            } }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { scope.launch {
                if (utteranceId?.startsWith("preview:") == true) {
                    _previewingVoiceId.value = null
                    _playbackError.value = "Voice preview failed. Check the installed voice or connection."
                    return@launch
                }
                val state = parseUtteranceId(utteranceId) ?: return@launch
                if (state.token == activePlaybackToken) {
                    failPlayback("Speech failed. Tap play to retry, or choose another installed voice.")
                }
            } }
        })
    }

    fun loadDocument(documentId: Long, sentencesList: List<SpeechSentence>, title: String, startSentenceIndex: Int = 0) {
        stop()
        resetTranslations()
        _isCompleted.value = false
        this.documentId = documentId
        documentTitle = title
        sentences = sentencesList
        spokenSentenceTextMap.clear()
        _translatedSentences.value = emptyMap()
        totalCharacters = sentencesList.lastOrNull()?.end ?: 0
        _currentSentenceIndex.value = startSentenceIndex.coerceIn(0, maxOf(0, sentences.lastIndex))
        stop()
    }

    fun getSentences(): List<SpeechSentence> = sentences

    fun startPlayback() {
        _playbackError.value = null
        if (_isCompleted.value) { _currentSentenceIndex.value = 0; _isCompleted.value = false }
        if (!_isInitialized.value) {
            pendingStartPlayback = true
            return
        }
        if (sentences.isEmpty()) return
        pendingStartPlayback = false
        val targetLang = _translationTargetLang.value
        if (targetLang.isNotEmpty() && targetLang != "none") {
            configureVoiceForLanguage(targetLang)
        } else {
            configureVoiceForTier(_selectedModelTier.value)
        }
        if (_playbackError.value == null) restartPlaybackFromCurrentSentence()
    }

    fun pausePlayback() {
        resumeOnFocusGain = false
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = false
        abandonAudioFocus()
    }

    fun stop() {
        resumeOnFocusGain = false
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = false
        stopPlaybackService()
        abandonAudioFocus()
    }

    fun setSpeed(speed: Float) {
        val bounded = if (speed.isFinite()) speed.coerceIn(0.5f, 4.5f) else 1f
        _playbackSpeed.value = bounded
        tts?.setSpeechRate(bounded)
        if (_isPlaying.value) {
            restartPlaybackFromCurrentSentence()
        }
    }

    fun setModelTier(tier: String) {
        _selectedModelTier.value = tier
        configureVoiceForTier(tier)
        if (_isPlaying.value) {
            restartPlaybackFromCurrentSentence()
        }
    }

    fun seekToSentence(index: Int) {
        if (sentences.isEmpty()) return
        _isCompleted.value = false
        val targetIdx = index.coerceIn(0, sentences.lastIndex)
        val wasPlaying = _isPlaying.value
        invalidatePlaybackState(stopAudio = wasPlaying)
        _currentSentenceIndex.value = targetIdx
        if (wasPlaying) {
            restartPlaybackFromCurrentSentence()
        } else {
            val targetLang = _translationTargetLang.value
            if (targetLang.isNotEmpty() && targetLang != "none") {
                preparationJob = scope.launch {
                    prepareSpokenText(targetIdx)
                    prefetchTranslations(targetIdx + 1, count = 8)
                }
            }
        }
    }

    fun seekToCharacter(charIndex: Int) {
        if (sentences.isEmpty()) return
        val targetIdx = sentences.indexOfFirst { charIndex in it.start..it.end }
        if (targetIdx != -1) {
            seekToSentence(targetIdx)
        } else {
            val closest = sentences.minByOrNull { kotlin.math.abs(it.start - charIndex) }
            if (closest != null) {
                seekToSentence(closest.index)
            }
        }
    }

    fun skipForward15s() {
        val target = findSentenceIndexByWordDelta(_currentSentenceIndex.value, targetWordDelta = 38)
        seekToSentence(target)
    }

    fun skipBackward15s() {
        val target = findSentenceIndexByWordDelta(_currentSentenceIndex.value, targetWordDelta = -38)
        seekToSentence(target)
    }

    private suspend fun translateText(text: String, targetLang: String, sourceDocumentId: Long): String? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""
        if (targetLang.isEmpty() || targetLang == "none") return@withContext null

        val cacheKey = "$sourceDocumentId|$targetLang|$trimmed"
        synchronized(translationCache) {
            translationCache[cacheKey]?.let { return@withContext it }
        }

        if (!isNetworkAvailable()) {
            Log.w("ReadoutTtsEngine", "No internet for translation")
            return@withContext null
        }

        // Tier 1: Google Translate Mobile Web (Unblocked by bot detection, resilient against 429)
        try {
            val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://translate.google.com/m?sl=auto&tl=$targetLang&q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .build()

            translationClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string()
                    if (!html.isNullOrBlank()) {
                        val doc = Jsoup.parse(html)
                        val resultContainer = doc.selectFirst("div.result-container")
                        val translated = resultContainer?.text()?.trim()
                        if (!translated.isNullOrBlank()) {
                            synchronized(translationCache) {
                                translationCache[cacheKey] = translated
                                while (translationCache.size > 300) {
                                    val oldestKey = translationCache.entries.iterator().next().key
                                    translationCache.remove(oldestKey)
                                }
                            }
                            return@withContext translated
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ReadoutTtsEngine", "Primary mobile web translation failed, trying API fallback", e)
        }

        // Tier 2: Google Translate (dict-chrome-ex client with form POST)
        try {
            val url = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&tl=$targetLang&dt=t"
            val formBody = FormBody.Builder()
                .add("q", trimmed)
                .build()
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            translationClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank() && body.startsWith("[")) {
                        val jsonArray = JSONArray(body)
                        val firstArray = jsonArray.optJSONArray(0)
                        if (firstArray != null) {
                            val result = StringBuilder()
                            for (i in 0 until firstArray.length()) {
                                val partArray = firstArray.optJSONArray(i)
                                val translatedPart = partArray?.optString(0)
                                if (!translatedPart.isNullOrEmpty()) {
                                    result.append(translatedPart)
                                }
                            }
                            val translated = result.toString().trim()
                            if (translated.isNotEmpty()) {
                                synchronized(translationCache) {
                                    translationCache[cacheKey] = translated
                                    while (translationCache.size > 300) {
                                        val oldestKey = translationCache.entries.iterator().next().key
                                        translationCache.remove(oldestKey)
                                    }
                                }
                                return@withContext translated
                            }
                        }
                    }
                } else {
                    Log.w("ReadoutTtsEngine", "Primary translation HTTP error ${response.code}, falling back to MyMemory")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ReadoutTtsEngine", "Primary translation failed, falling back to secondary", e)
        }

        // Tier 2: MyMemory API Fallback
        try {
            val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val fallbackUrl = "https://api.mymemory.translated.net/get?q=$encodedQuery&langpair=autodetect|$targetLang"
            val fallbackRequest = Request.Builder()
                .url(fallbackUrl)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            translationClient.newCall(fallbackRequest).execute().use { fbResponse ->
                if (fbResponse.isSuccessful) {
                    val fbBody = fbResponse.body?.string()
                    if (!fbBody.isNullOrBlank() && fbBody.startsWith("{")) {
                        val json = JSONObject(fbBody)
                        val status = json.optInt("responseStatus", json.optString("responseStatus").toIntOrNull() ?: 0)
                        if (status == 200) {
                            val responseData = json.optJSONObject("responseData")
                            val transText = responseData?.optString("translatedText")
                            if (!transText.isNullOrBlank() &&
                                !transText.contains("MYMEMORY WARNING", ignoreCase = true) &&
                                !transText.contains("PLEASE SELECT", ignoreCase = true) &&
                                !transText.contains("QUERY LENGTH", ignoreCase = true)) {
                                val cleanTrans = Parser.unescapeEntities(transText, false).trim()
                                if (cleanTrans.isNotEmpty()) {
                                    synchronized(translationCache) {
                                        translationCache[cacheKey] = cleanTrans
                                        while (translationCache.size > 300) {
                                            val oldestKey = translationCache.entries.iterator().next().key
                                            translationCache.remove(oldestKey)
                                        }
                                    }
                                    return@withContext cleanTrans
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Secondary translation fallback also failed for language $targetLang", e)
        }

        null
    }

    private fun configureVoiceForLanguage(langCode: String) {
        val currentTts = tts ?: return
        if (langCode.isEmpty() || langCode == "none") {
            configureVoiceForTier(_selectedModelTier.value, preferredLocale = Locale.getDefault())
            return
        }

        val targetLocale = Locale.forLanguageTag(langCode)
        currentTts.setLanguage(targetLocale)
        updateAvailableVoicesForLocale(targetLocale)

        val available = try {
            currentTts.voices
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to retrieve voices for language $langCode", e)
            return
        }
        if (available.isNullOrEmpty()) return

        val localeVoices = available.filter { isLanguageMatch(it.locale, targetLocale) && eligibleVoice(it) }
        if (localeVoices.isEmpty()) {
            failPlayback("No installed voice for $langCode. Install a voice or allow online voices in Settings.")
            return
        }

        val hasInternet = isNetworkAvailable()
        val selectedVoice = localeVoices.maxByOrNull { scoreVoice(it, targetLocale, hasInternet) }

        if (selectedVoice != null) {
            currentTts.setLanguage(selectedVoice.locale)
            currentTts.voice = selectedVoice
            Log.d("ReadoutTtsEngine", "configureVoiceForLanguage($langCode): set voice=${selectedVoice.name} (score=${scoreVoice(selectedVoice, targetLocale, hasInternet)})")
        }
    }

    private fun startPlaybackAtSentence(index: Int) {
        if (!_isInitialized.value || sentences.isEmpty() || index !in sentences.indices) return
        if (!requestAudioFocus()) {
            failPlayback("Another app is using audio. Try again when it finishes.")
            return
        }
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = true
        _currentSentenceIndex.value = index

        val token = activePlaybackToken
        applyCurrentVoiceAndRate()
        startPlaybackService()

        enqueueSentencesUpTo(index + 2, token)
        prefetchTranslationsAhead(index + 3)
    }

    private fun applyCurrentVoiceAndRate() {
        val currentTts = tts ?: return
        try {
            currentTts.setSpeechRate(_playbackSpeed.value)
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to set speech rate", e)
        }
    }

    private fun enqueueSentencesUpTo(targetMaxIndex: Int, playbackToken: Long) {
        if (playbackToken != activePlaybackToken || !_isPlaying.value) return
        // One producer owns the queue. onStart extends its target without cancelling it.
        requestedQueueEnd = maxOf(requestedQueueEnd, targetMaxIndex.coerceAtMost(sentences.lastIndex))
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            while (playbackToken == activePlaybackToken && _isPlaying.value) {
                val index = if (highestQueuedIndex < 0) _currentSentenceIndex.value else highestQueuedIndex + 1
                if (index > requestedQueueEnd) break
                val spoken = prepareSpokenText(index)
                if (playbackToken != activePlaybackToken || !_isPlaying.value) return@launch
                if (spoken == null) {
                    failPlayback("Translation unavailable. Retry or turn translation off to read the original.")
                    return@launch
                }
                val target = _translationTargetLang.value
                if (target != "none" && target.isNotEmpty()) configureVoiceForLanguage(target)
                val chunks = SpeechChunks.split(spoken, TextToSpeech.getMaxSpeechInputLength() - 1)
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    val first = highestQueuedIndex < 0 && chunkIndex == 0
                    val id = "$playbackToken:$index:${chunkIndex == chunks.lastIndex}"
                    val result = tts?.speak(chunk, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                    if (result != TextToSpeech.SUCCESS) {
                        failPlayback("The speech engine rejected this text. Choose another voice and retry.")
                        return@launch
                    }
                }
                highestQueuedIndex = index
                if (index == _currentSentenceIndex.value) {
                    watchdogJob?.cancel()
                    watchdogJob = scope.launch {
                        delay(15000)
                        if (playbackToken == activePlaybackToken && _isPlaying.value && !ttsIsSpeaking()) {
                            failPlayback("Speech did not start. Check your voice settings and retry.")
                        }
                    }
                }
            }
        }
    }

    private var requestedQueueEnd = -1
    private fun ttsIsSpeaking() = tts?.isSpeaking == true

    private suspend fun prepareSpokenText(index: Int): String? {
        val generation = translationGeneration
        val sourceId = documentId
        val target = _translationTargetLang.value
        val key = "$sourceId|$index|$target"
        if (target in listOf("", "none")) spokenSentenceTextMap[key]?.let { return it }
        else _translatedSentences.value[index]?.let { return SpokenTextNormalizer.normalizeForSpeech(it, target) }
        val sentence = sentences.getOrNull(index) ?: return null
        val translated = if (target.isNotEmpty() && target != "none") {
            val parts = mutableListOf<String>()
            for (part in SpeechChunks.splitUtf8(sentence.text, 480)) {
                val result = if (translator != null) translator.invoke(part, target) else translateText(part, target, sourceId)
                currentCoroutineContext().ensureActive()
                if (generation != translationGeneration || sourceId != documentId) return null
                if (result.isNullOrBlank()) {
                    _translationErrors.value = _translationErrors.value + (index to "Translation unavailable. Tap to retry.")
                    return null
                }
                parts.add(result)
            }
            parts.joinToString(" ")
        } else null
        currentCoroutineContext().ensureActive()
        if (generation != translationGeneration || sourceId != documentId) return null
        if (translated != null) {
            _translatedSentences.value = (_translatedSentences.value + (index to translated)).filterKeys { it in (index - 40)..(index + 80) }
            _translationErrors.value = _translationErrors.value - index
        }
        val language = if (translated != null) target else (tts?.voice?.locale?.language ?: Locale.getDefault().language)
        val spoken = SpokenTextNormalizer.normalizeForSpeech(translated ?: sentence.text, language)
        if (spokenSentenceTextMap.size >= 128) spokenSentenceTextMap.clear()
        spokenSentenceTextMap[key] = spoken
        return spoken
    }

    fun prefetchTranslations(fromIndex: Int, count: Int = 10) {
        if (_translationTargetLang.value in listOf("", "none")) return
        translationPrefetchJob?.cancel()
        translationPrefetchJob = scope.launch {
            for (index in fromIndex.coerceAtLeast(0) until minOf(fromIndex + count, sentences.size)) {
                if (prepareSpokenText(index) == null) break
                delay(100)
            }
        }
    }

    private fun prefetchTranslationsAhead(fromIndex: Int) = prefetchTranslations(fromIndex, 4)

    private fun startPlaybackService() {
        val intent = Intent(context, com.iefan.readout.service.PlaybackService::class.java).apply {
            action = com.iefan.readout.service.PlaybackService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopPlaybackService() {
        context.stopService(Intent(context, com.iefan.readout.service.PlaybackService::class.java))
    }

    fun startSleepTimer(minutes: Int) {
        val boundedMinutes = minutes.coerceIn(0, 1440)
        _sleepTimerMinutes.value = boundedMinutes
        _sleepTimerRemainingSeconds.value = boundedMinutes * 60
        timerJob?.cancel()

        if (minutes == 0) {
            _sleepTimerRemainingSeconds.value = 0
            return
        }

        timerJob = scope.launch {
            while (_sleepTimerRemainingSeconds.value > 0) {
                delay(1000)
                _sleepTimerRemainingSeconds.value -= 1
            }
            pausePlayback()
            _sleepTimerMinutes.value = 0
        }
    }

    private fun configureVoiceForTier(tier: String, preferredLocale: Locale? = null) {
        val currentTts = tts ?: return
        val resolvedLocale = preferredLocale ?: currentTts.voice?.locale ?: Locale.US
        currentTts.setLanguage(resolvedLocale)
        updateAvailableVoicesForLocale(resolvedLocale)
        val available = try {
            currentTts.voices
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to retrieve voices", e)
            null
        }

        val targetVoiceId = _selectedVoiceId.value
        if (available.isNullOrEmpty()) return

        val currentLocale = preferredLocale ?: currentTts.voice?.locale ?: Locale.US
        val localeVoices = available.filter { isLanguageMatch(it.locale, currentLocale) && eligibleVoice(it) }
        if (localeVoices.isEmpty()) {
            failPlayback("No installed voice is available for this language. Install one in Android speech settings.")
            return
        }

        var selectedVoice = localeVoices.firstOrNull { it.name == targetVoiceId }
        if (selectedVoice != null) {
            val isNotInstalled = selectedVoice.features != null &&
                selectedVoice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            val requiresNetwork = selectedVoice.isNetworkConnectionRequired
            if (isNotInstalled || (requiresNetwork && !isNetworkAvailable())) {
                selectedVoice = null
            }
        }

        if (selectedVoice == null) {
            val hasInternet = isNetworkAvailable()
            selectedVoice = localeVoices.maxByOrNull { scoreVoice(it, currentLocale, hasInternet) }
        }

        if (selectedVoice != null) {
            currentTts.setLanguage(selectedVoice.locale)
            currentTts.setVoice(selectedVoice)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            currentTts.setAudioAttributes(audioAttributes)
        }
        currentTts.setPitch(1.0f)
    }

    private fun restartPlaybackFromCurrentSentence() {
        val index = _currentSentenceIndex.value
        if (!_isInitialized.value || sentences.isEmpty() || index !in sentences.indices) return
        startPlaybackAtSentence(index)
    }

    private fun invalidatePlaybackState(stopAudio: Boolean) {
        activePlaybackToken = playbackTokenCounter.incrementAndGet()
        queueJob?.cancel()
        queueJob = null
        requestedQueueEnd = -1
        watchdogJob?.cancel()
        watchdogJob = null
        highestQueuedIndex = -1
        if (stopAudio) {
            tts?.stop()
        }
    }

    private fun findSentenceIndexByWordDelta(startIndex: Int, targetWordDelta: Int): Int {
        if (sentences.isEmpty()) return 0
        if (targetWordDelta == 0) return startIndex.coerceIn(0, sentences.lastIndex)

        val direction = if (targetWordDelta > 0) 1 else -1
        val targetWords = kotlin.math.abs(targetWordDelta)
        var wordsTraversed = 0
        var index = startIndex.coerceIn(0, sentences.lastIndex)

        while (true) {
            val nextIndex = (index + direction).coerceIn(0, sentences.lastIndex)
            if (nextIndex == index) return index
            index = nextIndex
            wordsTraversed += sentences[index].words.size.coerceAtLeast(1)
            if (wordsTraversed >= targetWords) return index
        }
    }

    private fun buildUtteranceId(token: Long, sentenceIndex: Int): String = "$token:$sentenceIndex"

    private fun parseUtteranceId(utteranceId: String?): PlaybackState? {
        val raw = utteranceId ?: return null
        val parts = raw.split(':')
        if (parts.size < 2) return null
        val token = parts[0].toLongOrNull() ?: return null
        val sentenceIndex = parts[1].toIntOrNull() ?: return null
        return PlaybackState(token, sentenceIndex, parts.getOrNull(2) != "false")
    }

    private fun eligibleVoice(voice: Voice): Boolean =
        voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true &&
        (!voice.isNetworkConnectionRequired || (!offlineOnly && isNetworkAvailable()))

    private fun scoreVoice(voice: Voice, targetLocale: Locale, hasInternet: Boolean): Int {
        val isNotInstalled = voice.features != null &&
            voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        if (isNotInstalled) return -10000

        val requiresNetwork = voice.isNetworkConnectionRequired
        if (requiresNetwork && !hasInternet) return -10000

        var score = voice.quality

        val nameLower = voice.name.lowercase(Locale.ROOT)

        // Prioritize Google neural / wavenet voices
        if (hasInternet && requiresNetwork) {
            score += 500
        }
        if (nameLower.contains("network")) score += 300
        if (nameLower.contains("neural") || nameLower.contains("wavenet")) score += 400

        // User preferred voice: Ava (en-us-x-iol-network) is prioritized as the supreme default
        if (nameLower.contains("iol-network") || nameLower == "en-us-x-iol-network") {
            score += 1500
        } else if (nameLower.contains("-sfg-") || nameLower.contains("-iom-") ||
            nameLower.contains("-iob-") || nameLower.contains("-tpf-") || nameLower.contains("-tpd-")) {
            score += 200
        }

        // Offline highQuality flag and local fallback for Ava
        if (!requiresNetwork) {
            if (nameLower.contains("iol-local") || nameLower == "en-us-x-iol-local") {
                score += 800
            }
            if (voice.features != null && voice.features.contains("highQuality")) {
                score += 250
            }
            if (nameLower.contains("local")) {
                score += 100
            }
        }

        // Regional match preference (e.g. en_US matching en_US)
        if (isCountryMatch(voice.locale, targetLocale)) {
            score += 150
        }

        // Penalty for very low latency (often degraded audio quality)
        if (voice.latency == Voice.LATENCY_VERY_LOW || voice.latency == Voice.LATENCY_LOW) {
            score -= 50
        }

        return score
    }

    private fun updateAvailableVoicesForLocale(locale: Locale) {
        val currentTts = tts ?: return
        val available = try {
            currentTts.voices
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to retrieve voices", e)
            null
        }

        if (available.isNullOrEmpty()) {
            _availableVoices.value = emptyList()
            return
        }

        val hasInternet = isNetworkAvailable()
        _availableVoices.value = available
            .filter { isLanguageMatch(it.locale, locale) }
            .sortedByDescending { scoreVoice(it, locale, hasInternet) }
            .map { voice ->
                val status = when {
                    voice.isNetworkConnectionRequired -> VoiceStatus.NETWORK_REQUIRED
                    voice.features != null && voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) -> VoiceStatus.DOWNLOADABLE
                    else -> VoiceStatus.DOWNLOADED
                }
                VoiceInfo(
                    id = voice.name,
                    displayName = getFriendlyVoiceName(voice.name),
                    status = status,
                    locale = voice.locale
                )
            }
            .distinctBy { it.id }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        @Suppress("DEPRECATION")
        val networkInfo = connectivityManager.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return networkInfo.isConnected
    }

    private fun isLanguageMatch(loc1: Locale, loc2: Locale): Boolean {
        val lang1 = loc1.language.lowercase()
        val lang2 = loc2.language.lowercase()
        if (lang1 == lang2) return true
        return try {
            val iso1 = loc1.isO3Language.lowercase()
            val iso2 = loc2.isO3Language.lowercase()
            iso1.isNotEmpty() && iso1 == iso2
        } catch (_: Exception) {
            false
        }
    }

    private fun isCountryMatch(loc1: Locale, loc2: Locale): Boolean {
        val country1 = loc1.country.lowercase()
        val country2 = loc2.country.lowercase()
        if (country1.isNotEmpty() && country1 == country2) return true
        return try {
            val iso1 = loc1.isO3Country.lowercase()
            val iso2 = loc2.isO3Country.lowercase()
            iso1.isNotEmpty() && iso1 == iso2
        } catch (_: Exception) {
            false
        }
    }

    private fun getFriendlyVoiceName(voiceName: String): String {
        val parts = voiceName.split("-x-")
        if (parts.size <= 1) return voiceName

        val persona = parts[1].substringBefore("-").lowercase()
        return when (persona) {
            "sfg" -> "Serena · Dynamic Narrator"
            "iol", "lol" -> "Ava · Warm & Conversational"
            "iom", "lom" -> "James · Deep & Resonant"
            "tpf" -> "Oliver · Expressive Narrator"
            "tpd" -> "Lucas · Balanced Narrator"
            "tpc" -> "Grace · Soft Narrator"
            "iog" -> "Sophia · Clear Narrator"
            "iob" -> "Ethan · Direct Narrator"
            "msm" -> "Benjamin · Smooth Narrator"
            "rjs" -> "Arthur · British Storyteller"
            "gba" -> "Emma · British Narrator"
            "gbb" -> "George · British Narrator"
            "cfl" -> "Aarav · Indian English"
            "hie" -> "Kavya · Natural Hindi"
            "hid" -> "Rohan · Deep Hindi"
            "hia" -> "Ananya · Clear Hindi"
            "hic" -> "Kabir · Warm Hindi"
            else -> "${persona.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} · Narrator"
        }
    }

    fun previewVoice(voiceId: String, displayName: String = "", locale: Locale = Locale.US) {
        val currentTts = tts ?: return

        // If audio is actively playing a document, pause playback
        if (_isPlaying.value) {
            pausePlayback()
        } else {
            invalidatePlaybackState(stopAudio = true)
        }

        val available = try {
            currentTts.voices
        } catch (_: Exception) {
            null
        } ?: emptySet()

        val targetVoice = if (voiceId != "default" && voiceId.isNotEmpty()) {
            available.firstOrNull { it.name == voiceId }
        } else {
            available.filter { isLanguageMatch(it.locale, locale) }
                .maxByOrNull { scoreVoice(it, locale, isNetworkAvailable()) }
        }

        val effectiveLocale = targetVoice?.locale ?: locale
        if (targetVoice != null) {
            currentTts.setLanguage(targetVoice.locale)
            currentTts.voice = targetVoice
        } else {
            currentTts.setLanguage(effectiveLocale)
        }

        val personaName = getPersonaNameForAudition(displayName, targetVoice?.name ?: voiceId)
        val sampleText = getAuditionGreeting(personaName, effectiveLocale)

        _previewingVoiceId.value = voiceId
        val utteranceId = "preview:$voiceId"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        currentTts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun getPersonaNameForAudition(displayName: String, voiceName: String): String {
        val firstToken = displayName.split("·", "-", " ").firstOrNull()?.trim()
        if (!firstToken.isNullOrBlank() && firstToken.length > 1 &&
            !firstToken.equals("system", ignoreCase = true) &&
            !firstToken.equals("default", ignoreCase = true) &&
            !firstToken.equals("recommended", ignoreCase = true)
        ) {
            return firstToken
        }
        val parts = voiceName.split("-x-")
        if (parts.size > 1) {
            val persona = parts[1].substringBefore("-").lowercase()
            return when (persona) {
                "sfg" -> "Serena"
                "iol", "lol" -> "Ava"
                "iom", "lom" -> "James"
                "tpf" -> "Oliver"
                "tpd" -> "Lucas"
                "tpc" -> "Grace"
                "iog" -> "Sophia"
                "iob" -> "Ethan"
                "msm" -> "Benjamin"
                "rjs" -> "Arthur"
                "gba" -> "Emma"
                "gbb" -> "George"
                "cfl" -> "Aarav"
                "hie" -> "Kavya"
                "hid" -> "Rohan"
                "hia" -> "Ananya"
                "hic" -> "Kabir"
                else -> persona.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        }
        return "Ava"
    }

    private fun getAuditionGreeting(personaName: String, locale: Locale): String {
        return when (locale.language.lowercase()) {
            "es" -> "Hola, mi nombre es $personaName. Soy tu narrador de lectura."
            "fr" -> "Bonjour, je m'appelle $personaName. Je suis votre narrateur de lecture."
            "de" -> "Hallo, mein Name ist $personaName. Ich bin dein Vorleser."
            "hi" -> "नमस्ते, मेरा नाम $personaName है। मैं आपका वाचक हूँ।"
            "it" -> "Ciao, mi chiamo $personaName. Sono il tuo narratore."
            "pt" -> "Olá, meu nome é $personaName. Sou seu narrador de leitura."
            "ru" -> "Здравствуйте, меня зовут $personaName. Я ваш чтец."
            "ja" -> "こんにちは、$personaName です。あなたの朗読ナレーターです。"
            "ko" -> "안녕하세요, $personaName 입니다. 책 읽어주는 내레이터입니다."
            "zh" -> "你好，我是 $personaName，你的朗读播音员。"
            else -> "Hi, my name is $personaName. I am your reading narrator."
        }
    }

    fun shutdown() {
        timerJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        tts?.shutdown()
        tts = null
        if (instance == this) {
            instance = null
        }
    }

    private data class PlaybackState(
        val token: Long,
        val sentenceIndex: Int,
        val lastChunk: Boolean = true
    )
}
