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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.iefan.readout.tts.SpeechSentence

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SentenceReaderView(
    sentences: List<SpeechSentence>,
    activeSentenceIndex: Int,
    isPlaying: Boolean,
    isTranslating: Boolean = false,
    translatedSentences: Map<Int, String> = emptyMap(),
    translationErrors: Map<Int, String> = emptyMap(),
    onRetryTranslation: (Int) -> Unit = {},
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

    // Instant jump for offscreen / distant targets (cold-start resume, chapter jump, bookmark, scrub).
    // Smooth scroll ONLY for immediate adjacent sentence during continuous playback.
    suspend fun scrollToSentence(targetIndex: Int, animateIfNearby: Boolean = true) {
        if (targetIndex !in sentences.indices) return
        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val targetTopPx = if (viewportHeight > 0) (viewportHeight * 0.10f).toInt() else 100

        val activeItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (activeItem != null && animateIfNearby && kotlin.math.abs(activeItem.index - targetIndex) <= 1) {
            val delta = (activeItem.offset - targetTopPx).toFloat()
            if (kotlin.math.abs(delta) > 4f) {
                listState.animateScrollBy(delta, tween(250, easing = FastOutSlowInEasing))
            }
        } else {
            listState.scrollToItem(targetIndex, -targetTopPx)
            val updated = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (updated != null) {
                val delta = (updated.offset - targetTopPx).toFloat()
                if (kotlin.math.abs(delta) > 8f) {
                    listState.animateScrollBy(delta, tween(150, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    LaunchedEffect(activeSentenceIndex, sentences.isNotEmpty(), autoFollowEnabled) {
        if (!autoFollowEnabled || isUserDragging || activeSentenceIndex !in sentences.indices) return@LaunchedEffect
        scrollToSentence(activeSentenceIndex, animateIfNearby = true)
    }

    val activeTranslation = translatedSentences[activeSentenceIndex]
    LaunchedEffect(activeTranslation) {
        if (!autoFollowEnabled || isUserDragging || activeSentenceIndex !in sentences.indices) return@LaunchedEffect
        val activeItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeSentenceIndex }
        if (activeItem != null) {
            val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            val targetTopPx = if (viewportHeight > 0) (viewportHeight * 0.10f).toInt() else 100
            val delta = (activeItem.offset - targetTopPx).toFloat()
            if (kotlin.math.abs(delta) > 6f) {
                listState.animateScrollBy(delta, tween(200, easing = FastOutSlowInEasing))
            }
        }
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    if (isTranslating) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collectLatest { index ->
                    delay(300)
                    com.iefan.readout.tts.ReadoutTtsEngine.instance?.prefetchTranslations(
                        index,
                        count = 6
                    )
                }
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
                modifier = Modifier.fillMaxSize().testTag("sentence_scroller")
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
                                horizontal = 14.dp,
                                vertical = 12.dp
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            run {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp, end = 10.dp)
                                        .width(3.5.dp)
                                        .height(20.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isActive) primaryColor else Color.Transparent)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                val textColor = if (isActive) {
                                    Color.White
                                } else if (idx < activeSentenceIndex) {
                                    Color.White.copy(alpha = 0.72f)
                                } else {
                                    Color.White.copy(alpha = 0.82f)
                                }

                                Text(
                                    text = sentence.text,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                        lineHeight = 32.sp,
                                        fontSize = 19.sp,
                                        letterSpacing = 0.18.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = textColor
                                )

                                 val translated = translatedSentences[idx]
                                 if (isTranslating) {
                                     if (!translated.isNullOrBlank() && translated.trim() != sentence.text.trim()) {
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
                                     } else if (isActive && translated == null) {
                                         Spacer(modifier = Modifier.height(4.dp))
                                         Text(
                                             text = translationErrors[idx] ?: "Translating...",
                                             modifier = Modifier.combinedClickable(onClick = { onRetryTranslation(idx) }),
                                             style = MaterialTheme.typography.bodySmall.copy(
                                                 fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                                 fontSize = 13.sp,
                                                 fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                             ),
                                             color = primaryColor.copy(alpha = 0.6f)
                                         )
                                     }
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
                        coroutineScope.launch { scrollToSentence(activeSentenceIndex, animateIfNearby = false) }
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
