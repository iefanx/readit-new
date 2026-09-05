package com.iefan.readout.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import kotlinx.coroutines.launch
import com.iefan.readout.tts.SpeechSentence

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KaraokeView(
    sentences: List<SpeechSentence>,
    activeSentenceIndex: Int,
    currentWordRange: Pair<Int, Int>?,
    isPlaying: Boolean,
    isTranslating: Boolean = false,
    translatedSentences: Map<Int, String> = emptyMap(),
    onSentenceJump: (Int) -> Unit,
    onLongPressBookmark: (sentenceIndex: Int, sentenceText: String) -> Unit = { _, _ -> },
    showResyncButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = activeSentenceIndex.coerceAtLeast(0)
    )
    val coroutineScope = rememberCoroutineScope()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollowEnabled by remember { mutableStateOf(true) }

    // When the user drags to scroll manually, pause auto-follow so they can freely read
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            autoFollowEnabled = false
        }
    }

    // Re-enable auto-follow whenever playback starts from paused/stopped
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            autoFollowEnabled = true
        }
    }

    fun scrollToSentence(targetIndex: Int) {
        coroutineScope.launch {
            if (targetIndex !in sentences.indices) return@launch
            val layoutInfo = listState.layoutInfo
            val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
            val targetTopPx = if (viewportHeight > 0) (viewportHeight * 0.10f).toInt() else 100

            val activeItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (activeItem != null) {
                val delta = (activeItem.offset - targetTopPx).toFloat()
                if (kotlin.math.abs(delta) > 8f) {
                    listState.animateScrollBy(delta)
                }
            } else {
                listState.scrollToItem(targetIndex, -targetTopPx)
                kotlinx.coroutines.delay(16)
                val updated = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                if (updated != null) {
                    val delta = (updated.offset - targetTopPx).toFloat()
                    if (kotlin.math.abs(delta) > 8f) {
                        listState.animateScrollBy(delta)
                    }
                }
            }
        }
    }

    // High-reliability auto-scroll: smoothly positions active sentence ~10% from viewport top
    LaunchedEffect(activeSentenceIndex, autoFollowEnabled) {
        if (!autoFollowEnabled || activeSentenceIndex !in sentences.indices || isUserDragging) return@LaunchedEffect

        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val targetTopPx = if (viewportHeight > 0) (viewportHeight * 0.10f).toInt() else 100

        val activeItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeSentenceIndex }
        if (activeItem != null) {
            val delta = (activeItem.offset - targetTopPx).toFloat()
            if (kotlin.math.abs(delta) > 8f) {
                listState.animateScrollBy(delta)
            }
        } else {
            listState.scrollToItem(activeSentenceIndex, -targetTopPx)
            kotlinx.coroutines.delay(16)
            val updated = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeSentenceIndex }
            if (updated != null) {
                val delta = (updated.offset - targetTopPx).toFloat()
                if (kotlin.math.abs(delta) > 8f) {
                    listState.animateScrollBy(delta)
                }
            }
        }
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Subtle micro-haptic tick when playback transitions to a new sentence
    LaunchedEffect(activeSentenceIndex) {
        if (isPlaying && activeSentenceIndex > 0) {
            try {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        if (sentences.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No content available. Add a document to start reading.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 220.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().testTag("karaoke_scroller")
            ) {
                // key = s.start ensures stable item identity across document changes,
                // preventing full relayout when a new book is loaded.
                itemsIndexed(sentences, key = { _, s -> s.start }) { idx, sentence ->
                    val isActive = idx == activeSentenceIndex
                    val primaryColor = MaterialTheme.colorScheme.primary

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isActive) Color(0x24FFFFFF) else Color.Transparent
                            )
                            .then(
                                if (isActive) {
                                    Modifier.border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.12f),
                                        RoundedCornerShape(14.dp)
                                    )
                                } else Modifier
                            )
                            .combinedClickable(
                                onClick = {
                                    autoFollowEnabled = true
                                    onSentenceJump(idx)
                                },
                                onLongClick = { onLongPressBookmark(idx, sentence.text) }
                            )
                            .padding(
                                horizontal = if (isActive) 14.dp else 4.dp,
                                vertical = if (isActive) 12.dp else 6.dp
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp, end = 10.dp)
                                        .width(3.5.dp)
                                        .height(20.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(primaryColor)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                val textColor = if (isActive) {
                                    Color.White
                                } else if (idx < activeSentenceIndex) {
                                    Color.White.copy(alpha = 0.42f)
                                } else {
                                    Color.White.copy(alpha = 0.30f)
                                }

                                Text(
                                    text = sentence.text,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                        lineHeight = 32.sp,
                                        fontSize = 19.sp,
                                        letterSpacing = 0.18.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )

                                val translated = translatedSentences[idx]
                                if (isTranslating && !translated.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = translated,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                            lineHeight = 24.sp,
                                            fontSize = 16.sp
                                        ),
                                        color = if (isActive) primaryColor.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top edge gradient dissolve mask
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.90f), Color.Transparent)
                        )
                    )
            )

            // Bottom edge gradient dissolve mask above floating controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                        )
                    )
            )

            // Resync floating pill: displays at bottom right above the media control player
            AnimatedVisibility(
                visible = !autoFollowEnabled && showResyncButton && sentences.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.82f) + slideInVertically { it / 2 },
                exit = fadeOut() + scaleOut(targetScale = 0.82f) + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 104.dp)
            ) {
                Surface(
                    onClick = {
                        try {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        } catch (_: Exception) {}
                        autoFollowEnabled = true
                        scrollToSentence(activeSentenceIndex)
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    shadowElevation = 8.dp,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync audio position",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Sync",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// Ext helper styles to avoid code repetition
@Composable
fun MaterialTheme.styleOfSubtitle() = typography.titleSmall.copy(
    fontSize = 11.sp,
    letterSpacing = 1.sp,
    lineHeight = 14.sp
)

@Composable
fun MaterialTheme.styleOfCaption() = typography.bodySmall.copy(
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.1.sp
)
