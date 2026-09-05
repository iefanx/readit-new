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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                _currentWordRange.value = null
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

    private val _currentWordRange = MutableStateFlow<Pair<Int, Int>?>(null)
    val currentWordRange = _currentWordRange.asStateFlow()

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

    private val spokenSentenceTextMap = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private var sentences: List<SpeechSentence> = emptyList()

    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes = _sleepTimerMinutes.asStateFlow()

    private val _sleepTimerRemainingSeconds = MutableStateFlow(0)
    val sleepTimerRemainingSeconds = _sleepTimerRemainingSeconds.asStateFlow()

    private var timerJob: Job? = null
    private var wordHighlightJob: Job? = null
    private var watchdogJob: Job? = null
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
        _translationTargetLang.value = langCode
        _translatedSentences.value = emptyMap()
        if (langCode.isNotEmpty() && langCode != "none") {
            downloadLanguagePackIfNeeded(Locale.forLanguageTag(langCode))
        }
        configureVoiceForLanguage(langCode)
        if (_isPlaying.value) {
            restartPlaybackFromCurrentSentence()
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
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("ReadoutTtsEngine", "English language is not supported or missing data.")
            } else {
                setupProgressListener()
                _isInitialized.value = true
                Log.d("ReadoutTtsEngine", "TTS initialized successfully.")
                updateAvailableVoicesForLocale(Locale.US)
                configureVoiceForTier(_selectedModelTier.value)
                if (pendingStartPlayback && sentences.isNotEmpty()) {
                    pendingStartPlayback = false
                    restartPlaybackFromCurrentSentence()
                }
            }
        } else {
            Log.e("ReadoutTtsEngine", "TTS initialization failed.")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId?.startsWith("preview:") == true) {
                    _previewingVoiceId.value = utteranceId.substringAfter("preview:")
                    return
                }
                wordHighlightJob?.cancel()
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return

                // Cancel any pending stall watchdog immediately on start of any sentence
                watchdogJob?.cancel()
                watchdogJob = null

                _currentSentenceIndex.value = playbackState.sentenceIndex
                _currentWordRange.value = null
                _isPlaying.value = true

                // Keep prebuffering sliding window ahead: buffer up to sentenceIndex + 2
                enqueueSentencesUpTo(playbackState.sentenceIndex + 2, playbackState.token)
                prefetchTranslationsAhead(playbackState.sentenceIndex + 3)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("preview:") == true) {
                    _previewingVoiceId.value = null
                    return
                }
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return

                if (playbackState.sentenceIndex >= sentences.lastIndex) {
                    val finalSentence = sentences.getOrNull(playbackState.sentenceIndex)
                    _isPlaying.value = false
                    _currentWordRange.value = finalSentence?.let { Pair(it.end, it.end) }
                    stopPlaybackService()
                    abandonAudioFocus()
                } else {
                    // Safety-net watchdog: Android TTS already has the next sentence queued with QUEUE_ADD
                    // and will transition seamlessly with 0ms gap.
                    // If hardware or engine stalls for >2500ms without onStart, recover automatically.
                    val nextIndex = playbackState.sentenceIndex + 1
                    watchdogJob?.cancel()
                    watchdogJob = scope.launch {
                        delay(2500)
                        if (playbackState.token == activePlaybackToken && _isPlaying.value && _currentSentenceIndex.value <= playbackState.sentenceIndex) {
                            Log.w("ReadoutTtsEngine", "Playback stall watchdog: recovering at sentence $nextIndex")
                            startPlaybackAtSentence(nextIndex)
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith("preview:") == true) {
                    _previewingVoiceId.value = null
                    return
                }
                Log.e("ReadoutTtsEngine", "TTS error on sentence $utteranceId")
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return
                watchdogJob?.cancel()
                watchdogJob = null

                // Attempt graceful recovery by advancing to next sentence
                val nextIndex = playbackState.sentenceIndex + 1
                if (nextIndex in sentences.indices && _isPlaying.value) {
                    Log.d("ReadoutTtsEngine", "Recovering from TTS error by advancing to sentence $nextIndex")
                    startPlaybackAtSentence(nextIndex)
                } else {
                    _isPlaying.value = false
                    _currentWordRange.value = null
                    stopPlaybackService()
                    abandonAudioFocus()
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId?.startsWith("preview:") == true) return
                val playbackState = parseUtteranceId(utteranceId) ?: return
                if (playbackState.token != activePlaybackToken) return

                val sentence = sentences.getOrNull(playbackState.sentenceIndex) ?: return
                val spokenText = spokenSentenceTextMap[playbackState.sentenceIndex] ?: sentence.text

                wordHighlightJob?.cancel()
                wordHighlightJob = scope.launch {
                    val speed = _playbackSpeed.value.coerceAtLeast(0.5f)
                    // Hardware-calibrated 35ms sync delay: eliminates visual highlight drift
                    val adjustedDelay = (35L / speed).toLong().coerceIn(0L, 70L)
                    if (adjustedDelay > 0) delay(adjustedDelay)
                    if (playbackState.token != activePlaybackToken) return@launch

                    val absStart: Int
                    val absEnd: Int

                    if (spokenText == sentence.text) {
                        absStart = sentence.start + start.coerceIn(0, sentence.text.length)
                        absEnd = sentence.start + end.coerceIn(0, sentence.text.length)
                    } else {
                        // Normalization or translation: map proportional progress to original sentence words
                        if (sentence.words.isNotEmpty()) {
                            val progress = (start.toFloat() / spokenText.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val wordIdx = (progress * sentence.words.size).toInt().coerceIn(0, sentence.words.lastIndex)
                            val word = sentence.words[wordIdx]
                            absStart = word.start
                            absEnd = word.end
                        } else {
                            val progress = (start.toFloat() / spokenText.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val charIdx = (progress * sentence.text.length).toInt().coerceIn(0, sentence.text.length)
                            absStart = sentence.start + charIdx
                            absEnd = sentence.start + (charIdx + (end - start)).coerceIn(charIdx, sentence.text.length)
                        }
                    }

                    _currentWordRange.value = Pair(absStart, absEnd)
                }
            }
        })
    }

    fun loadDocument(documentId: Long, sentencesList: List<SpeechSentence>, title: String, startSentenceIndex: Int = 0) {
        this.documentId = documentId
        documentTitle = title
        sentences = sentencesList
        spokenSentenceTextMap.clear()
        _translatedSentences.value = emptyMap()
        totalCharacters = sentencesList.lastOrNull()?.end ?: 0
        _currentSentenceIndex.value = startSentenceIndex.coerceIn(0, maxOf(0, sentences.lastIndex))
        _currentWordRange.value = null
        stop()
    }

    fun getSentences(): List<SpeechSentence> = sentences

    fun startPlayback() {
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
        restartPlaybackFromCurrentSentence()
    }

    fun pausePlayback() {
        resumeOnFocusGain = false
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = false
        _currentWordRange.value = null
        abandonAudioFocus()
    }

    fun stop() {
        resumeOnFocusGain = false
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = false
        _currentWordRange.value = null
        stopPlaybackService()
        abandonAudioFocus()
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

        val localeVoices = available.filter { isLanguageMatch(it.locale, targetLocale) }
        if (localeVoices.isEmpty()) {
            Log.w("ReadoutTtsEngine", "No voices found for language $langCode, falling back to setLanguage only")
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
        requestAudioFocus()
        invalidatePlaybackState(stopAudio = true)
        _isPlaying.value = true
        _currentSentenceIndex.value = index
        _currentWordRange.value = null

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
        scope.launch {
            enqueueMutex.withLock {
                if (playbackToken != activePlaybackToken || !_isPlaying.value) return@withLock
                val start = if (highestQueuedIndex < 0) {
                    _currentSentenceIndex.value
                } else {
                    highestQueuedIndex + 1
                }
                val end = targetMaxIndex.coerceAtMost(sentences.lastIndex)
                if (start > end) return@withLock

                for (i in start..end) {
                    if (playbackToken != activePlaybackToken || !_isPlaying.value) return@withLock
                    val textToSpeak = prepareSpokenText(i)
                    if (playbackToken != activePlaybackToken || !_isPlaying.value) return@withLock

                    val isFirstInStream = (highestQueuedIndex < 0)
                    val queueMode = if (isFirstInStream) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val utteranceId = buildUtteranceId(playbackToken, i)
                    val params = android.os.Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    highestQueuedIndex = i
                    val res = tts?.speak(textToSpeak, queueMode, params, utteranceId)
                    Log.d("ReadoutTtsEngine", "Enqueued sentence $i (mode=${if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"}, res=$res): ${textToSpeak.take(40)}")
                }
            }
        }
    }

    private suspend fun prepareSpokenText(index: Int): String {
        val cached = spokenSentenceTextMap[index]
        if (cached != null) return cached

        val sentence = sentences.getOrNull(index) ?: return ""
        val targetLang = _translationTargetLang.value
        val rawText = if (targetLang.isNotEmpty() && targetLang != "none") {
            val translated = translateText(sentence.text, targetLang)
            _translatedSentences.update { it + (index to translated) }
            translated
        } else {
            sentence.text
        }
        val textToSpeak = SpokenTextNormalizer.normalizeForSpeech(rawText)
        spokenSentenceTextMap[index] = textToSpeak
        if (spokenSentenceTextMap.size > 128) {
            val minKeep = (index - 32).coerceAtLeast(0)
            val maxKeep = index + 64
            spokenSentenceTextMap.keys.retainAll { it in minKeep..maxKeep }
        }
        return textToSpeak
    }

    private fun prefetchTranslationsAhead(fromIndex: Int) {
        val targetLang = _translationTargetLang.value
        if (targetLang.isEmpty() || targetLang == "none") return
        scope.launch(Dispatchers.IO) {
            for (idx in fromIndex until minOf(fromIndex + 4, sentences.size)) {
                if (!spokenSentenceTextMap.containsKey(idx)) {
                    prepareSpokenText(idx)
                }
            }
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
        watchdogJob?.cancel()
        watchdogJob = null
        wordHighlightJob?.cancel()
        wordHighlightJob = null
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
        val parts = raw.split(':', limit = 2)
        if (parts.size != 2) return null
        val token = parts[0].toLongOrNull() ?: return null
        val sentenceIndex = parts[1].toIntOrNull() ?: return null
        return PlaybackState(token, sentenceIndex)
    }

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
        val sentenceIndex: Int
    )
}
