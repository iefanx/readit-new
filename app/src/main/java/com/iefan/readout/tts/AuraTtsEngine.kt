package com.iefan.readout.tts

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AuraTtsEngine(private val context: Context) : TextToSpeech.OnInitListener {

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

    private val _selectedModelTier = MutableStateFlow("BALANCED") // "ULTRA_LIGHT", "BALANCED", "HIGH_FIDELITY"
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

    fun loadDocument(text: String, startSentenceIndex: Int = 0) {
        sentences = DocumentParser.parse(text)
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
        // Model customization - simulating change characteristics
        when (tier) {
            "ULTRA_LIGHT" -> {
                // Low footprint, fast response voice settings
                tts?.setPitch(1.1f)
            }
            "BALANCED" -> {
                // Sweet spot, human voice settings
                tts?.setPitch(1.0f)
            }
            "HIGH_FIDELITY" -> {
                // High fidelity depth voice settings
                tts?.setPitch(0.9f)
            }
        }
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
        
        // Ensure tts is configured with current settings
        tts?.setSpeechRate(_playbackSpeed.value)
        
        // We use TextToSpeech.QUEUE_FLUSH to override any ongoing utterance
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, index.toString())
        }
        tts?.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, params, index.toString())
        _isPlaying.value = true
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

    fun shutdown() {
        timerJob?.cancel()
        scope.cancel()
        tts?.shutdown()
    }
}
