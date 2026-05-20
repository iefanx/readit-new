package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Document
import com.example.tts.SpeechSentence
import com.example.ui.components.KaraokeView
import com.example.ui.components.SoundWaveVisualizer
import com.example.ui.components.styleOfCaption
import com.example.ui.components.styleOfSubtitle

enum class ActiveOverlay {
    NONE,
    SLEEP_TIMER,
    SPEED_ONLY,
    FULL_SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivePlayerView(
    document: Document,
    sentences: List<SpeechSentence>,
    isPlaying: Boolean,
    currentSentenceIndex: Int,
    currentWordRange: Pair<Int, Int>?,
    playbackSpeed: Float,
    selectedModelTier: String,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSeconds: Int,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSeekToSentence: (Int) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onModelChanged: (String) -> Unit,
    onSleepTimerChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeOverlay by remember { mutableStateOf(ActiveOverlay.NONE) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "ReadIt Player",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("player_back_btn")) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Return to Library",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { 
                            activeOverlay = if (activeOverlay == ActiveOverlay.FULL_SETTINGS) {
                                ActiveOverlay.NONE 
                            } else {
                                ActiveOverlay.FULL_SETTINGS 
                            }
                        },
                        modifier = Modifier.testTag("player_settings_btn")
                    ) {
                        Icon(
                            imageVector = if (activeOverlay == ActiveOverlay.FULL_SETTINGS) Icons.Default.Article else Icons.Default.Tune,
                            contentDescription = "Toggle Player Console",
                            tint = Color.White
                        )
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
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    AnimatedContent(
                        targetState = activeOverlay,
                        transitionSpec = {
                            slideInVertically { it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
                        },
                        label = "player_drawer_anim"
                    ) { overlay ->
                        when (overlay) {
                            ActiveOverlay.FULL_SETTINGS -> {
                                PlayerConsolePanel(
                                    speed = playbackSpeed,
                                    onSpeedChange = onSpeedChanged,
                                    modelTier = selectedModelTier,
                                    onModelChange = onModelChanged,
                                    sleepMinutes = sleepTimerMinutes,
                                    sleepSecondsRemaining = sleepTimerRemainingSeconds,
                                    onSleepChange = onSleepTimerChanged,
                                    onCloseSettings = { activeOverlay = ActiveOverlay.NONE },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .border(1.dp, Color(0xFF242426), RoundedCornerShape(24.dp))
                                )
                            }
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
                                    sentencesSize = sentences.size,
                                    currentIndex = currentSentenceIndex,
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

                    // Extra subtle speaking status matching Screen 2
                    val speakStateProgress = if (sentences.size > 0) {
                        (currentSentenceIndex + 1) * 100 / sentences.size
                    } else 0
                    
                    val statusLabelText = if (isPlaying) {
                        "● Speaking sentence ${currentSentenceIndex + 1} of ${sentences.size} (${speakStateProgress}%)"
                    } else {
                        "● Speech Engine Ready"
                    }
                    
                    Text(
                        text = statusLabelText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.animateContentSize()
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingCapsulePlayer(
    sentencesSize: Int,
    currentIndex: Int,
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
    Row(
        modifier = modifier.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Dynamic Sleep Timer Icon Selection (Glowing Active State or normal sleep clock)
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(if (sleepTimerMinutes > 0) Color(0xFF7F4FFD) else Color(0xFF1F1F22))
                .clickable { onSelectSleepTimer() },
            contentAlignment = Alignment.Center
        ) {
            if (sleepTimerMinutes > 0) {
                val mm = sleepTimerRemainingSeconds / 60
                val ss = sleepTimerRemainingSeconds % 60
                Text(
                    text = String.format("%02d:%02d", mm, ss),
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
                .background(Color(0xFF4F26C4)) // Beautiful purple circle contrast from reference
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
                text = String.format("%.1fx", playbackSpeed),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun PlayerConsolePanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modelTier: String,
    onModelChange: (String) -> Unit,
    sleepMinutes: Int,
    onSleepChange: (Int) -> Unit,
    sleepSecondsRemaining: Int,
    onCloseSettings: () -> Unit,
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
                Text(
                    text = "SPEECH CONTROL PANEL",
                    style = MaterialTheme.styleOfSubtitle(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = onCloseSettings, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Speed Governor slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Speed Governor",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format("%.1fx", speed),
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
                        .testTag("speed_governor_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.5x", style = MaterialTheme.styleOfCaption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2.0x", style = MaterialTheme.styleOfCaption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("4.5x", style = MaterialTheme.styleOfCaption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Sleep Timer selection (Brief representation in full console)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sleep Countdown",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (sleepMinutes > 0) {
                        val mm = sleepSecondsRemaining / 60
                        val ss = sleepSecondsRemaining % 60
                        Text(
                            text = String.format("%02d:%02d remaining", mm, ss),
                            style = MaterialTheme.styleOfCaption(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val timers = listOf(0, 15, 30, 45)
                    timers.forEach { m ->
                        val isSel = sleepMinutes == m
                        val label = if (m == 0) "Off" else "${m}m"

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1.0f)
                                .height(36.dp)
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
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Synthesizer Tier
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Acoustic Synthesizer (ONNX Model)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tiers = listOf("ULTRA_LIGHT", "BALANCED", "HIGH_FIDELITY")
                    tiers.forEach { tier ->
                        val isSel = modelTier == tier
                        val label = when (tier) {
                            "ULTRA_LIGHT" -> "Ultra-Light"
                            "BALANCED" -> "Balanced"
                            else -> "High-Fid"
                        }

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
                                .clickable { onModelChange(tier) }
                                .border(
                                    1.dp,
                                    if (isSel) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
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
                    text = String.format("Active Countdown: %02d:%02d remaining", mm, ss),
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
                        text = String.format("%.1fx", speed),
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
                            text = String.format("%.1fx", p),
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
