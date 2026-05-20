package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.BenchmarkDialog
import com.example.ui.screens.ActivePlayerView
import com.example.ui.screens.MainLibraryView
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.AuraBookViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: AuraBookViewModel = viewModel()
                
                // Collect reactive StateFlows from ViewModel
                val allDocuments by viewModel.allDocuments.collectAsState()
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

                val benchmarkProgress by viewModel.benchmarkProgress.collectAsState()
                val benchmarkResult by viewModel.benchmarkResult.collectAsState()

                var showBenchmarkTrigger by remember { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    if (activeDoc == null) {
                        // The minimalist drop zone front library
                        MainLibraryView(
                            allDocuments = allDocuments,
                            onSelectDocument = { doc -> viewModel.selectDocument(doc) },
                            onDeleteDocument = { doc -> viewModel.deleteDocument(doc) },
                            onAddDocument = { title, content, sUrl, coverPath ->
                                viewModel.addNewBook(title, content, sUrl, coverPath)
                            },
                            onOpenBenchmark = { showBenchmarkTrigger = true },
                            isImporting = isImporting,
                            onUrlImport = { url, customTitle -> viewModel.importDocumentFromUrl(url, customTitle) },
                            onUriImport = { uri, customTitle -> viewModel.importDocumentFromUri(uri, customTitle) }
                        )
                    } else {
                        // High-fidelity active acoustic reader
                        val doc = activeDoc!!
                        ActivePlayerView(
                            document = doc,
                            sentences = sentences,
                            isPlaying = isPlaying,
                            currentSentenceIndex = currentIndex,
                            currentWordRange = wordRange,
                            playbackSpeed = speed,
                            selectedModelTier = modelTier,
                            sleepTimerMinutes = sleepTimerMinutes,
                            sleepTimerRemainingSeconds = remainingSeconds,
                            onBack = { viewModel.deselectDocument() },
                            onTogglePlayback = { viewModel.togglePlayback() },
                            onSkipForward = { viewModel.skipForward() },
                            onSkipBackward = { viewModel.skipBackward() },
                            onSeekToSentence = { index -> viewModel.seekToSentence(index) },
                            onSpeedChanged = { nextSpeed -> viewModel.setPlaybackSpeed(nextSpeed) },
                            onModelChanged = { tier -> viewModel.setModelTier(tier) },
                            onSleepTimerChanged = { m -> viewModel.startSleepTimer(m) }
                        )
                    }

                    // Benchmark panel overlay dialog
                    if (showBenchmarkTrigger) {
                        BenchmarkDialog(
                            progress = benchmarkProgress,
                            result = benchmarkResult,
                            onRun = { viewModel.runHardwareBenchmark() },
                            onDismiss = {
                                viewModel.resetBenchmark()
                                showBenchmarkTrigger = false
                            },
                            onSelectTier = { tier -> viewModel.setModelTier(tier) }
                        )
                    }
                }
            }
        }
    }
}
