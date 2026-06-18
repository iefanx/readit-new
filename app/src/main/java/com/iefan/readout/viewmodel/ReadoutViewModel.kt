package com.iefan.readout.viewmodel

import android.app.Application
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iefan.readout.data.*
import com.iefan.readout.tts.AuraTtsEngine
import com.iefan.readout.tts.DocumentParser
import com.iefan.readout.tts.SpeechSentence
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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

class ReadoutViewModel(application: Application) : AndroidViewModel(application) {

    private val documentRepository: DocumentRepository
    private val ttsEngine: AuraTtsEngine

    // All books / articles in library
    val allDocuments: StateFlow<List<Document>>

    private val _isLibraryOpen = MutableStateFlow(false)
    val isLibraryOpen = _isLibraryOpen.asStateFlow()

    val allCollections: StateFlow<List<CollectionEntity>>
    val allCrossRefs: StateFlow<List<DocumentCollectionCrossRef>>

    // Currently playing/viewed document
    private val _activeDocument = MutableStateFlow<Document?>(null)
    val activeDocument = _activeDocument.asStateFlow()

    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded = _isPlayerExpanded.asStateFlow()

    // Import visual loading state
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

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

    // State bindings straight from TTS Engine
    val isPlaying: StateFlow<Boolean>
    val currentSentenceIndex: StateFlow<Int>
    val currentWordRange: StateFlow<Pair<Int, Int>?>
    val playbackSpeed: StateFlow<Float>
    val selectedModelTier: StateFlow<String>
    val sleepTimerMinutes: StateFlow<Int>
    val sleepTimerRemainingSeconds: StateFlow<Int>

    // Hardware Benchmark flow
    private val _benchmarkProgress = MutableStateFlow<Float?>(null)
    val benchmarkProgress = _benchmarkProgress.asStateFlow()

    private val _benchmarkResult = MutableStateFlow<BenchmarkResult?>(null)
    val benchmarkResult = _benchmarkResult.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        documentRepository = DocumentRepository(database.documentDao())
        ttsEngine = AuraTtsEngine(application)

        val sharedPrefs = application.getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE)
        val savedTier = sharedPrefs.getString("selected_voice_tier", "HIGH_FIDELITY") ?: "HIGH_FIDELITY"
        ttsEngine.setModelTier(savedTier)

        allDocuments = documentRepository.allDocuments
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        allCollections = documentRepository.allCollections
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

        // Bind flows from Engine to ViewModel State Flows
        isPlaying = ttsEngine.isPlaying
        currentSentenceIndex = ttsEngine.currentSentenceIndex
        currentWordRange = ttsEngine.currentWordRange
        playbackSpeed = ttsEngine.playbackSpeed
        selectedModelTier = ttsEngine.selectedModelTier
        sleepTimerMinutes = ttsEngine.sleepTimerMinutes
        sleepTimerRemainingSeconds = ttsEngine.sleepTimerRemainingSeconds

        // Listen for sentence changes to update Room position with a debounce of 3 seconds
        viewModelScope.launch {
            currentSentenceIndex.collectLatest { index ->
                activeDocument.value?.let { doc ->
                    val sentencesList = _activeSentences.value
                    if (sentencesList.isNotEmpty() && index < sentencesList.size) {
                        val currentSentence = sentencesList[index]
                        delay(3000)
                        documentRepository.updatePlaybackPosition(doc.id, currentSentence.start)
                    }
                }
            }
        }

        // Trigger preloads if db is empty and we haven't preloaded before
        viewModelScope.launch {
            allDocuments.collect { list ->
                val hasPreloaded = sharedPrefs.getBoolean("has_preloaded_samples", false)
                if (list.isEmpty() && !hasPreloaded) {
                    preloadSampleBooks()
                    sharedPrefs.edit().putBoolean("has_preloaded_samples", true).apply()
                }
            }
        }
    }

    private suspend fun preloadSampleBooks() {
        val samples = listOf(
            Document(
                title = "The Art of Focus",
                content = """In an age of constant notification ping-backs and infinite scrolling feeds, deep focus has become the ultimate cognitive commodity. True intellectual output is born from selective ignorance—the ability to shut down background cycles and direct your entire working memory onto a single problem channel. 

As a local-first system, this reading session contains zero remote trackers, zero cloud subscription walls, and complete offline privacy. 

This is Readout. To begin your focused auditory journey, try swiping up on the bottom active player bar to view real-time karaoke sentence tracking, or double tap on any sentence on that view to immediately skip your speech engine to that exact location. Playback speeds can be scaled fluidly from 0.5x up to 4.5x with fully compensated time-pitch dynamics.""",
                sourceUrl = "Readout Sample Archive",
                selectedModelTier = "HIGH_FIDELITY",
                playbackSpeed = 1.0f
            ),
            Document(
                title = "A Brief History of Speed Audio",
                content = """Slowing down or speeding up audio has been a tool for learning since magnetic tape recorders allowed mechanical pitch adjustments. However, traditional speed alterations resulted in Chipmunk tones because the frequency domain shifted as the time domain compressed. 

With modern local-first DSP algorithms, digital time-stretching decouples speed from frequency entirely. Readout uses hardware-efficient offline processing models (recommending Ultra-Light, Balanced, or High-Fidelity tiers depending on core configurations) to preserve rich natural voice pitch, even when playback speed is driven up to a blistering 4.5 times normal listening speed.""",
                sourceUrl = "DSP Learning Chronicles",
                selectedModelTier = "HIGH_FIDELITY",
                playbackSpeed = 1.2f
            )
        )
        for (sample in samples) {
            documentRepository.insert(sample)
        }
    }

    fun selectDocument(document: Document) {
        if (_activeDocument.value?.id == document.id) {
            _isPlayerExpanded.value = true
            return
        }

        ttsEngine.stop()

        val updatedDoc = document.copy(lastReadTime = System.currentTimeMillis())
        _activeDocument.value = updatedDoc
        viewModelScope.launch {
            documentRepository.update(updatedDoc)
            
            val chapters = documentRepository.getChaptersForDocument(updatedDoc.id)
            _activeChapters.value = chapters

            val parsedSentences = withContext(Dispatchers.Default) {
                DocumentParser.parse(updatedDoc.content)
            }
            _activeSentences.value = parsedSentences

            // Find best starting sentence index based on saved playback position
            var savedSentenceIdx = 0
            for ((idx, sent) in parsedSentences.withIndex()) {
                if (updatedDoc.playbackPosition in sent.start..sent.end) {
                    savedSentenceIdx = idx
                    break
                }
            }

            ttsEngine.loadDocument(parsedSentences, updatedDoc.title, savedSentenceIdx)
            ttsEngine.setSpeed(updatedDoc.playbackSpeed)
            _isPlayerExpanded.value = true
        }
    }

    fun seekToChapter(chapter: Chapter) {
        val sentencesList = _activeSentences.value
        if (sentencesList.isNotEmpty()) {
            var bestIdx = 0
            var minDiff = Int.MAX_VALUE
            for ((idx, sent) in sentencesList.withIndex()) {
                val diff = Math.abs(sent.start - chapter.startCharOffset)
                if (diff < minDiff) {
                    minDiff = diff
                    bestIdx = idx
                }
                if (sent.start >= chapter.startCharOffset) {
                    bestIdx = idx
                    break
                }
            }
            seekToSentence(bestIdx)
        }
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
        saveCurrentPlaybackPosition()
        ttsEngine.stop()
        _activeDocument.value = null
        _activeSentences.value = emptyList()
        _isPlayerExpanded.value = false
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
            ttsEngine.startPlayback()
        }
    }

    fun seekToSentence(index: Int) {
        ttsEngine.seekToSentence(index)
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

    fun startSleepTimer(minutes: Int) {
        ttsEngine.startSleepTimer(minutes)
    }

    fun addNewBook(title: String, content: String, sourceUrl: String?, coverPath: String? = null, chapters: List<ChapterCandidate> = emptyList()) {
        viewModelScope.launch {
            val doc = Document(
                title = title.ifBlank { "Untitled Document" },
                content = content,
                sourceUrl = sourceUrl?.ifBlank { null },
                coverPath = coverPath
            )
            val generatedId = documentRepository.insert(doc)
            val createdDoc = doc.copy(id = generatedId)
            
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
            
            selectDocument(createdDoc)
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

    fun createCollection(name: String, andAddDocumentId: Long? = null) {
        viewModelScope.launch {
            val colId = documentRepository.insertCollection(CollectionEntity(name = name))
            if (andAddDocumentId != null) {
                documentRepository.addDocumentToCollection(andAddDocumentId, colId)
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
            // Estimate RAM size in GigaBytes
            val maxMemory = Runtime.getRuntime().maxMemory()
            val availableMemoryGb = maxMemory / (1024.0 * 1024.0 * 1024.0)
            
            // Adjust to get realistic total RAM values for Android devices
            val estimatedRamGb = when {
                availableMemoryGb < 1.0 -> 2.0
                availableMemoryGb < 2.0 -> 4.0
                availableMemoryGb < 3.5 -> 6.0
                else -> 8.0
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

    fun importDocumentFromUrl(url: String, customTitle: String?) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                withContext(Dispatchers.IO) {
                    val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        "https://$url"
                    } else url

                    val doc = Jsoup.connect(cleanUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(12000)
                        .get()

                    val parsedTitle = if (!customTitle.isNullOrBlank()) customTitle else doc.title()

                    // Strip scripts, headers, menus
                    doc.select("script, style, header, footer, nav, aside, noscript, iframe").remove()
                    
                    // Filter down to body article chunks or just text
                    val articleElements = doc.select("article, main, .post-content, .mw-parser-output, p")
                    val rawTxt = if (articleElements.isNotEmpty()) {
                        articleElements.map { it.text() }.filter { it.length > 50 }.joinToString("\n\n")
                    } else ""
                    
                    val textToUse = if (rawTxt.isNotBlank()) rawTxt else doc.body().text()

                    if (textToUse.isNotBlank()) {
                        val chapters = ChapterExtractor.extractChaptersFromText(textToUse)
                        withContext(Dispatchers.Main) {
                            addNewBook(parsedTitle, textToUse, cleanUrl, null, chapters)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _importError.value = "Failed to import from URL: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importDocumentFromUri(uri: android.net.Uri, customTitle: String?) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val contentResolver = context.contentResolver
                    
                    var fileName = "Imported Document"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }

                    val tempFile = File(context.cacheDir, "temp_upload_${System.currentTimeMillis()}")
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        val titleToUse = if (!customTitle.isNullOrBlank()) customTitle else {
                            fileName.substringBeforeLast(".")
                        }

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
                            } catch (e: Exception) {
                                // Not a valid ZIP file, fallback to plain text
                            }
                        }

                        val extracted: ExtractedDocument
                        var coverPath: String? = null

                        if (isPdf) {
                            extracted = extractTextFromPdf(tempFile)
                            coverPath = generatePdfCover(tempFile)
                        } else if (isDocx) {
                            extracted = extractTextFromDocx(tempFile)
                        } else if (isEpub) {
                            extracted = extractTextFromEpub(tempFile)
                        } else if (isHtml) {
                            extracted = extractTextFromHtml(tempFile)
                        } else {
                            extracted = extractTextFromTxt(tempFile)
                        }

                        if (extracted.content.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                addNewBook(titleToUse, extracted.content, fileName, coverPath, extracted.chapters)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        _importError.value = "Failed to process imported file: ${e.localizedMessage ?: "Unknown error"}"
                    } finally {
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _importError.value = "Failed to read imported file: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isImporting.value = false
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
                    if (lastChar == '-') {
                        builder.setLength(builder.length - 1)
                        builder.append(currentLine)
                    } else {
                        val isBulletList = currentLine.startsWith("-") || 
                                           currentLine.startsWith("*") || 
                                           currentLine.startsWith("•") || 
                                           currentLine.matches(Regex("^\\d+\\.\\s+.*"))
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
                val textEntries = entries.filter { entry ->
                    val name = entry.name.lowercase()
                    !entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))
                }.sortedBy { it.name }

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

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
    }
}
