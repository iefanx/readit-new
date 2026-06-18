package com.iefan.readout.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.data.Document
import com.iefan.readout.data.Chapter
import com.iefan.readout.tts.SpeechSentence
import com.iefan.readout.ui.components.KaraokeView
import com.iefan.readout.ui.components.SoundWaveVisualizer
import com.iefan.readout.ui.components.styleOfCaption
import com.iefan.readout.ui.components.styleOfSubtitle
import com.iefan.readout.ui.components.ChapterSelectionDialog
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.automirrored.filled.ArrowBack

enum class ActiveOverlay {
    NONE,
    SLEEP_TIMER,
    SPEED_ONLY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivePlayerView(
    document: Document,
    sentences: List<SpeechSentence>,
    isPlaying: Boolean,
    currentSentenceIndex: Int,
    currentWordRange: Pair<Int, Int>?,
    progressFraction: Float,
    playbackSpeed: Float,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSeconds: Int,
    chapters: List<Chapter>,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSeekToSentence: (Int) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onSleepTimerChanged: (Int) -> Unit,
    onSeekToChapter: (Chapter) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        if (isPlaying) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    var activeOverlay by remember { mutableStateOf(ActiveOverlay.NONE) }
    var showChapterDialog by remember { mutableStateOf(false) }

    val currentCharacterOffset = remember(currentSentenceIndex, sentences) {
        if (currentSentenceIndex in sentences.indices) {
            sentences[currentSentenceIndex].start
        } else {
            0
        }
    }

    if (showChapterDialog) {
        ChapterSelectionDialog(
            chapters = chapters,
            currentCharOffset = currentCharacterOffset,
            onChapterSelected = onSeekToChapter,
            onDismiss = { showChapterDialog = false }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Readout Player",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("player_back_btn")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return to Library",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (chapters.isNotEmpty()) {
                        IconButton(onClick = { showChapterDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Toc,
                                contentDescription = "Table of Contents",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Upper viewport: Karaoke follow reader viewport (takes full height in background)
            KaraokeView(
                sentences = sentences,
                activeSentenceIndex = currentSentenceIndex,
                currentWordRange = currentWordRange,
                onSentenceJump = onSeekToSentence,
                modifier = Modifier.fillMaxSize()
            )

            // Overlaid Panel floating elegantly over the e-reader viewport
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = activeOverlay,
                    transitionSpec = {
                        slideInVertically { it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
                    },
                    label = "player_drawer_anim",
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) { overlay ->
                    when (overlay) {

                        ActiveOverlay.SLEEP_TIMER -> {
                            SleepTimerPanel(
                                sleepMinutes = sleepTimerMinutes,
                                sleepSecondsRemaining = sleepTimerRemainingSeconds,
                                onSleepChange = onSleepTimerChanged,
                                onClose = { activeOverlay = ActiveOverlay.NONE },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .border(1.dp, Color(0xFF242426), RoundedCornerShape(24.dp))
                            )
                        }
                        ActiveOverlay.SPEED_ONLY -> {
                            SpeedOnlyPanel(
                                speed = playbackSpeed,
                                onSpeedChange = onSpeedChanged,
                                onClose = { activeOverlay = ActiveOverlay.NONE },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .border(1.dp, Color(0xFF242426), RoundedCornerShape(24.dp))
                            )
                        }
                        ActiveOverlay.NONE -> {
                            FloatingCapsulePlayer(
                                progressFraction = progressFraction,
                                isPlaying = isPlaying,
                                playbackSpeed = playbackSpeed,
                                sleepTimerMinutes = sleepTimerMinutes,
                                sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                                onTogglePlayback = onTogglePlayback,
                                onSkipForward = onSkipForward,
                                onSkipBackward = onSkipBackward,
                                onSelectSleepTimer = { activeOverlay = ActiveOverlay.SLEEP_TIMER },
                                onSelectSpeedOnly = { activeOverlay = ActiveOverlay.SPEED_ONLY },
                                modifier = Modifier
                                    .width(330.dp)
                                    .height(72.dp)
                                    .clip(RoundedCornerShape(36.dp))
                                    .background(Color(0xFF141416))
                                    .border(1.dp, Color(0xFF242426), RoundedCornerShape(36.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingCapsulePlayer(
    progressFraction: Float,
    isPlaying: Boolean,
    playbackSpeed: Float,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSeconds: Int,
    onTogglePlayback: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSelectSleepTimer: () -> Unit,
    onSelectSpeedOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Dynamic Sleep Timer Icon Selection (Glowing Active State or normal sleep clock)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(if (sleepTimerMinutes > 0) MaterialTheme.colorScheme.primary else Color(0xFF1F1F22))
                    .clickable { onSelectSleepTimer() },
                contentAlignment = Alignment.Center
            ) {
                if (sleepTimerMinutes > 0) {
                    val mm = sleepTimerRemainingSeconds / 60
                    val ss = sleepTimerRemainingSeconds % 60
                    Text(
                        text = String.format(java.util.Locale.US, "%02d:%02d", mm, ss),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Sleep Timer",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Rewind 10s Button
            IconButton(
                onClick = onSkipBackward,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = "Rewind",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Central Play/Pause Capsule
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(27.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onTogglePlayback() }
                    .testTag("play_pause_toggle_btn"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Forward 10s Button
            IconButton(
                onClick = onSkipForward,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Forward",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Speed Trigger Button Shows context overlay
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color(0xFF1F1F22))
                    .clickable { onSelectSpeedOnly() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", playbackSpeed),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        LinearProgressIndicator(
            progress = progressFraction,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .padding(top = 0.dp)
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.15f)
        )
    }
}



@Composable
fun SleepTimerPanel(
    sleepMinutes: Int,
    sleepSecondsRemaining: Int,
    onSleepChange: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 16.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Sleep Timer",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "SLEEP COUNTDOWN TIMER",
                        style = MaterialTheme.styleOfSubtitle(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = "Select a countdown duration. Playback will stop automatically when the timer reaches zero.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            if (sleepMinutes > 0) {
                val mm = sleepSecondsRemaining / 60
                val ss = sleepSecondsRemaining % 60
                Text(
                    text = String.format(java.util.Locale.US, "Active Countdown: %02d:%02d remaining", mm, ss),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Expanded timer options: Off/Cancel, 5m, 10m, 15m, 30m, 45m, 60m, 90m
            val timers = listOf(
                listOf(0, 5, 10, 15),
                listOf(30, 45, 60, 90)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                timers.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { m ->
                            val isSel = sleepMinutes == m
                            val label = if (m == 0) "Off" else "${m} min"
                            
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1.0f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable { onSleepChange(m) }
                                    .border(
                                        1.dp,
                                        if (isSel) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedOnlyPanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 16.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Playback Speed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "PLAYBACK SPEED SETTING",
                        style = MaterialTheme.styleOfSubtitle(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = "Adjust speaking speed dynamically to match your comfortable listening pacing.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            // Speed Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Speed Preference",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1fx", speed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Slider(
                    value = speed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..4.5f,
                    steps = 39,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("speed_governor_slider_only")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.5x (Slowest)", style = MaterialTheme.styleOfCaption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2.0x (Normal/High)", style = MaterialTheme.styleOfCaption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("4.5x (Hyper-Speed)", style = MaterialTheme.styleOfCaption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Quick Preset Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val speedPresets = listOf(0.8f, 1.0f, 1.5f, 2.0f, 2.5f)
                speedPresets.forEach { p ->
                    val isSel = p == speed
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1.0f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable { onSpeedChange(p) }
                            .border(
                                1.dp,
                                if (isSel) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1fx", p),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
