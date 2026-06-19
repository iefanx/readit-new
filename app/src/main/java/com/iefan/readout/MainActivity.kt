package com.iefan.readout

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iefan.readout.ui.components.VoiceSelectionDialog
import com.iefan.readout.ui.screens.*
import com.iefan.readout.ui.theme.MyApplicationTheme
import com.iefan.readout.viewmodel.ReadoutViewModel
import com.iefan.readout.data.Chapter
import com.iefan.readout.utils.InAppReviewHelper

class MainActivity : ComponentActivity() {
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

        setContent {
            MyApplicationTheme {
                val viewModel: ReadoutViewModel = viewModel()
                
                // Collect reactive StateFlows from ViewModel
                val allDocuments by viewModel.allDocuments.collectAsState()
                val isLibraryOpen by viewModel.isLibraryOpen.collectAsState()
                val allCollections by viewModel.allCollections.collectAsState()
                val allCrossRefs by viewModel.allCrossRefs.collectAsState()
                val activeDoc by viewModel.activeDocument.collectAsState()
                val sentences by viewModel.activeSentences.collectAsState()
                val isPlaying by viewModel.isPlaying.collectAsState()
                val currentIndex by viewModel.currentSentenceIndex.collectAsState()
                val wordRange by viewModel.currentWordRange.collectAsState()
                val speed by viewModel.playbackSpeed.collectAsState()
                val modelTier by viewModel.selectedModelTier.collectAsState()
                val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsState()
                val remainingSeconds by viewModel.sleepTimerRemainingSeconds.collectAsState()
                val isImporting by viewModel.isImporting.collectAsState()
                val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsState()
                val activeChapters by viewModel.activeChapters.collectAsState()

                val progressFraction = remember(wordRange, sentences, currentIndex) {
                    val totalChars = if (sentences.isNotEmpty()) sentences.last().end else 0
                    val currentCharIndex = wordRange?.first ?: (sentences.getOrNull(currentIndex)?.start ?: 0)
                    if (totalChars > 0) currentCharIndex.toFloat() / totalChars else 0f
                }

                var showVoiceSelector by remember { mutableStateOf(false) }

                BackHandler(enabled = isPlayerExpanded) {
                    viewModel.minimizePlayer()
                }

                BackHandler(enabled = !isPlayerExpanded && isLibraryOpen) {
                    viewModel.setLibraryOpen(false)
                }

                val snackbarHostState = remember { SnackbarHostState() }
                val importError by viewModel.importError.collectAsState()

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
                                onAddDocument = { title, content, sUrl, coverPath ->
                                    viewModel.addNewBook(title, content, sUrl, coverPath)
                                },
                                onOpenVoiceSelector = { showVoiceSelector = true },
                                onOpenLibrary = { viewModel.setLibraryOpen(true) },
                                onToggleFavorite = { doc -> viewModel.toggleFavorite(doc) },
                                onAddDocumentToCollection = { docId, colId -> viewModel.addDocumentToCollection(docId, colId) },
                                onRemoveDocumentFromCollection = { docId, colId -> viewModel.removeDocumentFromCollection(docId, colId) },
                                onCreateCollection = { name, docId -> viewModel.createCollection(name, docId) },
                                onDeleteCollection = { col -> viewModel.deleteCollection(col) },
                                onRenameCollection = { col, newName -> viewModel.renameCollection(col, newName) },
                                isImporting = isImporting,
                                onUrlImport = { url, customTitle -> viewModel.importDocumentFromUrl(url, customTitle) },
                                onUriImport = { uri, customTitle -> viewModel.importDocumentFromUri(uri, customTitle) },
                                onEditDocument = { docId, nextTitle, nextCoverUri, removeCover ->
                                    viewModel.updateBookDetails(docId, nextTitle, nextCoverUri, removeCover)
                                },
                                
                                // Mini Player bindings
                                activeDocument = activeDocVal,
                                isPlaying = isPlaying,
                                progressFraction = progressFraction,
                                onTogglePlayback = { viewModel.togglePlayback() },
                                onSkipForward = { viewModel.skipForward() },
                                onExpandPlayer = { viewModel.expandPlayer() },
                                onCloseMiniPlayer = { viewModel.deselectDocument() }
                            )
                        }
                    } else {
                        // High-fidelity active acoustic reader
                        val doc = activeDocVal ?: run {
                            viewModel.minimizePlayer()
                            return@Scaffold
                        }
                        ActivePlayerView(
                            modifier = Modifier.padding(paddingValues),
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
                            onBack = { viewModel.minimizePlayer() },
                            onTogglePlayback = { viewModel.togglePlayback() },
                            onSkipForward = { viewModel.skipForward() },
                            onSkipBackward = { viewModel.skipBackward() },
                            onSeekToSentence = { index -> viewModel.seekToSentence(index) },
                            onSpeedChanged = { nextSpeed -> viewModel.setPlaybackSpeed(nextSpeed) },
                            onSleepTimerChanged = { m -> viewModel.startSleepTimer(m) },
                            onSeekToChapter = { chapter -> viewModel.seekToChapter(chapter) }
                        )
                    }

                    // Voice selection dialog overlay
                    if (showVoiceSelector) {
                        VoiceSelectionDialog(
                            currentTier = modelTier,
                            onSelectTier = { tier -> viewModel.setModelTier(tier) },
                            onDismiss = { showVoiceSelector = false }
                        )
                    }
                }
            }
        }
    }
}
