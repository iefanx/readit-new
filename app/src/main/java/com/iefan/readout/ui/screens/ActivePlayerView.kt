package com.iefan.readout.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
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
import com.iefan.readout.data.Bookmark
import com.iefan.readout.tts.SpeechSentence
import com.iefan.readout.ui.components.KaraokeView
import com.iefan.readout.ui.components.SoundWaveVisualizer
import com.iefan.readout.ui.components.styleOfCaption
import com.iefan.readout.utils.rememberHapticTrigger
import com.iefan.readout.ui.components.styleOfSubtitle
import com.iefan.readout.ui.components.BookmarkActionSheet
import com.iefan.readout.ui.components.TableOfContentsSheet
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
    bookmarks: List<Bookmark>,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSeekToSentence: (Int) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onSleepTimerChanged: (Int) -> Unit,
    onSeekToChapter: (Chapter) -> Unit,
    onSeekToBookmark: (Bookmark) -> Unit,
    onAddBookmark: (sentenceIndex: Int, charOffset: Int, label: String) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    isTranslating: Boolean,
    onSeekToFraction: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticTrigger = rememberHapticTrigger()
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
    var showTocSheet by remember { mutableStateOf(false) }

    // Long-press bookmark state
    data class PendingBookmark(val sentenceIndex: Int, val charOffset: Int, val text: String)
    var pendingBookmark by remember { mutableStateOf<PendingBookmark?>(null) }

    val currentCharacterOffset = remember(currentSentenceIndex, sentences) {
        if (currentSentenceIndex in sentences.indices) {
            sentences[currentSentenceIndex].start
        } else {
            0
        }
    }

    // Dynamic estimated remaining time calculations
    val wordsRemaining = remember(currentSentenceIndex, sentences) {
        if (currentSentenceIndex in sentences.indices) {
            sentences.subList(currentSentenceIndex, sentences.size).sumOf { sentence ->
                sentence.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
            }
        } else {
            0
        }
    }

    val timeRemainingStr = remember(wordsRemaining, playbackSpeed) {
        val totalSeconds = if (playbackSpeed > 0f) {
            (wordsRemaining / (2.5f * playbackSpeed)).toInt()
        } else {
            0
        }
        if (totalSeconds <= 0) {
            "0s left"
        } else if (totalSeconds < 60) {
            "${totalSeconds}s left"
        } else {
            val totalMinutes = totalSeconds / 60
            if (totalMinutes < 60) {
                "${totalMinutes}m left"
            } else {
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                if (mins > 0) {
                    "${hours}h ${mins}m left"
                } else {
                    "${hours}h left"
                }
            }
        }
    }

    // TableOfContentsSheet (chapters + bookmarks)
    if (showTocSheet) {
        TableOfContentsSheet(
            chapters = chapters,
            bookmarks = bookmarks,
            currentCharOffset = currentCharacterOffset,
            onChapterSelected = onSeekToChapter,
            onBookmarkSelected = onSeekToBookmark,
            onBookmarkDeleted = onRemoveBookmark,
            onDismiss = { showTocSheet = false }
        )
    }

    // BookmarkActionSheet (long-press on a sentence)
    pendingBookmark?.let { pending ->
        BookmarkActionSheet(
            sentenceText = pending.text,
            sentenceIndex = pending.sentenceIndex,
            charOffset = pending.charOffset,
            onConfirm = { label ->
                onAddBookmark(pending.sentenceIndex, pending.charOffset, label)
            },
            onDismiss = { pendingBookmark = null }
        )
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(bottom = 8.dp)
            ) {
                // Line 1: Nav Bar Row (Back button, title, and TOC/Chapters button)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    IconButton(
                        onClick = {
                            hapticTrigger()
                            onBack()
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .testTag("player_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return to Library",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = document.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.68f),
                        textAlign = TextAlign.Center
                    )

                    // TOC button — always visible (shows chapters tab if available,
                    // otherwise defaults to bookmarks tab)
                    IconButton(
                        onClick = {
                            hapticTrigger()
                            showTocSheet = true
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Toc,
                            contentDescription = "Table of Contents",
                            tint = Color.White
                        )
                    }
                }

                // Line 2: Wide progress bar with remaining duration
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeekableProgressBar(
                        progress = progressFraction,
                        onSeek = onSeekToFraction,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = timeRemainingStr,
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
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
                isPlaying = isPlaying,
                isTranslating = isTranslating,
                onSentenceJump = { idx ->
                    onSeekToSentence(idx)
                    if (!isPlaying) {
                        onTogglePlayback()
                    }
                },
                onLongPressBookmark = { idx, text ->
                    val charOffset = if (idx in sentences.indices) sentences[idx].start else 0
                    pendingBookmark = PendingBookmark(idx, charOffset, text)
                },
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
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekableProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = Color.White.copy(alpha = 0.15f)

    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val currentActualProgress by rememberUpdatedState(progress)
    val displayProgress = dragProgress ?: progress
    val view = LocalView.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                var isDragging = false
                detectDragGestures(
                    onDragStart = { startPosition ->
                        val progressX = currentActualProgress * size.width
                        // Check if touch is within 24.dp of the thumb circle
                        val distance = Math.abs(startPosition.x - progressX)
                        if (distance <= 24.dp.toPx()) {
                            isDragging = true
                            dragProgress = currentActualProgress
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    },
                    onDragEnd = {
                        if (isDragging) {
                            dragProgress?.let { onSeek(it) }
                            dragProgress = null
                            isDragging = false
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    },
                    onDragCancel = {
                        if (isDragging) {
                            dragProgress = null
                            isDragging = false
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    },
                    onDrag = { change, _ ->
                        if (isDragging) {
                            change.consume()
                            val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragProgress = fraction
                        }
                    }
                )
            }
    ) {
        val h = size.height
        val centerY = h / 2
        val strokeWidthVal = 4.dp.toPx()
        val thumbRadius = 6.dp.toPx()

        // Draw track
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = strokeWidthVal,
            cap = StrokeCap.Round
        )

        // Draw progress
        val progressX = displayProgress * size.width
        if (progressX > 0f) {
            drawLine(
                color = primaryColor,
                start = Offset(0f, centerY),
                end = Offset(progressX, centerY),
                strokeWidth = strokeWidthVal,
                cap = StrokeCap.Round
            )
        }

        // Draw thumb circle
        drawCircle(
            color = primaryColor,
            radius = thumbRadius,
            center = Offset(progressX, centerY)
        )
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
    val hapticTrigger = rememberHapticTrigger()
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(36.dp))
                .background(Color(0xD9141416))
                .border(1.dp, Color(0x99242426), RoundedCornerShape(36.dp))
        ) {
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
                        .background(if (sleepTimerMinutes > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color(0xCC1F1F22))
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
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Rewind 10s Button
                IconButton(
                    onClick = {
                        hapticTrigger()
                        onSkipBackward()
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Rewind",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Central Play/Pause Capsule
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(27.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .clickable {
                            hapticTrigger()
                            onTogglePlayback()
                        }
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
                    onClick = {
                        hapticTrigger()
                        onSkipForward()
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Forward",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Speed Trigger Button Shows context overlay
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(Color(0xCC1F1F22))
                        .clickable {
                            hapticTrigger()
                            onSelectSpeedOnly()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1fx", playbackSpeed),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
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
