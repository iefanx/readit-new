package com.iefan.readout.viewmodel

import android.app.Application
import android.util.Log
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.iefan.readout.ui.theme.OledPrimary
import com.iefan.readout.data.*
import com.iefan.readout.tts.ReadoutTtsEngine
import com.iefan.readout.tts.DocumentParser
import com.iefan.readout.tts.SpeechSentence
import com.iefan.readout.tts.VoiceInfo
import com.iefan.readout.tts.VoiceStatus
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BenchmarkResult(
    val cores: Int,
    val memoryGb: Double,
    val npuDetected: Boolean,
    val recommendedTier: String
)

data class ExtractedDocument(val content: String, val chapters: List<ChapterCandidate>)

data class ImportTaskProgress(
    val isImporting: Boolean = false,
    val currentItemIndex: Int = 0,
    val totalItems: Int = 0,
    val currentTitle: String = "",
    val currentStage: String = "",
    val progressFraction: Float = 0f
)

data class BatchImportItem(
    val uri: android.net.Uri,
    val title: String?,
    val customCoverUri: android.net.Uri? = null,
    val isFavorite: Boolean = false,
    val collectionId: Long? = null
)

// Pre-compiled once at class level — avoids allocating a Regex on every line of text during PDF/EPUB parsing
private val BULLET_LIST_REGEX = Regex("^\\d+\\.\\s+.*")

class ReadoutViewModel(application: Application) : AndroidViewModel(application) {
    private data class CachedSentences(
        val contentHash: Int,
        val sentences: List<SpeechSentence>
    )

    private val sentenceCache = object : LinkedHashMap<Long, CachedSentences>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedSentences>?): Boolean {
            return size > 6
        }
    }

    private val documentRepository: DocumentRepository
    private val ttsEngine: ReadoutTtsEngine

    // All books / articles in library
    val allDocuments: StateFlow<List<Document>>

    private val _isLibraryOpen = MutableStateFlow(false)
    val isLibraryOpen = _isLibraryOpen.asStateFlow()

    private val _collectionOrder = MutableStateFlow<List<Long>>(emptyList())
    val allCollections: StateFlow<List<CollectionEntity>>
    val allCrossRefs: StateFlow<List<DocumentCollectionCrossRef>>
    val allBookmarks: StateFlow<List<Bookmark>>

    // Currently playing/viewed document
    private val _activeDocument = MutableStateFlow<Document?>(null)
    val activeDocument = _activeDocument.asStateFlow()

    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded = _isPlayerExpanded.asStateFlow()

    private val _isPreparingPlayback = MutableStateFlow(false)
    val isPreparingPlayback = _isPreparingPlayback.asStateFlow()

    // Import visual loading state and granular progress tracking
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportTaskProgress())
    val importProgress = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    fun clearImportError() {
        _importError.value = null
    }

    // Structured sentences for high-performance follow / jump highlight
    private val _activeSentences = MutableStateFlow<List<SpeechSentence>>(emptyList())
    val activeSentences = _activeSentences.asStateFlow()

    private val _activeChapters = MutableStateFlow<List<Chapter>>(emptyList())
    val activeChapters = _activeChapters.asStateFlow()

    private val _activeBookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val activeBookmarks = _activeBookmarks.asStateFlow()

    // Cancellable job that keeps activeBookmarks in sync with the current document
    private var bookmarkCollectionJob: kotlinx.coroutines.Job? = null
    private var documentSelectionJob: Job? = null

    // State bindings straight from TTS Engine
    val isPlaying: StateFlow<Boolean>
    val currentSentenceIndex: StateFlow<Int>
    val currentWordRange: StateFlow<Pair<Int, Int>?>
    val playbackSpeed: StateFlow<Float>
    val selectedModelTier: StateFlow<String>
    val sleepTimerMinutes: StateFlow<Int>
    val sleepTimerRemainingSeconds: StateFlow<Int>
    val selectedVoiceId: StateFlow<String>
    val previewingVoiceId: StateFlow<String?>
    val availableVoices: StateFlow<List<VoiceInfo>>
    val translationTargetLang: StateFlow<String>
    val translatedSentences: StateFlow<Map<Int, String>>

    private val _themeColor = MutableStateFlow<Color>(OledPrimary)
    val themeColor = _themeColor.asStateFlow()

    // Hardware Benchmark flow
    private val _benchmarkProgress = MutableStateFlow<Float?>(null)
    val benchmarkProgress = _benchmarkProgress.asStateFlow()

    private val _benchmarkResult = MutableStateFlow<BenchmarkResult?>(null)
    val benchmarkResult = _benchmarkResult.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        documentRepository = DocumentRepository(database.documentDao())
        ttsEngine = ReadoutTtsEngine(application)

        val sharedPrefs = application.getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
        val savedColorInt = sharedPrefs.getInt("theme_color", OledPrimary.toArgb())
        _themeColor.value = Color(savedColorInt)

        val savedTier = sharedPrefs.getString("selected_voice_tier", "HIGH_FIDELITY") ?: "HIGH_FIDELITY"
        ttsEngine.setModelTier(savedTier)
        val savedVoiceId = sharedPrefs.getString("selected_voice_id", "default") ?: "default"
        ttsEngine.setSelectedVoiceId(savedVoiceId)
        val savedTranslation = sharedPrefs.getString("translation_target_lang", "none") ?: "none"
        ttsEngine.setTranslationTargetLang(savedTranslation)

        val orderStr = sharedPrefs.getString("collection_order", "") ?: ""
        val savedOrder = if (orderStr.isNotEmpty()) orderStr.split(",").mapNotNull { it.toLongOrNull() } else emptyList()
        _collectionOrder.value = savedOrder

        allDocuments = documentRepository.allDocuments
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        allCollections = documentRepository.allCollections
            .combine(_collectionOrder) { collections, order ->
                if (order.isEmpty()) {
                    collections.sortedByDescending { it.addedDate }
                } else {
                    val orderMap = order.withIndex().associate { it.value to it.index }
                    collections.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        allCrossRefs = documentRepository.allCrossRefs
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        allBookmarks = documentRepository.getAllBookmarksFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        // Bind flows from Engine to ViewModel State Flows
        isPlaying = ttsEngine.isPlaying
        currentSentenceIndex = ttsEngine.currentSentenceIndex
        currentWordRange = ttsEngine.currentWordRange
        playbackSpeed = ttsEngine.playbackSpeed
        selectedModelTier = ttsEngine.selectedModelTier
        sleepTimerMinutes = ttsEngine.sleepTimerMinutes
        sleepTimerRemainingSeconds = ttsEngine.sleepTimerRemainingSeconds
        selectedVoiceId = ttsEngine.selectedVoiceId
        previewingVoiceId = ttsEngine.previewingVoiceId
        availableVoices = ttsEngine.availableVoices
        translationTargetLang = ttsEngine.translationTargetLang
        translatedSentences = ttsEngine.translatedSentences

        // Listen for sentence changes to update Room position with a debounce of 3 seconds
        viewModelScope.launch {
            currentSentenceIndex.collectLatest { index ->
                activeDocument.value?.let { doc ->
                    val sentencesList = _activeSentences.value
                    if (sentencesList.isNotEmpty() && index < sentencesList.size) {
                        val currentSentence = sentencesList[index]
                        delay(3000)
                        val stillSameDocument = activeDocument.value?.id == doc.id
                        val stillSameSentence = currentSentenceIndex.value == index
                        if (isPlaying.value && stillSameDocument && stillSameSentence) {
                            documentRepository.updatePlaybackPosition(doc.id, currentSentence.start)
                        }
                    }
                }
            }
        }

        // Trigger preloads if we haven't preloaded v4 samples before.
        // Also backfill contentLength for any existing documents that have contentLength <= 0.
        viewModelScope.launch {
            val hasPreloaded = sharedPrefs.getBoolean("has_preloaded_samples_v4", false)
            if (!hasPreloaded) {
                val list = allDocuments.first()
                for (doc in list) {
                    if (doc.title == "The Art of Focus" ||
                        doc.title == "A Brief History of Speed Audio" ||
                        doc.title == "About this app, what this app can do" ||
                        doc.title == "The Odyssey") {
                        documentRepository.delete(doc)
                    }
                }
                preloadSampleBooks()
                sharedPrefs.edit().putBoolean("has_preloaded_samples_v4", true).apply()
            } else {
                // Ensure existing docs have valid contentLength
                val list = allDocuments.first()
                for (doc in list) {
                    if (doc.contentLength <= 0) {
                        try {
                            val fullDoc = documentRepository.getDocumentById(doc.id)
                            if (fullDoc != null && fullDoc.content.isNotEmpty()) {
                                documentRepository.update(fullDoc.copy(contentLength = fullDoc.content.length))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            allDocuments
                .map { docs -> docs.map { it.id } }
                .distinctUntilChanged()
                .collectLatest { docIds ->
                    val docsToWarm = allDocuments.value.filter { it.id in docIds.take(4) }
                    if (docsToWarm.isNotEmpty()) {
                        warmSentenceCache(docsToWarm)
                    }
                }
        }
    }

    private suspend fun preloadSampleBooks() {
        val context = getApplication<Application>()
        
        // 1. Load Odyssey from Assets
        val odysseyDoc = withContext(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "The_Odyssey_by_Homer.epub")
                context.assets.open("The Odyssey by Homer.epub").use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val extracted = extractTextFromEpub(tempFile)
                val coverPath = extractEpubCover(tempFile)
                tempFile.delete()
                val contentStr = extracted.content.ifBlank { "Error parsing The Odyssey." }
                
                Document(
                    title = "The Odyssey",
                    content = contentStr,
                    sourceUrl = "Homer",
                    coverPath = coverPath,
                    selectedModelTier = "HIGH_FIDELITY",
                    playbackSpeed = 1.0f,
                    contentLength = contentStr.length
                ) to extracted.chapters
            } catch (e: Exception) {
                e.printStackTrace()
                val fallbackContent = "Error loading The Odyssey from assets."
                Document(
                    title = "The Odyssey",
                    content = fallbackContent,
                    sourceUrl = "Homer",
                    selectedModelTier = "HIGH_FIDELITY",
                    playbackSpeed = 1.0f,
                    contentLength = fallbackContent.length
                ) to emptyList<ChapterCandidate>()
            }
        }

        // 2. Load "About this app" document
        val aboutContent = """Welcome to Readout, your premium, distraction-free reading assistant.

Here is what this app can do:

1. Hybrid Speech System: You can choose between using Google's high-fidelity online Text-to-Speech models (requires an active network connection) or falling back to standard offline voices for 100% network-free playback.

2. Built-in Translation: Upload a document in any language and listen to it seamlessly translated into your preferred language of choice.

3. Voice & Speech Preferences: Customize your listening experience by selecting from a wide range of available voices to hear the narration in your preferred speech style.

4. Sleep Countdown Timer: Set a sleep timer from the settings panel to automatically pause audio playback after a specified amount of time.

5. Interactive Navigation & Speed Control: Adjust playback speeds fluidly from 0.5x up to 4.5x. Tap or drag the progress bar to skip, double-tap on any sentence to jump the reader to that location, and view cumulative follow-along karaoke highlights as you listen.""".trimIndent()

        val aboutDoc = Document(
            title = "About this app, what this app can do",
            content = aboutContent,
            sourceUrl = "Readout User Guide",
            selectedModelTier = "HIGH_FIDELITY",
            playbackSpeed = 1.0f,
            contentLength = aboutContent.length
        )

        // Insert Odyssey
        val odysseyId = documentRepository.insert(odysseyDoc.first)
        if (odysseyDoc.second.isNotEmpty()) {
            val dbChapters = odysseyDoc.second.map { candidate ->
                Chapter(
                    documentId = odysseyId,
                    title = candidate.title,
                    startCharOffset = candidate.charOffset
                )
            }
            documentRepository.insertChapters(dbChapters)
        }

        // Insert About
        documentRepository.insert(aboutDoc)
    }

    fun selectDocument(document: Document) {
        if (_activeDocument.value?.id == document.id) {
            _isPlayerExpanded.value = true
            ttsEngine.startPlayback()
            return
        }

        documentSelectionJob?.cancel()
        ttsEngine.stop()
        _isPreparingPlayback.value = true
        _isPlayerExpanded.value = true

        documentSelectionJob = viewModelScope.launch {
            try {
                val fullDoc = documentRepository.getDocumentById(document.id) ?: document
                val targetLength = if (fullDoc.contentLength > 0) fullDoc.contentLength else fullDoc.content.length
                val updatedDoc = fullDoc.copy(
                    lastReadTime = System.currentTimeMillis(),
                    contentLength = targetLength
                )
                _activeDocument.value = updatedDoc
                _activeSentences.value = getCachedSentences(updatedDoc).orEmpty()
                documentRepository.update(updatedDoc)

                val chapters = documentRepository.getChaptersForDocument(updatedDoc.id)
                _activeChapters.value = chapters

                // Collect bookmarks reactively — cancel any previous document's stream first
                bookmarkCollectionJob?.cancel()
                bookmarkCollectionJob = viewModelScope.launch {
                    documentRepository.getBookmarksForDocumentFlow(updatedDoc.id)
                        .collect { bookmarks -> _activeBookmarks.value = bookmarks }
                }

                val parsedSentences = getOrParseSentences(updatedDoc)
                _activeSentences.value = parsedSentences

                // Find best starting sentence index based on saved playback position
                var savedSentenceIdx = 0
                for ((idx, sent) in parsedSentences.withIndex()) {
                    if (updatedDoc.playbackPosition in sent.start..sent.end) {
                        savedSentenceIdx = idx
                        break
                    }
                }

                ttsEngine.loadDocument(updatedDoc.id, parsedSentences, updatedDoc.title, savedSentenceIdx)
                ttsEngine.setModelTier(updatedDoc.selectedModelTier)
                ttsEngine.setSpeed(updatedDoc.playbackSpeed)
                ttsEngine.startPlayback()
            } finally {
                if (_activeDocument.value?.id == document.id) {
                    _isPreparingPlayback.value = false
                }
            }
        }
    }

    fun seekToBookmark(bookmark: Bookmark) {
        seekToSentence(bookmark.sentenceIndex)
    }

    fun selectBookmark(bookmark: Bookmark) {
        documentSelectionJob?.cancel()
        documentSelectionJob = viewModelScope.launch {
            try {
                val doc = documentRepository.getDocumentById(bookmark.documentId)
                if (doc != null) {
                    if (_activeDocument.value?.id == doc.id) {
                        seekToBookmark(bookmark)
                        _isPlayerExpanded.value = true
                        ttsEngine.startPlayback()
                        return@launch
                    }

                    ttsEngine.stop()
                    _isPreparingPlayback.value = true
                    _isPlayerExpanded.value = true
                    val updatedDoc = doc.copy(lastReadTime = System.currentTimeMillis())
                    _activeDocument.value = updatedDoc
                    _activeSentences.value = getCachedSentences(updatedDoc).orEmpty()
                    documentRepository.update(updatedDoc)

                    val chapters = documentRepository.getChaptersForDocument(updatedDoc.id)
                    _activeChapters.value = chapters

                    bookmarkCollectionJob?.cancel()
                    bookmarkCollectionJob = viewModelScope.launch {
                        documentRepository.getBookmarksForDocumentFlow(updatedDoc.id)
                            .collect { bookmarks -> _activeBookmarks.value = bookmarks }
                    }

                    val parsedSentences = getOrParseSentences(updatedDoc)
                    _activeSentences.value = parsedSentences

                    val targetIndex = bookmark.sentenceIndex.coerceIn(0, maxOf(0, parsedSentences.size - 1))
                    ttsEngine.loadDocument(updatedDoc.id, parsedSentences, updatedDoc.title, targetIndex)
                    ttsEngine.setModelTier(updatedDoc.selectedModelTier)
                    ttsEngine.setSpeed(updatedDoc.playbackSpeed)
                    ttsEngine.startPlayback()
                }
            } finally {
                if (_activeDocument.value?.id == bookmark.documentId) {
                    _isPreparingPlayback.value = false
                }
            }
        }
    }

    fun addBookmark(sentenceIndex: Int, charOffset: Int, label: String) {
        val doc = _activeDocument.value ?: return
        viewModelScope.launch {
            documentRepository.insertBookmark(
                Bookmark(
                    documentId = doc.id,
                    sentenceIndex = sentenceIndex,
                    charOffset = charOffset,
                    label = label
                )
            )
            // activeBookmarks updates automatically via the reactive Flow collection job
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            documentRepository.deleteBookmark(bookmark)
            // activeBookmarks updates automatically via the reactive Flow collection job
        }
    }

    fun seekToChapter(chapter: Chapter) {
        val sentencesList = _activeSentences.value
        if (sentencesList.isEmpty()) return
        // Binary search: sentences are sorted by start offset, so O(log N) instead of O(N)
        var lo = 0; var hi = sentencesList.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sentencesList[mid].start < chapter.startCharOffset) lo = mid + 1 else hi = mid
        }
        // lo is now the first sentence whose start >= chapter.startCharOffset; back up one if closer
        val bestIdx = if (lo > 0 &&
            Math.abs(sentencesList[lo - 1].start - chapter.startCharOffset) <
            Math.abs(sentencesList[lo].start - chapter.startCharOffset)) lo - 1 else lo
        seekToSentence(bestIdx)
    }

    private fun saveCurrentPlaybackPosition() {
        val doc = _activeDocument.value ?: return
        val index = currentSentenceIndex.value
        val sentencesList = _activeSentences.value
        if (sentencesList.isNotEmpty() && index < sentencesList.size) {
            val currentSentence = sentencesList[index]
            viewModelScope.launch {
                documentRepository.updatePlaybackPosition(doc.id, currentSentence.start)
            }
        }
    }

    fun deselectDocument() {
        documentSelectionJob?.cancel()
        saveCurrentPlaybackPosition()
        ttsEngine.stop()
        _activeDocument.value = null
        _activeSentences.value = emptyList()
        _isPlayerExpanded.value = false
        _isPreparingPlayback.value = false
    }

    fun minimizePlayer() {
        saveCurrentPlaybackPosition()
        _isPlayerExpanded.value = false
    }

    fun expandPlayer() {
        _isPlayerExpanded.value = true
    }

    fun togglePlayback() {
        if (isPlaying.value) {
            ttsEngine.pausePlayback()
            saveCurrentPlaybackPosition()
        } else {
            // Restart from beginning if we completed the book
            val currentIdx = currentSentenceIndex.value
            val totalSentences = activeSentences.value.size
            if (totalSentences > 0 && currentIdx >= totalSentences - 1) {
                seekToSentence(0)
            }
            ttsEngine.startPlayback()
        }
    }

    fun seekToFraction(fraction: Float) {
        val sentences = _activeSentences.value
        if (sentences.isEmpty()) return
        val totalChars = sentences.last().end
        val targetChar = (fraction * totalChars).toInt()
        // Binary search: sentences are sorted by start offset (O(log N) vs O(N) linear scan).
        // Critical for smooth progress-bar scrubbing which fires events on every pixel of drag.
        var lo = 0; var hi = sentences.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sentences[mid].end < targetChar) lo = mid + 1 else hi = mid
        }
        seekToSentence(lo)
    }

    fun seekToSentence(index: Int) {
        ttsEngine.seekToSentence(index)
        val doc = _activeDocument.value ?: return
        val sentencesList = _activeSentences.value
        if (sentencesList.isNotEmpty() && index in sentencesList.indices) {
            val currentSentence = sentencesList[index]
            viewModelScope.launch {
                documentRepository.updatePlaybackPosition(doc.id, currentSentence.start)
            }
        }
    }

    fun skipForward() {
        ttsEngine.skipForward15s()
    }

    fun skipBackward() {
        ttsEngine.skipBackward15s()
    }

    fun setPlaybackSpeed(speed: Float) {
        ttsEngine.setSpeed(speed)
        viewModelScope.launch {
            _activeDocument.value?.let { doc ->
                documentRepository.updatePlaybackSpeed(doc.id, speed)
                _activeDocument.value = doc.copy(playbackSpeed = speed)
            }
        }
    }

    fun setModelTier(tier: String) {
        ttsEngine.setModelTier(tier)
        viewModelScope.launch {
            val sharedPrefs = getApplication<Application>().getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
            sharedPrefs.edit().putString("selected_voice_tier", tier).apply()

            _activeDocument.value?.let { doc ->
                documentRepository.updateModelTier(doc.id, tier)
                _activeDocument.value = doc.copy(selectedModelTier = tier)
            }
        }
    }

    fun setSelectedVoiceId(id: String) {
        ttsEngine.setSelectedVoiceId(id)
        val sharedPrefs = getApplication<Application>().getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_voice_id", id).apply()
    }

    fun previewVoice(voiceId: String, displayName: String = "", locale: java.util.Locale = java.util.Locale.US) {
        ttsEngine.previewVoice(voiceId, displayName, locale)
    }

    fun setTranslationTargetLang(langCode: String) {
        ttsEngine.setTranslationTargetLang(langCode)
        val sharedPrefs = getApplication<Application>().getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("translation_target_lang", langCode).apply()
    }

    fun setThemeColor(color: Color) {
        _themeColor.value = color
        val sharedPrefs = getApplication<Application>().getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putInt("theme_color", color.toArgb()).apply()
    }

    fun startSleepTimer(minutes: Int) {
        ttsEngine.startSleepTimer(minutes)
    }

    fun saveCoverFromUri(uri: android.net.Uri?): String? {
        if (uri == null) return null
        return try {
            val context = getApplication<Application>()
            val coverFile = File(context.filesDir, "cover_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(coverFile).use { output ->
                    input.copyTo(output)
                }
            }
            coverFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun insertDocumentInternal(
        title: String,
        content: String,
        sourceUrl: String?,
        coverPath: String? = null,
        chapters: List<ChapterCandidate> = emptyList(),
        autoSelect: Boolean = false,
        customCoverUri: android.net.Uri? = null,
        isFavorite: Boolean = false,
        collectionId: Long? = null
    ): Document = withContext(Dispatchers.IO) {
        val finalCover = saveCoverFromUri(customCoverUri) ?: coverPath
        val now = System.currentTimeMillis()
        val doc = Document(
            title = title.ifBlank { "Untitled Document" },
            content = content,
            sourceUrl = sourceUrl?.ifBlank { null },
            coverPath = finalCover,
            contentLength = content.length,
            isFavorite = isFavorite,
            addedDate = now,
            lastReadTime = now
        )
        val generatedId = documentRepository.insert(doc)
        val createdDoc = doc.copy(id = generatedId)
        
        if (collectionId != null) {
            documentRepository.addDocumentToCollection(generatedId, collectionId)
        }
        
        if (chapters.isNotEmpty()) {
            val dbChapters = chapters.map { candidate ->
                Chapter(
                    documentId = generatedId,
                    title = candidate.title,
                    startCharOffset = candidate.charOffset
                )
            }
            documentRepository.insertChapters(dbChapters)
        }

        // Pre-warm speech sentence cache on background thread for instant playback
        if (content.isNotBlank()) {
            val parsedSentences = withContext(Dispatchers.Default) {
                DocumentParser.parse(content)
            }
            putCachedSentences(createdDoc, parsedSentences)
        }
        
        if (autoSelect) {
            withContext(Dispatchers.Main) {
                selectDocument(createdDoc)
            }
        }

        createdDoc
    }

    fun addNewBook(
        title: String,
        content: String,
        sourceUrl: String?,
        coverPath: String? = null,
        chapters: List<ChapterCandidate> = emptyList(),
        autoSelect: Boolean = false,
        customCoverUri: android.net.Uri? = null,
        isFavorite: Boolean = false,
        collectionId: Long? = null
    ) {
        viewModelScope.launch {
            insertDocumentInternal(
                title = title,
                content = content,
                sourceUrl = sourceUrl,
                coverPath = coverPath,
                chapters = chapters,
                autoSelect = autoSelect,
                customCoverUri = customCoverUri,
                isFavorite = isFavorite,
                collectionId = collectionId
            )
        }
    }

    fun setLibraryOpen(open: Boolean) {
        _isLibraryOpen.value = open
    }

    fun toggleFavorite(document: Document) {
        viewModelScope.launch {
            val nextFav = !document.isFavorite
            documentRepository.updateFavoriteStatus(document.id, nextFav)
            if (_activeDocument.value?.id == document.id) {
                _activeDocument.value = _activeDocument.value?.copy(isFavorite = nextFav)
            }
        }
    }

    fun createCollection(name: String, andAddDocumentId: Long? = null, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val colId = documentRepository.insertCollection(CollectionEntity(name = name.trim()))
            if (andAddDocumentId != null) {
                documentRepository.addDocumentToCollection(andAddDocumentId, colId)
            }
            withContext(Dispatchers.Main) {
                onCreated?.invoke(colId)
            }
        }
    }

    fun addDocumentToCollection(documentId: Long, collectionId: Long) {
        viewModelScope.launch {
            documentRepository.addDocumentToCollection(documentId, collectionId)
        }
    }

    fun removeDocumentFromCollection(documentId: Long, collectionId: Long) {
        viewModelScope.launch {
            documentRepository.removeDocumentFromCollection(documentId, collectionId)
        }
    }

    fun deleteCollection(collection: CollectionEntity) {
        viewModelScope.launch {
            documentRepository.deleteCrossRefsForCollection(collection.id)
            documentRepository.deleteCollection(collection)
        }
    }

    fun renameCollection(collection: CollectionEntity, newName: String) {
        viewModelScope.launch {
            documentRepository.renameCollection(collection.id, newName)
        }
    }

    fun reorderCollections(collectionIds: List<Long>) {
        viewModelScope.launch {
            val sharedPrefs = getApplication<Application>().getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
            sharedPrefs.edit().putString("collection_order", collectionIds.joinToString(",")).apply()
            _collectionOrder.value = collectionIds
        }
    }

    // Deletes selected document and goes back to frontpage
    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            if (_activeDocument.value?.id == document.id) {
                deselectDocument()
            }
            documentRepository.deleteCrossRefsForDocument(document.id)
            documentRepository.delete(document)
        }
    }

    fun runHardwareBenchmark() {
        viewModelScope.launch {
            _benchmarkProgress.value = 0f
            _benchmarkResult.value = null

            // Over 3 seconds, simulate system benchmark passes
            for (i in 1..30) {
                delay(100)
                _benchmarkProgress.value = i / 30f
            }

            // Real System Specs Check
            val cores = Runtime.getRuntime().availableProcessors()
            
            // Query actual device physical RAM
            val actManager = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val physicalRamBytes = memInfo.totalMem
            val rawRamGb = if (physicalRamBytes > 0) physicalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0) else 4.0
            
            val estimatedRamGb = when {
                rawRamGb <= 2.5 -> 2.0
                rawRamGb <= 3.5 -> 3.0
                rawRamGb <= 4.5 -> 4.0
                rawRamGb <= 6.5 -> 6.0
                rawRamGb <= 8.5 -> 8.0
                rawRamGb <= 12.5 -> 12.0
                else -> 16.0
            }

            val npuDetected = Build.HARDWARE.lowercase().contains("qcom") || 
                              Build.HARDWARE.lowercase().contains("exynos") || 
                              Build.HARDWARE.lowercase().contains("mtk") ||
                              Build.HARDWARE.lowercase().contains("tensor")

            val recommended = when {
                cores < 4 || estimatedRamGb < 3.0 -> "ULTRA_LIGHT"
                cores < 8 || estimatedRamGb < 6.5 -> "BALANCED"
                else -> "HIGH_FIDELITY"
            }

            _benchmarkResult.value = BenchmarkResult(
                cores = cores,
                memoryGb = estimatedRamGb,
                npuDetected = npuDetected,
                recommendedTier = recommended
            )

            // Auto-apply recommended tier to active document
            _activeDocument.value?.let { _ ->
                setModelTier(recommended)
            }

            _benchmarkProgress.value = null
        }
    }

    fun resetBenchmark() {
        _benchmarkResult.value = null
        _benchmarkProgress.value = null
    }

    fun importDocumentFromUrl(
        url: String,
        customTitle: String? = null,
        customCoverUri: android.net.Uri? = null,
        isFavorite: Boolean = false,
        collectionId: Long? = null
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importProgress.value = ImportTaskProgress(
                isImporting = true,
                currentItemIndex = 1,
                totalItems = 1,
                currentTitle = customTitle ?: url,
                currentStage = "Connecting to web page...",
                progressFraction = 0.15f
            )
            try {
                withContext(Dispatchers.IO) {
                    val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        "https://$url"
                    } else url

                    val doc = Jsoup.connect(cleanUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(12000)
                        .get()

                    _importProgress.value = _importProgress.value.copy(
                        currentStage = "Extracting article text...",
                        progressFraction = 0.50f
                    )

                    val parsedTitle = if (!customTitle.isNullOrBlank()) customTitle else doc.title().ifBlank { cleanUrl }

                    // Strip scripts, headers, menus
                    doc.select("script, style, header, footer, nav, aside, noscript, iframe").remove()
                    
                    val mainContent = doc.select("article, main, .post-content, .mw-parser-output").firstOrNull()
                    val textToUse = if (mainContent != null) {
                        mainContent.select("p, h1, h2, h3, h4, h5, h6, li, blockquote").map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
                    } else {
                        doc.select("p, h1, h2, h3, h4, h5, h6, li, blockquote").map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
                    }

                    if (textToUse.isNotBlank()) {
                        _importProgress.value = _importProgress.value.copy(
                            currentStage = "Analyzing speech structure & chapters...",
                            progressFraction = 0.75f
                        )
                        val chapters = ChapterExtractor.extractChaptersFromText(textToUse)
                        val customCoverPath = saveCoverFromUri(customCoverUri)

                        _importProgress.value = _importProgress.value.copy(
                            currentStage = "Saving to library...",
                            progressFraction = 0.90f
                        )
                        insertDocumentInternal(
                            title = parsedTitle,
                            content = textToUse,
                            sourceUrl = cleanUrl,
                            coverPath = customCoverPath,
                            chapters = chapters,
                            autoSelect = false,
                            customCoverUri = null,
                            isFavorite = isFavorite,
                            collectionId = collectionId
                        )
                        _importProgress.value = _importProgress.value.copy(
                            currentStage = "Complete!",
                            progressFraction = 1.0f
                        )
                        delay(150)
                    } else {
                        throw IllegalArgumentException("No readable article content found at this URL.")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _importError.value = "Failed to import from URL: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isImporting.value = false
                _importProgress.value = ImportTaskProgress()
            }
        }
    }

    private suspend fun processDocumentFromUri(
        uri: android.net.Uri,
        customTitle: String?,
        customCoverUri: android.net.Uri? = null,
        isFavorite: Boolean = false,
        collectionId: Long? = null,
        onProgress: (stage: String, stageFraction: Float) -> Unit
    ): Document? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val contentResolver = context.contentResolver

        onProgress("Reading file...", 0.10f)

        var fileName = "Imported Document"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }

        val tempFile = File(context.cacheDir, "temp_upload_${System.currentTimeMillis()}_${(0..9999).random()}")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val rawTitle = if (!customTitle.isNullOrBlank()) customTitle else {
                fileName.substringBeforeLast(".")
            }
            val titleToUse = cleanBookTitle(rawTitle)

            var isPdf = fileName.endsWith(".pdf", ignoreCase = true) || 
                        (contentResolver.getType(uri) ?: "").contains("pdf", ignoreCase = true)
            
            var isDocx = fileName.endsWith(".docx", ignoreCase = true) || 
                         (contentResolver.getType(uri) ?: "").contains("vnd.openxmlformats-officedocument", ignoreCase = true)

            var isEpub = fileName.endsWith(".epub", ignoreCase = true) || 
                         (contentResolver.getType(uri) ?: "").contains("epub", ignoreCase = true)

            var isHtml = fileName.endsWith(".html", ignoreCase = true) || 
                         fileName.endsWith(".htm", ignoreCase = true) || 
                         fileName.endsWith(".xhtml", ignoreCase = true) || 
                         (contentResolver.getType(uri) ?: "").contains("html", ignoreCase = true)

            // ZIP-based structure sniffing fallback for ambiguous file naming or generic MIME types
            if (!isPdf && !isDocx && !isEpub && !isHtml) {
                try {
                    java.util.zip.ZipFile(tempFile).use { zip ->
                        if (zip.getEntry("word/document.xml") != null) {
                            isDocx = true
                        } else if (zip.getEntry("META-INF/container.xml") != null || 
                                   zip.entries().asSequence().any { it.name.endsWith(".epub", ignoreCase = true) }) {
                            isEpub = true
                        }
                    }
                } catch (_: Exception) {}
            }

            onProgress("Extracting text and structure...", 0.35f)

            val extracted: ExtractedDocument
            var coverPath: String? = null

            if (isPdf) {
                extracted = extractTextFromPdf(tempFile)
                coverPath = generatePdfCover(tempFile)
            } else if (isDocx) {
                extracted = extractTextFromDocx(tempFile)
            } else if (isEpub) {
                extracted = extractTextFromEpub(tempFile)
                coverPath = extractEpubCover(tempFile)
            } else if (isHtml) {
                extracted = extractTextFromHtml(tempFile)
            } else {
                extracted = extractTextFromTxt(tempFile)
            }

            if (extracted.content.isBlank()) {
                throw IllegalArgumentException("No readable text found in $fileName")
            }

            onProgress("Analyzing speech structure & chapters...", 0.65f)

            val customCoverPath = saveCoverFromUri(customCoverUri)
            val finalCoverPath = customCoverPath ?: coverPath

            onProgress("Saving to library...", 0.85f)

            val createdDoc = insertDocumentInternal(
                title = titleToUse,
                content = extracted.content,
                sourceUrl = fileName,
                coverPath = finalCoverPath,
                chapters = extracted.chapters,
                autoSelect = false,
                customCoverUri = null,
                isFavorite = isFavorite,
                collectionId = collectionId
            )

            onProgress("Complete!", 1.00f)
            createdDoc
        } finally {
            tempFile.delete()
        }
    }

    fun importDocumentFromUri(
        uri: android.net.Uri,
        customTitle: String?,
        autoSelect: Boolean = false,
        customCoverUri: android.net.Uri? = null,
        isFavorite: Boolean = false,
        collectionId: Long? = null
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importProgress.value = ImportTaskProgress(
                isImporting = true,
                currentItemIndex = 1,
                totalItems = 1,
                currentTitle = customTitle ?: "Document",
                currentStage = "Reading file...",
                progressFraction = 0.05f
            )
            try {
                val doc = processDocumentFromUri(
                    uri = uri,
                    customTitle = customTitle,
                    customCoverUri = customCoverUri,
                    isFavorite = isFavorite,
                    collectionId = collectionId,
                    onProgress = { stage, frac ->
                        _importProgress.value = _importProgress.value.copy(
                            currentStage = stage,
                            progressFraction = frac
                        )
                    }
                )
                delay(150)
                if (autoSelect && doc != null) {
                    selectDocument(doc)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _importError.value = "Failed to import file: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isImporting.value = false
                _importProgress.value = ImportTaskProgress()
            }
        }
    }

    fun importDocumentsBatch(drafts: List<BatchImportItem>) {
        if (drafts.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            val total = drafts.size
            _importProgress.value = ImportTaskProgress(
                isImporting = true,
                currentItemIndex = 1,
                totalItems = total,
                currentTitle = drafts.first().title ?: "Document 1",
                currentStage = "Starting batch import...",
                progressFraction = 0f
            )
            try {
                for ((index, draft) in drafts.withIndex()) {
                    val itemNumber = index + 1
                    val itemTitle = draft.title ?: "Document $itemNumber"
                    _importProgress.value = ImportTaskProgress(
                        isImporting = true,
                        currentItemIndex = itemNumber,
                        totalItems = total,
                        currentTitle = itemTitle,
                        currentStage = "Reading file...",
                        progressFraction = index.toFloat() / total
                    )
                    try {
                        processDocumentFromUri(
                            uri = draft.uri,
                            customTitle = draft.title,
                            customCoverUri = draft.customCoverUri,
                            isFavorite = draft.isFavorite,
                            collectionId = draft.collectionId,
                            onProgress = { stage, stageFrac ->
                                val overallFrac = (index + stageFrac) / total
                                _importProgress.value = _importProgress.value.copy(
                                    currentStage = stage,
                                    progressFraction = overallFrac
                                )
                            }
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        _importError.value = "Failed to import '$itemTitle': ${e.localizedMessage ?: "Unknown error"}"
                    }
                }
                delay(200)
            } finally {
                _isImporting.value = false
                _importProgress.value = ImportTaskProgress()
            }
        }
    }

    private fun cleanTextParagraphs(rawText: String): String {
        if (rawText.isBlank()) return ""
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\\n\\s*\\n+"))
        val cleanedBlocks = mutableListOf<String>()

        for (block in blocks) {
            val cleanBlock = block.trim()
            if (cleanBlock.isEmpty()) continue

            val lines = cleanBlock.split("\n")
            val builder = StringBuilder()

            for (i in lines.indices) {
                val currentLine = lines[i].trim()
                if (currentLine.isEmpty()) continue

                if (builder.isNotEmpty()) {
                    val lastChar = builder.lastOrNull()
                    val isHyphenated = lastChar == '-' &&
                            builder.length >= 2 &&
                            builder[builder.length - 2].isLetterOrDigit() &&
                            currentLine.firstOrNull()?.isLetterOrDigit() == true

                    if (isHyphenated) {
                        builder.setLength(builder.length - 1)
                        builder.append(currentLine)
                    } else {
                        val isBulletList = currentLine.startsWith("-") ||
                                           currentLine.startsWith("*") ||
                                           currentLine.startsWith("•") ||
                                           currentLine.matches(BULLET_LIST_REGEX)
                        if (isBulletList) {
                            builder.append("\n").append(currentLine)
                        } else {
                            builder.append(" ").append(currentLine)
                        }
                    }
                } else {
                    builder.append(currentLine)
                }
            }
            cleanedBlocks.add(builder.toString().trim())
        }
        return cleanedBlocks.joinToString("\n\n")
    }

    private suspend fun extractTextFromPdf(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        var reader: PdfReader? = null
        try {
            val stream = file.inputStream()
            reader = PdfReader(stream)
            val numberOfPages = reader.numberOfPages
            val textBuilder = StringBuilder()
            val pageStartOffsets = mutableListOf<Int>()
            pageStartOffsets.add(0) // page 0 placeholder
            
            for (i in 1..numberOfPages) {
                val pageText = PdfTextExtractor.getTextFromPage(reader, i)
                val cleanedPageText = if (pageText != null) cleanTextParagraphs(pageText) else ""
                
                pageStartOffsets.add(textBuilder.length)
                
                if (cleanedPageText.isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n\n")
                    }
                    textBuilder.append(cleanedPageText)
                }
            }
            val content = textBuilder.toString()
            val chapters = ChapterExtractor.extractChaptersFromPdf(file, content, pageStartOffsets)
            ExtractedDocument(content, chapters)
        } catch (e: Exception) {
            e.printStackTrace()
            ExtractedDocument("", emptyList())
        } finally {
            reader?.close()
        }
    }

    private suspend fun extractTextFromDocx(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        try {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return@withContext ExtractedDocument("", emptyList())
                zip.getInputStream(entry).use { stream ->
                    val xml = stream.bufferedReader(Charsets.UTF_8).readText()
                    val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                    val result = StringBuilder()
                    
                    val paragraphs = doc.select("*|p")
                    for (p in paragraphs) {
                        val pBuilder = StringBuilder()
                        val texts = p.select("*|t")
                        for (t in texts) {
                            pBuilder.append(t.text())
                        }
                        val pText = pBuilder.toString().trim()
                        if (pText.isNotEmpty()) {
                            result.append(pText).append("\n\n")
                        }
                    }
                    val content = cleanTextParagraphs(result.toString())
                    val chapters = ChapterExtractor.extractChaptersFromText(content)
                    ExtractedDocument(content, chapters)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ExtractedDocument("", emptyList())
        }
    }

    private suspend fun extractTextFromEpub(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        try {
            val result = StringBuilder()
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                
                // Try to parse the OPF spine sequence for the correct reading order
                val opfEntry = entries.firstOrNull { it.name.lowercase().endsWith(".opf") }
                val resolvedEntries = mutableListOf<java.util.zip.ZipEntry>()
                
                if (opfEntry != null) {
                    try {
                        val opfContent = zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).readText()
                        val opfDir = opfEntry.name.substringBeforeLast("/", "")
                        val doc = org.jsoup.Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())
                        
                        val manifestMap = mutableMapOf<String, String>()
                        val manifestItems = doc.select("manifest > item")
                        for (item in manifestItems) {
                            val id = item.attr("id")
                            val href = item.attr("href")
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifestMap[id] = href
                            }
                        }
                        
                        val spineItems = doc.select("spine > itemref")
                        for (itemref in spineItems) {
                            val idref = itemref.attr("idref")
                            val href = manifestMap[idref] ?: continue
                            val cleanHref = href.substringBefore("#")
                            val fullPath = if (opfDir.isEmpty()) cleanHref else "$opfDir/$cleanHref"
                            
                            var entry = zip.getEntry(fullPath)
                            if (entry == null) {
                                try {
                                    val decodedPath = java.net.URLDecoder.decode(fullPath, "UTF-8")
                                    entry = zip.getEntry(decodedPath)
                                } catch (_: Exception) {}
                            }
                            
                            if (entry != null && !entry.isDirectory) {
                                resolvedEntries.add(entry)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ReadoutViewModel", "Failed to parse EPUB spine reading order, falling back to alphabetical", e)
                    }
                }
                
                // Fallback to natural alphabetical sorting if OPF spine parsing yields no files
                var textEntries = resolvedEntries
                if (textEntries.isEmpty()) {
                    textEntries = entries.filter { entry ->
                        val name = entry.name.lowercase()
                        !entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))
                    }.sortedWith(Comparator { a, b ->
                        naturalCompare(a.name.lowercase(), b.name.lowercase())
                    }).toMutableList()
                }

                if (textEntries.isEmpty()) return@withContext ExtractedDocument("", emptyList())

                for (entry in textEntries) {
                    zip.getInputStream(entry).use { stream ->
                        val htmlContent = stream.bufferedReader(Charsets.UTF_8).readText()
                        val cleanText = extractParagraphsFromHtml(htmlContent)
                        if (cleanText.isNotBlank()) {
                            result.append(cleanText).append("\n\n")
                        }
                    }
                }
            }
            val content = cleanTextParagraphs(result.toString())
            val chapters = ChapterExtractor.extractChaptersFromEpub(file, content)
            ExtractedDocument(content, chapters)
        } catch (e: Exception) {
            e.printStackTrace()
            ExtractedDocument("", emptyList())
        }
    }

    private suspend fun extractEpubCover(file: File): String? = withContext(Dispatchers.IO) {
        try {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries().toList()

                // Strategy 1: Parse OPF manifest
                val opfEntry = entries.firstOrNull { it.name.lowercase().endsWith(".opf") }
                if (opfEntry != null) {
                    val opfContent = zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).readText()
                    val opfDir = opfEntry.name.substringBeforeLast("/", "")

                    val coverHref =
                        Regex("""<item[^>]+id=["']cover[^"*]["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(opfContent)?.groupValues?.getOrNull(1)
                        ?: Regex("""<item[^>]+properties=["'][^']*cover-image[^'*]["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(opfContent)?.groupValues?.getOrNull(1)
                        ?: Regex("""<item[^>]+href=["']([^"']+)["'][^>]+properties=["'][^']*cover-image[^'*]["']""", RegexOption.IGNORE_CASE)
                            .find(opfContent)?.groupValues?.getOrNull(1)

                    if (coverHref != null) {
                        val candidatePath = if (opfDir.isEmpty()) coverHref else "$opfDir/$coverHref"
                        val coverEntry = zip.getEntry(candidatePath)
                            ?: entries.firstOrNull { it.name.endsWith(coverHref, ignoreCase = true) }
                        if (coverEntry != null) {
                            val ext = coverHref.substringAfterLast(".").lowercase()
                            if (ext in listOf("jpg", "jpeg", "png", "gif", "webp")) {
                                val out = File(getApplication<Application>().filesDir, "cover_${System.currentTimeMillis()}.$ext")
                                zip.getInputStream(coverEntry).use { inp -> FileOutputStream(out).use { inp.copyTo(it) } }
                                return@withContext out.absolutePath
                            }
                        }
                    }
                }

                // Strategy 2: Well-known cover filenames
                val knownNames = listOf(
                    "cover.jpg", "cover.jpeg", "cover.png",
                    "images/cover.jpg", "images/cover.jpeg", "images/cover.png",
                    "OEBPS/cover.jpg", "OEBPS/images/cover.jpg",
                    "OEBPS/cover.jpeg", "OEBPS/images/cover.jpeg"
                )
                for (name in knownNames) {
                    val entry = entries.firstOrNull {
                        it.name.equals(name, ignoreCase = true) ||
                        it.name.lowercase().endsWith("/$name")
                    }
                    if (entry != null) {
                        val ext = entry.name.substringAfterLast(".").lowercase()
                        val out = File(getApplication<Application>().filesDir, "cover_${System.currentTimeMillis()}.$ext")
                        zip.getInputStream(entry).use { inp -> FileOutputStream(out).use { inp.copyTo(it) } }
                        return@withContext out.absolutePath
                    }
                }

                // Strategy 3: Any image with "cover" in the name
                val byName = entries.firstOrNull { entry ->
                    !entry.isDirectory &&
                    entry.name.lowercase().contains("cover") &&
                    entry.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
                }
                if (byName != null) {
                    val ext = byName.name.substringAfterLast(".").lowercase()
                    val out = File(getApplication<Application>().filesDir, "cover_${System.currentTimeMillis()}.$ext")
                    zip.getInputStream(byName).use { inp -> FileOutputStream(out).use { inp.copyTo(it) } }
                    return@withContext out.absolutePath
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractTextFromHtml(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        val content = try {
            val rawHtml = file.readText(Charsets.UTF_8)
            extractParagraphsFromHtml(rawHtml)
        } catch (e: Exception) {
            try {
                val rawHtml = file.readText(Charsets.ISO_8859_1)
                extractParagraphsFromHtml(rawHtml)
            } catch (ex: Exception) {
                ""
            }
        }
        val chapters = ChapterExtractor.extractChaptersFromText(content)
        ExtractedDocument(content, chapters)
    }

    private fun extractParagraphsFromHtml(html: String): String {
        try {
            val doc = Jsoup.parse(html)
            doc.select("script, style, head, header, footer, nav, iframe, noscript").remove()
            
            val result = StringBuilder()
            val blocks = doc.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre")
            if (blocks.isNotEmpty()) {
                for (block in blocks) {
                    val pText = block.text().trim()
                    if (pText.isNotEmpty()) {
                        result.append(pText).append("\n\n")
                    }
                }
            } else {
                val text = doc.body()?.text() ?: doc.text()
                result.append(text)
            }
            return result.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    private suspend fun generatePdfCover(file: File): String? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val width = 400
                val height = (page.height.toFloat() / page.width.toFloat() * width).toInt().coerceIn(300, 800)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val coverFile = File(getApplication<Application>().filesDir, "cover_${System.currentTimeMillis()}.png")
                FileOutputStream(coverFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                coverFile.absolutePath
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    private suspend fun extractTextFromTxt(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                file.readText(Charsets.ISO_8859_1)
            } catch (ex: Exception) {
                ""
            }
        }
        val chapters = ChapterExtractor.extractChaptersFromText(content)
        ExtractedDocument(content, chapters)
    }

    fun updateBookDetails(documentId: Long, newTitle: String, newCoverUri: android.net.Uri?, removeCover: Boolean) {
        viewModelScope.launch {
            val doc = documentRepository.getDocumentById(documentId)
            if (doc != null) {
                var coverPath = if (removeCover) null else doc.coverPath
                
                if (newCoverUri != null && !removeCover) {
                    val context = getApplication<Application>()
                    val contentResolver = context.contentResolver
                    val tempFile = File(context.filesDir, "cover_${System.currentTimeMillis()}.png")
                    try {
                        contentResolver.openInputStream(newCoverUri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        coverPath = tempFile.absolutePath
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val updatedDoc = doc.copy(title = newTitle, coverPath = coverPath)
                documentRepository.update(updatedDoc)
                
                if (_activeDocument.value?.id == documentId) {
                    _activeDocument.value = updatedDoc
                }
            }
        }
    }

    private fun cleanBookTitle(rawTitle: String): String {
        val spaced = rawTitle.replace(Regex("[_\\-]+"), " ")
        return spaced.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            }
    }

    suspend fun exportBackupData(): String = withContext(Dispatchers.IO) {
        val rootJson = JSONObject()
        
        // 1. Export Documents
        val documentsArray = JSONArray()
        val docsList = allDocuments.value
        val bookmarksList = allBookmarks.value
        
        for (metaDoc in docsList) {
            val fullDoc = documentRepository.getDocumentById(metaDoc.id) ?: continue
            val docJson = JSONObject().apply {
                put("title", fullDoc.title)
                put("content", fullDoc.content)
                put("sourceUrl", fullDoc.sourceUrl)
                put("addedDate", fullDoc.addedDate)
                put("playbackPosition", fullDoc.playbackPosition)
                put("selectedModelTier", fullDoc.selectedModelTier)
                put("playbackSpeed", fullDoc.playbackSpeed)
                put("coverPath", fullDoc.coverPath)
                put("lastReadTime", fullDoc.lastReadTime)
                put("isFavorite", fullDoc.isFavorite)
                put("contentLength", fullDoc.contentLength)
            }
            
            // Nested Bookmarks
            val docBookmarks = bookmarksList.filter { it.documentId == fullDoc.id }
            val bookmarksArray = JSONArray()
            for (bm in docBookmarks) {
                bookmarksArray.put(JSONObject().apply {
                    put("sentenceIndex", bm.sentenceIndex)
                    put("charOffset", bm.charOffset)
                    put("label", bm.label)
                    put("createdAt", bm.createdAt)
                })
            }
            docJson.put("bookmarks", bookmarksArray)
            
            // Nested Chapters
            val chaptersList = documentRepository.getChaptersForDocument(fullDoc.id)
            val chaptersArray = JSONArray()
            for (ch in chaptersList) {
                chaptersArray.put(JSONObject().apply {
                    put("title", ch.title)
                    put("startCharOffset", ch.startCharOffset)
                    put("startSentenceIndex", ch.startSentenceIndex)
                })
            }
            docJson.put("chapters", chaptersArray)
            
            documentsArray.put(docJson)
        }
        rootJson.put("documents", documentsArray)
        
        // 2. Export Collections
        val collectionsArray = JSONArray()
        val collectionsList = allCollections.value
        val crossRefsList = allCrossRefs.value
        
        for (col in collectionsList) {
            val colJson = JSONObject().apply {
                put("name", col.name)
                put("addedDate", col.addedDate)
            }
            
            val documentTitlesArray = JSONArray()
            val docIdsInCol = crossRefsList.filter { it.collectionId == col.id }.map { it.documentId }
            for (docId in docIdsInCol) {
                val docTitle = docsList.firstOrNull { it.id == docId }?.title
                if (docTitle != null) {
                    documentTitlesArray.put(docTitle)
                }
            }
            colJson.put("documentTitles", documentTitlesArray)
            collectionsArray.put(colJson)
        }
        rootJson.put("collections", collectionsArray)
        
        rootJson.toString(2)
    }

    suspend fun importBackupData(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootJson = JSONObject(jsonString)
            
            val documentsArray = rootJson.optJSONArray("documents") ?: JSONArray()
            val titleToNewIdMap = mutableMapOf<String, Long>()
            
            for (i in 0 until documentsArray.length()) {
                val docJson = documentsArray.getJSONObject(i)
                val title = docJson.getString("title")
                val content = docJson.getString("content")
                val sourceUrl = if (docJson.has("sourceUrl") && !docJson.isNull("sourceUrl")) docJson.getString("sourceUrl") else null
                val addedDate = docJson.optLong("addedDate", System.currentTimeMillis())
                val playbackPosition = docJson.optInt("playbackPosition", 0)
                val selectedModelTier = docJson.optString("selectedModelTier", "HIGH_FIDELITY")
                val playbackSpeed = docJson.optDouble("playbackSpeed", 1.0).toFloat()
                val coverPath = if (docJson.has("coverPath") && !docJson.isNull("coverPath")) docJson.getString("coverPath") else null
                val lastReadTime = docJson.optLong("lastReadTime", System.currentTimeMillis())
                val isFavorite = docJson.optBoolean("isFavorite", false)
                val contentLength = docJson.optInt("contentLength", content.length)
                
                val doc = Document(
                    title = title,
                    content = content,
                    sourceUrl = sourceUrl,
                    addedDate = addedDate,
                    playbackPosition = playbackPosition,
                    selectedModelTier = selectedModelTier,
                    playbackSpeed = playbackSpeed,
                    coverPath = coverPath,
                    lastReadTime = lastReadTime,
                    isFavorite = isFavorite,
                    contentLength = contentLength
                )
                
                val newDocId = documentRepository.insert(doc)
                titleToNewIdMap[title] = newDocId
                
                // Nest Bookmarks
                val bookmarksArray = docJson.optJSONArray("bookmarks") ?: JSONArray()
                for (j in 0 until bookmarksArray.length()) {
                    val bmJson = bookmarksArray.getJSONObject(j)
                    val bookmark = Bookmark(
                        documentId = newDocId,
                        sentenceIndex = bmJson.getInt("sentenceIndex"),
                        charOffset = bmJson.getInt("charOffset"),
                        label = bmJson.getString("label"),
                        createdAt = bmJson.optLong("createdAt", System.currentTimeMillis())
                    )
                    documentRepository.insertBookmark(bookmark)
                }
                
                // Nest Chapters
                val chaptersArray = docJson.optJSONArray("chapters") ?: JSONArray()
                val chaptersList = mutableListOf<Chapter>()
                for (j in 0 until chaptersArray.length()) {
                    val chJson = chaptersArray.getJSONObject(j)
                    chaptersList.add(
                        Chapter(
                            documentId = newDocId,
                            title = chJson.getString("title"),
                            startCharOffset = chJson.getInt("startCharOffset"),
                            startSentenceIndex = chJson.optInt("startSentenceIndex", 0)
                        )
                    )
                }
                if (chaptersList.isNotEmpty()) {
                    documentRepository.insertChapters(chaptersList)
                }
            }
            
            // Collections
            val collectionsArray = rootJson.optJSONArray("collections") ?: JSONArray()
            for (i in 0 until collectionsArray.length()) {
                val colJson = collectionsArray.getJSONObject(i)
                val name = colJson.getString("name")
                val addedDate = colJson.optLong("addedDate", System.currentTimeMillis())
                
                val col = CollectionEntity(name = name, addedDate = addedDate)
                val newColId = documentRepository.insertCollection(col)
                
                val documentTitlesArray = colJson.optJSONArray("documentTitles") ?: JSONArray()
                for (j in 0 until documentTitlesArray.length()) {
                    val docTitle = documentTitlesArray.getString(j)
                    val newDocId = titleToNewIdMap[docTitle]
                    if (newDocId != null) {
                        documentRepository.addDocumentToCollection(newDocId, newColId)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("ReadoutViewModel", "Failed to import backup data", e)
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        documentSelectionJob?.cancel()
        bookmarkCollectionJob?.cancel()
        ttsEngine.shutdown()
    }

    private suspend fun getOrParseSentences(document: Document): List<SpeechSentence> {
        getCachedSentences(document)?.let { return it }

        val parsedSentences = withContext(Dispatchers.Default) {
            DocumentParser.parse(document.content)
        }
        putCachedSentences(document, parsedSentences)
        return parsedSentences
    }

    private fun getCachedSentences(document: Document): List<SpeechSentence>? {
        val contentHash = document.content.hashCode()
        return synchronized(sentenceCache) {
            sentenceCache[document.id]?.takeIf { it.contentHash == contentHash }?.sentences
        }
    }

    private fun putCachedSentences(document: Document, sentences: List<SpeechSentence>) {
        val contentHash = document.content.hashCode()
        synchronized(sentenceCache) {
            sentenceCache[document.id] = CachedSentences(contentHash, sentences)
        }
    }

    private suspend fun warmSentenceCache(documents: List<Document>) {
        documents.forEach { summaryDoc ->
            val fullDoc = try {
                documentRepository.getDocumentById(summaryDoc.id)
            } catch (e: Exception) {
                null
            } ?: return@forEach
            if (getCachedSentences(fullDoc) == null && fullDoc.content.isNotBlank()) {
                val parsed = withContext(Dispatchers.Default) {
                    DocumentParser.parse(fullDoc.content)
                }
                putCachedSentences(fullDoc, parsed)
            }
        }
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val numRegex = Regex("\\d+")
        val p1 = numRegex.replace(s1) { it.value.padStart(10, '0') }
        val p2 = numRegex.replace(s2) { it.value.padStart(10, '0') }
        return p1.compareTo(p2)
    }
}
