package com.iefan.readout.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AuraTtsEngine(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var instance: AuraTtsEngine? = null
            private set
    }

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

    private val _selectedModelTier = MutableStateFlow("HIGH_FIDELITY") // "ULTRA_LIGHT", "BALANCED", "HIGH_FIDELITY"
    val selectedModelTier = _selectedModelTier.asStateFlow()

    // Loaded document sentences
    private var sentences: List<SpeechSentence> = emptyList()

    // Sleep Timer duration in minutes (0 means inactive)
    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes = _sleepTimerMinutes.asStateFlow()
    
    private val _sleepTimerRemainingSeconds = MutableStateFlow(0)
    val sleepTimerRemainingSeconds = _sleepTimerRemainingSeconds.asStateFlow()

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        instance = this
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("AuraTtsEngine", "English language is not supported or missing data.")
            } else {
                setupProgressListener()
                _isInitialized.value = true
                Log.d("AuraTtsEngine", "TTS initialized successfully.")
                configureVoiceForTier(_selectedModelTier.value)
            }
        } else {
            Log.e("AuraTtsEngine", "TTS Initialization failed.")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val index = utteranceId?.toIntOrNull() ?: return
                _currentSentenceIndex.value = index
                _isPlaying.value = true
            }

            override fun onDone(utteranceId: String?) {
                val index = utteranceId?.toIntOrNull() ?: return
                // Check if there are more sentences to read
                scope.launch {
                    val nextIndex = index + 1
                    if (nextIndex < sentences.size) {
                        speakSentence(nextIndex)
                    } else {
                        _isPlaying.value = false
                        _currentWordRange.value = null
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e("AuraTtsEngine", "TTS error on sentence $utteranceId")
                _isPlaying.value = false
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val sentenceIdx = utteranceId?.toIntOrNull() ?: return
                val sentence = sentences.getOrNull(sentenceIdx) ?: return

                // start/end in onRangeStart are relative to the sentence text
                // Let's compute absolute document coordinates of the spoken word
                val absStart = sentence.start + start
                val absEnd = sentence.start + end
                _currentWordRange.value = Pair(absStart, absEnd)
            }
        })
    }

    fun loadDocument(sentencesList: List<SpeechSentence>, title: String, startSentenceIndex: Int = 0) {
        documentTitle = title
        sentences = sentencesList
        totalCharacters = sentencesList.lastOrNull()?.end ?: 0
        _currentSentenceIndex.value = startSentenceIndex.coerceIn(0, maxOf(0, sentences.size - 1))
        _currentWordRange.value = null
        stop()
    }

    fun startPlayback() {
        if (!_isInitialized.value || sentences.isEmpty()) return
        speakSentence(_currentSentenceIndex.value)
    }

    fun pausePlayback() {
        tts?.stop()
        _isPlaying.value = false
        _currentWordRange.value = null
    }

    fun stop() {
        tts?.stop()
        _isPlaying.value = false
        _currentWordRange.value = null
    }

    fun setSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.5f, 4.5f)
        _playbackSpeed.value = bounded
        tts?.setSpeechRate(bounded)
        // If playing, restart current sentence with the new speed
        if (_isPlaying.value) {
            speakSentence(_currentSentenceIndex.value)
        }
    }

    fun setModelTier(tier: String) {
        _selectedModelTier.value = tier
        configureVoiceForTier(tier)
        // If playing, restart current sentence
        if (_isPlaying.value) {
            speakSentence(_currentSentenceIndex.value)
        }
    }

    fun seekToSentence(index: Int) {
        if (sentences.isEmpty()) return
        val targetIdx = index.coerceIn(0, sentences.size - 1)
        _currentSentenceIndex.value = targetIdx
        _currentWordRange.value = null
        if (_isPlaying.value) {
            speakSentence(targetIdx)
        }
    }

    fun seekToCharacter(charIndex: Int) {
        if (sentences.isEmpty()) return
        val targetIdx = sentences.indexOfFirst { charIndex in it.start..it.end }
        if (targetIdx != -1) {
            seekToSentence(targetIdx)
        } else {
            val closest = sentences.minByOrNull { Math.abs(it.start - charIndex) }
            if (closest != null) {
                seekToSentence(closest.index)
            }
        }
    }

    fun skipForward15s() {
        // Skip ahead by sentences (roughly 3 sentences ~ 15 seconds)
        val target = (_currentSentenceIndex.value + 3).coerceAtMost(sentences.size - 1)
        seekToSentence(target)
    }

    fun skipBackward15s() {
        // Skip back by sentences (roughly 3 sentences ~ 15 seconds)
        val target = (_currentSentenceIndex.value - 3).coerceAtLeast(0)
        seekToSentence(target)
    }

    private fun speakSentence(index: Int) {
        val sentence = sentences.getOrNull(index) ?: return
        
        // Ensure the correct voice configuration is applied at speech time to prevent race conditions
        configureVoiceForTier(_selectedModelTier.value)
        tts?.setSpeechRate(_playbackSpeed.value)
        
        startPlaybackService()
        
        // We use TextToSpeech.QUEUE_FLUSH to override any ongoing utterance
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, index.toString())
        }
        tts?.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, params, index.toString())
        _isPlaying.value = true
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
            // Sleep Timer Finished!
            pausePlayback()
            _sleepTimerMinutes.value = 0
        }
    }

    private fun configureVoiceForTier(tier: String) {
        val currentTts = tts ?: return
        val availableVoices = try {
            currentTts.voices
        } catch (e: Exception) {
            Log.e("AuraTtsEngine", "Failed to retrieve voices", e)
            null
        }

        if (availableVoices.isNullOrEmpty()) {
            when (tier) {
                "ULTRA_LIGHT" -> currentTts.setPitch(1.05f)
                "BALANCED" -> currentTts.setPitch(1.0f)
                "HIGH_FIDELITY" -> currentTts.setPitch(0.95f)
            }
            return
        }

        val englishVoices = availableVoices.filter {
            it.locale.language.lowercase() == "en"
        }

        if (englishVoices.isEmpty()) {
            currentTts.setLanguage(Locale.US)
            return
        }

        val offlineVoices = englishVoices.filter { !it.isNetworkConnectionRequired }
        val candidateVoices = if (offlineVoices.isNotEmpty()) offlineVoices else englishVoices

        when (tier) {
            "ULTRA_LIGHT" -> {
                val defaultVoice = candidateVoices.lastOrNull { !it.isNetworkConnectionRequired } ?: candidateVoices.firstOrNull()
                if (defaultVoice != null) {
                    currentTts.voice = defaultVoice
                }
                currentTts.setPitch(1.05f)
            }
            "BALANCED" -> {
                val balancedVoice = candidateVoices.firstOrNull { it.name.contains("en-us-x-local", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.name.contains("local", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.quality >= 300 }
                    ?: candidateVoices.firstOrNull()
                
                if (balancedVoice != null) {
                    currentTts.voice = balancedVoice
                }
                currentTts.setPitch(1.0f)
            }
            "HIGH_FIDELITY" -> {
                val bestVoice = candidateVoices.firstOrNull { it.name.contains("en-us-x-iol", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.name.contains("en-gb-x-iol", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.name.contains("en-us-x-sfg", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.name.contains("en-us-x-iom", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.name.contains("en-us-x-iog", ignoreCase = true) }
                    ?: candidateVoices.firstOrNull { it.quality >= 400 }
                    ?: candidateVoices.firstOrNull { it.quality >= 300 }
                    ?: candidateVoices.firstOrNull()

                if (bestVoice != null) {
                    currentTts.voice = bestVoice
                }
                currentTts.setPitch(1.0f)
            }
        }
    }

    fun shutdown() {
        timerJob?.cancel()
        scope.cancel()
        tts?.shutdown()
        tts = null
        if (instance == this) {
            instance = null
        }
    }
}
