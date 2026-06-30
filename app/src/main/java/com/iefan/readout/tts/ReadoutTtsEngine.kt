package com.iefan.readout.tts

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

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

class ReadoutTtsEngine(private val context: Context) : TextToSpeech.OnInitListener {

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
        get() = _currentWordRange.value?.first ?: (sentences.getOrNull(_currentSentenceIndex.value)?.start ?: 0)

    val sentencesSize: Int
        get() = sentences.size

    private var tts: TextToSpeech? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized = _isInitialized.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex = _currentSentenceIndex.asStateFlow()

    private val _currentWordRange = MutableStateFlow<Pair<Int, Int>?>(null)
    val currentWordRange = _currentWordRange.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private val _selectedModelTier = MutableStateFlow("HIGH_FIDELITY")
    val selectedModelTier = _selectedModelTier.asStateFlow()

    private val _selectedVoiceId = MutableStateFlow("default")
    val selectedVoiceId = _selectedVoiceId.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val availableVoices = _availableVoices.asStateFlow()

    private val _translationTargetLang = MutableStateFlow("none")
    val translationTargetLang = _translationTargetLang.asStateFlow()

    private var sentences: List<SpeechSentence> = emptyList()

    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes = _sleepTimerMinutes.asStateFlow()

    private val _sleepTimerRemainingSeconds = MutableStateFlow(0)
    val sleepTimerRemainingSeconds = _sleepTimerRemainingSeconds.asStateFlow()

    private var timerJob: Job? = null
    private var wordHighlightJob: Job? = null
    private var currentSpeakJob: Job? = null
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
        _translationTargetLang.value = langCode
        configureVoiceForLanguage(langCode)
        if (_isPlaying.value) {
            restartPlaybackFromCurrentSentence()
        }
    }

    private fun initializeTts() {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("ReadoutTtsEngine", "English language is not supported or missing data.")
            } else {
                setupProgressListener()
                _isInitialized.value = true
                Log.d("ReadoutTtsEngine", "TTS initialized successfully.")
                updateAvailableVoicesForLocale(Locale.US)
                configureVoiceForTier(_selectedModelTier.value)
            }
        } else {
            Log.e("ReadoutTtsEngine", "TTS initialization failed.")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                wordHighlightJob?.cancel()
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return
                _currentSentenceIndex.value = playbackState.sentenceIndex
                _currentWordRange.value = null
                _isPlaying.value = true
            }

            override fun onDone(utteranceId: String?) {
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return

                scope.launch {
                    val nextIndex = playbackState.sentenceIndex + 1
                    if (nextIndex < sentences.size) {
                        speakSentence(nextIndex, playbackState.token)
                    } else {
                        _isPlaying.value = false
                        _currentWordRange.value = null
                        stopPlaybackService()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e("ReadoutTtsEngine", "TTS error on sentence $utteranceId")
                _isPlaying.value = false
                _currentWordRange.value = null
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return

                val sentence = sentences.getOrNull(playbackState.sentenceIndex) ?: return
                wordHighlightJob?.cancel()
                wordHighlightJob = scope.launch {
                    val baseLatencyMs = 280L
                    val speed = _playbackSpeed.value.coerceAtLeast(0.5f)
                    val adjustedDelay = (baseLatencyMs / speed).toLong().coerceIn(60L, 600L)
                    delay(adjustedDelay)
                    if (playbackState.token != activePlaybackToken) return@launch
                    val absStart = sentence.start + start
                    val absEnd = sentence.start + end
                    _currentWordRange.value = Pair(absStart, absEnd)
                }
            }
        })
    }

    fun loadDocument(documentId: Long, sentencesList: List<SpeechSentence>, title: String, startSentenceIndex: Int = 0) {
        this.documentId = documentId
        documentTitle = title
        sentences = sentencesList
        totalCharacters = sentencesList.lastOrNull()?.end ?: 0
        _currentSentenceIndex.value = startSentenceIndex.coerceIn(0, maxOf(0, sentences.lastIndex))
        _currentWordRange.value = null
        stop()
    }

    fun getSentences(): List<SpeechSentence> = sentences

    fun startPlayback() {
        if (!_isInitialized.value || sentences.isEmpty()) return
        restartPlaybackFromCurrentSentence()
    }

    fun pausePlayback() {
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = false
        _currentWordRange.value = null
    }

    fun stop() {
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = false
        _currentWordRange.value = null
        stopPlaybackService()
    }

    fun setSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.5f, 4.5f)
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
        val targetIdx = index.coerceIn(0, sentences.lastIndex)
        val wasPlaying = _isPlaying.value
        invalidatePlaybackState(stopAudio = wasPlaying)
        _currentSentenceIndex.value = targetIdx
        _currentWordRange.value = null
        if (wasPlaying) {
            restartPlaybackFromCurrentSentence()
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

    private suspend fun translateText(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            Log.w("ReadoutTtsEngine", "No internet for translation, returning original text")
            return@withContext text
        }

        val cacheKey = "$documentId|$targetLang|$text"
        synchronized(translationCache) {
            translationCache[cacheKey]?.let { return@withContext it }
        }

        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=${java.net.URLEncoder.encode(text, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            translationClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ReadoutTtsEngine", "Translation HTTP error: ${response.code}")
                    return@withContext text
                }
                val body = response.body?.string() ?: return@withContext text
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

                    val translated = result.toString().ifBlank { text }
                    synchronized(translationCache) {
                        translationCache[cacheKey] = translated
                        while (translationCache.size > 250) {
                            val oldestKey = translationCache.entries.iterator().next().key
                            translationCache.remove(oldestKey)
                        }
                    }
                    return@withContext translated
                }
                text
            }
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Translation failed for language $targetLang", e)
            text
        }
    }

    private fun configureVoiceForLanguage(langCode: String) {
        val currentTts = tts ?: return
        if (langCode.isEmpty() || langCode == "none") {
            configureVoiceForTier(_selectedModelTier.value, preferredLocale = Locale.US)
            return
        }

        val targetLocale = Locale(langCode)
        currentTts.setLanguage(targetLocale)
        updateAvailableVoicesForLocale(targetLocale)

        val available = try {
            currentTts.voices
        } catch (e: Exception) {
            Log.e("ReadoutTtsEngine", "Failed to retrieve voices for language $langCode", e)
            return
        }
        if (available.isNullOrEmpty()) return

        val localeVoices = available.filter { isLanguageMatch(it.locale, targetLocale) }
        if (localeVoices.isEmpty()) {
            Log.w("ReadoutTtsEngine", "No voices found for language $langCode, falling back to setLanguage only")
            return
        }

        val hasInternet = isNetworkAvailable()
        var selectedVoice: Voice? = null
        if (hasInternet) {
            selectedVoice = localeVoices
                .filter { it.isNetworkConnectionRequired }
                .maxByOrNull { it.quality }
        }

        if (selectedVoice == null) {
            selectedVoice = localeVoices
                .filter {
                    !it.isNetworkConnectionRequired &&
                        (it.features == null || !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                }
                .maxByOrNull { it.quality }
        }

        if (selectedVoice == null) {
            selectedVoice = localeVoices.maxByOrNull { it.quality }
        }

        if (selectedVoice != null) {
            currentTts.voice = selectedVoice
            Log.d("ReadoutTtsEngine", "configureVoiceForLanguage($langCode): set voice=${selectedVoice.name}")
        }
    }

    private fun speakSentence(index: Int, playbackToken: Long) {
        val sentence = sentences.getOrNull(index) ?: return
        currentSpeakJob?.cancel()
        currentSpeakJob = scope.launch {
            val targetLang = _translationTargetLang.value
            val textToSpeak = if (targetLang.isNotEmpty() && targetLang != "none") {
                translateText(sentence.text, targetLang)
            } else {
                sentence.text
            }

            if (!isActive || playbackToken != activePlaybackToken) return@launch
            configureVoiceForLanguage(targetLang)
            tts?.setSpeechRate(_playbackSpeed.value)
            startPlaybackService()

            val utteranceId = buildUtteranceId(playbackToken, index)
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            _isPlaying.value = true
        }
    }

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
        _sleepTimerMinutes.value = minutes
        _sleepTimerRemainingSeconds.value = minutes * 60
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
        val resolvedLocale = preferredLocale ?: currentTts.language ?: Locale.US
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

        val currentLocale = preferredLocale ?: currentTts.language ?: Locale.US
        val localeVoices = available.filter { isLanguageMatch(it.locale, currentLocale) }
        if (localeVoices.isEmpty()) return

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
            if (isNetworkAvailable()) {
                selectedVoice = localeVoices.firstOrNull { it.name.contains("iol-network", ignoreCase = true) }
                    ?: localeVoices.firstOrNull { it.isNetworkConnectionRequired }
            }
        }

        if (selectedVoice == null) {
            val installedOffline = localeVoices.filter {
                !it.isNetworkConnectionRequired &&
                    (it.features == null || !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            }
            selectedVoice = installedOffline.sortedWith(
                compareByDescending<Voice> {
                    it.name.contains("iol-local", ignoreCase = true) || it.name.contains("lol-local", ignoreCase = true)
                }.thenByDescending {
                    it.name.contains("iom-local", ignoreCase = true) || it.name.contains("lom-local", ignoreCase = true)
                }.thenByDescending {
                    isCountryMatch(it.locale, currentLocale)
                }.thenByDescending {
                    it.quality
                }
            ).firstOrNull() ?: localeVoices.firstOrNull()
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
        currentTts.setPitch(0.98f)
    }

    private fun restartPlaybackFromCurrentSentence() {
        val index = _currentSentenceIndex.value
        if (!_isInitialized.value || sentences.isEmpty() || index !in sentences.indices) return
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = true
        speakSentence(index, activePlaybackToken)
    }

    private fun invalidatePlaybackState(stopAudio: Boolean) {
        activePlaybackToken = playbackTokenCounter.incrementAndGet()
        currentSpeakJob?.cancel()
        currentSpeakJob = null
        wordHighlightJob?.cancel()
        wordHighlightJob = null
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
        val parts = raw.split(':', limit = 2)
        if (parts.size != 2) return null
        val token = parts[0].toLongOrNull() ?: return null
        val sentenceIndex = parts[1].toIntOrNull() ?: return null
        return PlaybackState(token, sentenceIndex)
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

        _availableVoices.value = available
            .filter { isLanguageMatch(it.locale, locale) }
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
            .sortedBy { it.displayName }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
        val isNetwork = voiceName.endsWith("network", ignoreCase = true)
        val suffix = if (isNetwork) " (Female)" else " (Male)"
        return when (persona) {
            "iol", "lol" -> "Voice A$suffix"
            "iom", "lom" -> "Voice B$suffix"
            "iog" -> "Voice C"
            "tpc" -> "Voice D"
            "sfg" -> "Voice E"
            "tpf" -> "Voice F"
            "tpd" -> "Voice G"
            "iob" -> "Voice H"
            "msm" -> "Voice I"
            "hia" -> "Hindi A"
            "hic" -> "Hindi B"
            "hid" -> "Hindi C"
            "hie" -> "Hindi D"
            else -> "Voice ${persona.uppercase()}$suffix"
        }
    }

    fun shutdown() {
        timerJob?.cancel()
        currentSpeakJob?.cancel()
        scope.cancel()
        tts?.shutdown()
        tts = null
        if (instance == this) {
            instance = null
        }
    }

    private data class PlaybackState(
        val token: Long,
        val sentenceIndex: Int
    )
}
