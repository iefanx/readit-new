package com.iefan.readout

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iefan.readout.ui.components.SettingsDialog
import com.iefan.readout.ui.screens.*
import com.iefan.readout.ui.theme.MyApplicationTheme
import com.iefan.readout.viewmodel.ReadoutViewModel
import com.iefan.readout.data.Chapter
import com.iefan.readout.data.Bookmark
import com.iefan.readout.utils.InAppReviewHelper

class MainActivity : ComponentActivity() {
    private val viewModel: ReadoutViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ReadoutViewModel::class.java]
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            android.util.Log.w("MainActivity", "Notification permission was denied by the user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(permission)
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            val viewModel = this@MainActivity.viewModel
            val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()

            MyApplicationTheme(primaryColor = themeColor) {
                // Collect reactive StateFlows from ViewModel
                val allDocuments by viewModel.allDocuments.collectAsStateWithLifecycle()
                val isLibraryOpen by viewModel.isLibraryOpen.collectAsStateWithLifecycle()
                val allCollections by viewModel.allCollections.collectAsStateWithLifecycle()
                val allCrossRefs by viewModel.allCrossRefs.collectAsStateWithLifecycle()
                val activeDoc by viewModel.activeDocument.collectAsStateWithLifecycle()
                val sentences by viewModel.activeSentences.collectAsStateWithLifecycle()
                val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
                val currentIndex by viewModel.currentSentenceIndex.collectAsStateWithLifecycle()
                val wordRange by viewModel.currentWordRange.collectAsStateWithLifecycle()
                val speed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
                val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
                val remainingSeconds by viewModel.sleepTimerRemainingSeconds.collectAsStateWithLifecycle()
                val selectedVoiceId by viewModel.selectedVoiceId.collectAsStateWithLifecycle()
                val previewingVoiceId by viewModel.previewingVoiceId.collectAsStateWithLifecycle()
                val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()
                val translationTargetLang by viewModel.translationTargetLang.collectAsStateWithLifecycle()
                val translatedSentences by viewModel.translatedSentences.collectAsStateWithLifecycle()

                val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
                val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
                val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsStateWithLifecycle()
                val isPreparingPlayback by viewModel.isPreparingPlayback.collectAsStateWithLifecycle()
                val activeChapters by viewModel.activeChapters.collectAsStateWithLifecycle()
                val activeBookmarks by viewModel.activeBookmarks.collectAsStateWithLifecycle()
                val allBookmarks by viewModel.allBookmarks.collectAsStateWithLifecycle()

                val progressFraction = remember(wordRange, sentences, currentIndex, activeDoc) {
                    val doc = activeDoc
                    if (doc != null) {
                        val totalChars = if (sentences.isNotEmpty()) {
                            sentences.last().end
                        } else {
                            doc.contentLength.takeIf { it > 0 } ?: doc.content.length
                        }
                        val currentCharIndex = wordRange?.second
                            ?: (sentences.getOrNull(currentIndex)?.start)
                            ?: doc.playbackPosition
                        if (totalChars > 0) (currentCharIndex.toFloat() / totalChars).coerceIn(0f, 1f) else 0f
                    } else {
                        0f
                    }
                }

                var showSettings by remember { mutableStateOf(false) }
                var settingsInitialScreen by remember { mutableIntStateOf(0) }

                val scope = rememberCoroutineScope()

                BackHandler(enabled = isPlayerExpanded) {
                    viewModel.minimizePlayer()
                }

                BackHandler(enabled = !isPlayerExpanded && isLibraryOpen) {
                    viewModel.setLibraryOpen(false)
                }

                val snackbarHostState = remember { SnackbarHostState() }

                val importBackupLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: android.net.Uri? ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val jsonString = this@MainActivity.contentResolver.openInputStream(uri)?.use { input ->
                                    input.bufferedReader().readText()
                                }
                                if (jsonString != null) {
                                    val success = viewModel.importBackupData(jsonString)
                                    if (success) {
                                        snackbarHostState.showSnackbar("Data imported successfully!")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to import data: invalid format")
                                    }
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Failed to read backup file: ${e.localizedMessage}")
                            }
                        }
                    }
                }

                val exportBackupLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json")
                ) { uri: android.net.Uri? ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val jsonString = viewModel.exportBackupData()
                                this@MainActivity.contentResolver.openOutputStream(uri)?.use { output ->
                                    output.bufferedWriter().use { writer ->
                                        writer.write(jsonString)
                                    }
                                }
                                snackbarHostState.showSnackbar("Data exported successfully!")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Failed to export data: ${e.localizedMessage}")
                            }
                        }
                    }
                }
                val importError by viewModel.importError.collectAsStateWithLifecycle()

                LaunchedEffect(importError) {
                    importError?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearImportError()
                    }
                }

                var lastOpenedDocId by remember { mutableStateOf<Long?>(null) }
                var achievedHighProgress by remember { mutableStateOf(false) }

                LaunchedEffect(progressFraction) {
                    if (progressFraction > 0.85f) {
                        achievedHighProgress = true
                    }
                }

                LaunchedEffect(activeDoc) {
                    val doc = activeDoc
                    if (doc != null) {
                        lastOpenedDocId = doc.id
                        achievedHighProgress = false
                    } else {
                        if (lastOpenedDocId != null && achievedHighProgress) {
                            InAppReviewHelper.checkAndPromptReview(this@MainActivity)
                        }
                        lastOpenedDocId = null
                        achievedHighProgress = false
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Black,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { paddingValues ->
                    val activeDocVal = activeDoc
                    if (!isPlayerExpanded) {
                        if (isLibraryOpen) {
                            LibraryView(
                                modifier = Modifier.padding(paddingValues),
                                allDocuments = allDocuments,
                                allCollections = allCollections,
                                allCrossRefs = allCrossRefs,
                                allBookmarks = allBookmarks,
                                onSelectBookmark = { bookmark -> viewModel.selectBookmark(bookmark) },
                                onBack = { viewModel.setLibraryOpen(false) },
                                onSelectDocument = { doc ->
                                    viewModel.selectDocument(doc)
                                },
                                onToggleFavorite = { doc -> viewModel.toggleFavorite(doc) },
                                onAddDocumentToCollection = { docId, colId -> viewModel.addDocumentToCollection(docId, colId) },
                                onRemoveDocumentFromCollection = { docId, colId -> viewModel.removeDocumentFromCollection(docId, colId) },
                                onCreateCollection = { name, docId -> viewModel.createCollection(name, docId) },
                                onDeleteCollection = { col -> viewModel.deleteCollection(col) },
                                onDeleteDocument = { doc -> viewModel.deleteDocument(doc) },
                                onEditDocument = { docId, nextTitle, nextCoverUri, removeCover ->
                                    viewModel.updateBookDetails(docId, nextTitle, nextCoverUri, removeCover)
                                }
                            )
                        } else {
                            // The minimalist drop zone front library
                            MainLibraryView(
                                modifier = Modifier.padding(paddingValues),
                                allDocuments = allDocuments,
                                allCollections = allCollections,
                                allCrossRefs = allCrossRefs,
                                onSelectDocument = { doc -> viewModel.selectDocument(doc) },
                                onDeleteDocument = { doc -> viewModel.deleteDocument(doc) },
                                onAddDocument = { title, content, sUrl, coverPath, isFavorite, collectionId ->
                                    viewModel.addNewBook(title, content, sUrl, coverPath, emptyList(), false, null, isFavorite, collectionId)
                                },
                                onOpenSettings = {
                                    settingsInitialScreen = 0
                                    showSettings = true
                                },
                                onOpenLibrary = { viewModel.setLibraryOpen(true) },
                                onToggleFavorite = { doc -> viewModel.toggleFavorite(doc) },
                                onAddDocumentToCollection = { docId, colId -> viewModel.addDocumentToCollection(docId, colId) },
                                onRemoveDocumentFromCollection = { docId, colId -> viewModel.removeDocumentFromCollection(docId, colId) },
                                onCreateCollection = { name, docId, onCreated -> viewModel.createCollection(name, docId, onCreated) },
                                onDeleteCollection = { col -> viewModel.deleteCollection(col) },
                                onRenameCollection = { col, newName -> viewModel.renameCollection(col, newName) },
                                isImporting = isImporting,
                                importProgress = importProgress,
                                onBatchImport = { drafts ->
                                    viewModel.importDocumentsBatch(drafts.map {
                                        com.iefan.readout.viewmodel.BatchImportItem(
                                            uri = it.uri,
                                            title = it.title.ifBlank { null },
                                            customCoverUri = it.customCoverUri,
                                            isFavorite = it.isFavorite,
                                            collectionId = it.collectionId
                                        )
                                    })
                                },
                                onUrlImport = { url, customTitle, coverUri, isFavorite, collectionId ->
                                    viewModel.importDocumentFromUrl(url, customTitle, coverUri, isFavorite, collectionId)
                                },
                                onUriImport = { uri, customTitle, autoSelect, coverUri, isFavorite, collectionId ->
                                    viewModel.importDocumentFromUri(uri, customTitle, autoSelect, coverUri, isFavorite, collectionId)
                                },
                                onEditDocument = { docId, nextTitle, nextCoverUri, removeCover ->
                                    viewModel.updateBookDetails(docId, nextTitle, nextCoverUri, removeCover)
                                },
                                onReorderCollections = { ids -> viewModel.reorderCollections(ids) },
                                
                                // Mini Player bindings
                                activeDocument = activeDocVal,
                                isPlaying = isPlaying,
                                progressFraction = progressFraction,
                                onTogglePlayback = { viewModel.togglePlayback() },
                                onSkipForward = { viewModel.skipForward() },
                                onExpandPlayer = { viewModel.expandPlayer() },
                                onCloseMiniPlayer = { viewModel.deselectDocument() },
                                onSeekToFraction = { fraction -> viewModel.seekToFraction(fraction) }
                            )
                        }
                    } else {
                        // High-fidelity active acoustic reader
                        val doc = activeDocVal ?: run {
                            if (!isPreparingPlayback) {
                                viewModel.minimizePlayer()
                            }
                            return@Scaffold
                        }
                        ActivePlayerView(
                            modifier = Modifier.fillMaxSize(),
                            document = doc,
                            sentences = sentences,
                            isPlaying = isPlaying,
                            currentSentenceIndex = currentIndex,
                            currentWordRange = wordRange,
                            progressFraction = progressFraction,
                            playbackSpeed = speed,
                            sleepTimerMinutes = sleepTimerMinutes,
                            sleepTimerRemainingSeconds = remainingSeconds,
                            chapters = activeChapters,
                            bookmarks = activeBookmarks,
                            onBack = { viewModel.minimizePlayer() },
                            onTogglePlayback = { viewModel.togglePlayback() },
                            onSkipForward = { viewModel.skipForward() },
                            onSkipBackward = { viewModel.skipBackward() },
                            onSeekToSentence = { index -> viewModel.seekToSentence(index) },
                            onSpeedChanged = { nextSpeed -> viewModel.setPlaybackSpeed(nextSpeed) },
                            onSleepTimerChanged = { m -> viewModel.startSleepTimer(m) },
                            onSeekToChapter = { chapter -> viewModel.seekToChapter(chapter) },
                            onSeekToBookmark = { bookmark -> viewModel.seekToBookmark(bookmark) },
                            onAddBookmark = { sentIdx, charOff, lbl ->
                                viewModel.addBookmark(sentIdx, charOff, lbl)
                            },
                            onRemoveBookmark = { bookmark -> viewModel.removeBookmark(bookmark) },
                            isTranslating = translationTargetLang != "none",
                            translationTargetLang = translationTargetLang,
                            translatedSentences = translatedSentences,
                            isPreparingPlayback = isPreparingPlayback,
                            onSeekToFraction = { fraction -> viewModel.seekToFraction(fraction) },
                            onOpenSettings = {
                                settingsInitialScreen = 0
                                showSettings = true
                            },
                            onOpenTranslation = {
                                settingsInitialScreen = 2
                                showSettings = true
                            }
                        )
                    }

                    // Settings dialog overlay
                    if (showSettings) {
                        SettingsDialog(
                            initialScreen = settingsInitialScreen,
                            selectedVoiceId = selectedVoiceId,
                            previewingVoiceId = previewingVoiceId,
                            availableVoices = availableVoices,
                            onSelectVoice = { voiceId -> viewModel.setSelectedVoiceId(voiceId) },
                            onPreviewVoice = { voiceId, name, locale -> viewModel.previewVoice(voiceId, name, locale) },
                            translationTargetLang = translationTargetLang,
                            onSelectTranslationLang = { lang -> viewModel.setTranslationTargetLang(lang) },
                            themeColor = themeColor,
                            onThemeColorChange = { color -> viewModel.setThemeColor(color) },
                            onImportData = {
                                showSettings = false
                                importBackupLauncher.launch("application/json")
                            },
                            onExportData = {
                                showSettings = false
                                exportBackupLauncher.launch("readout_backup_${System.currentTimeMillis()}.json")
                            },
                            onDismiss = { showSettings = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        Log.d("MainActivity", "handleIntent: action=$action, type=$type")

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("text/")) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    val trimmed = text.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || 
                        android.util.Patterns.WEB_URL.matcher(trimmed).matches()) {
                        Log.d("MainActivity", "Importing Web URL from Intent: $trimmed")
                        viewModel.importDocumentFromUrl(trimmed, null)
                    } else {
                        Log.d("MainActivity", "Importing plain text snippet from Intent")
                        val titleSnippet = if (trimmed.length > 30) trimmed.take(27) + "..." else trimmed
                        viewModel.addNewBook(title = "Shared: $titleSnippet", content = trimmed, sourceUrl = "Shared Text")
                    }
                }
            } else {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    Log.d("MainActivity", "Importing file URI via ACTION_SEND: $uri")
                    viewModel.importDocumentFromUri(uri, null, autoSelect = false)
                }
            }
        } else if (Intent.ACTION_VIEW == action) {
            val uri = intent.data
            if (uri != null) {
                Log.d("MainActivity", "Importing file URI via ACTION_VIEW: $uri")
                viewModel.importDocumentFromUri(uri, null, autoSelect = false)
            }
        }
    }
}
