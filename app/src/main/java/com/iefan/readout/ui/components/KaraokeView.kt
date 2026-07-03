package com.iefan.readout.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.tts.SpeechSentence
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KaraokeView(
    sentences: List<SpeechSentence>,
    activeSentenceIndex: Int,
    currentWordRange: Pair<Int, Int>?,
    isPlaying: Boolean,
    isTranslating: Boolean = false,
    onSentenceJump: (Int) -> Unit,
    onLongPressBookmark: (sentenceIndex: Int, sentenceText: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var autoFollowEnabled by remember { mutableStateOf(true) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    // CONFLATED channel: if multiple sentence advances arrive before a scroll finishes,
    // only the latest one is processed — prevents scroll animation queue buildup at high speed.
    val scrollChannel = remember { Channel<Int>(Channel.CONFLATED) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) autoFollowEnabled = true
    }

    // Disable auto-follow when user manually scrolls
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collectLatest {
                if (!isAutoScrolling) autoFollowEnabled = false
            }
    }

    // Produce: send the current sentence index into the scroll channel whenever it changes
    LaunchedEffect(activeSentenceIndex, autoFollowEnabled) {
        if (autoFollowEnabled && activeSentenceIndex in sentences.indices) {
            scrollChannel.send(activeSentenceIndex)
        }
    }

    // Consume: process scroll requests one at a time, always keeping active sentence at ~35% from top.
    // Fires BEFORE the sentence reaches the bottom (comfort zone is 20%–55%), well above media player.
    LaunchedEffect(scrollChannel) {
        for (targetIndex in scrollChannel) {
            if (!autoFollowEnabled || targetIndex !in sentences.indices) continue

            val layoutInfo = listState.layoutInfo
            val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
            if (viewportHeight <= 0) continue

            val activeItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }

            isAutoScrolling = true
            try {
                if (activeItem != null) {
                    val targetTopPx = (viewportHeight * 0.35f).toInt()
                    val currentTopPx = activeItem.offset
                    val comfortTop = (viewportHeight * 0.20f).toInt()
                    val comfortBottom = (viewportHeight * 0.55f).toInt()

                    if (currentTopPx < comfortTop || currentTopPx > comfortBottom) {
                        listState.animateScrollBy((currentTopPx - targetTopPx).toFloat())
                    }
                } else {
                    // Sentence is off-screen — jump to it then position at 35%
                    listState.scrollToItem(targetIndex)
                    val info2 = listState.layoutInfo
                    val vH = info2.viewportEndOffset - info2.viewportStartOffset
                    val item2 = info2.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                    if (item2 != null) {
                        val delta = (item2.offset - (vH * 0.35f).toInt()).toFloat()
                        listState.animateScrollBy(delta)
                    }
                }
            } finally {
                isAutoScrolling = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
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
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().testTag("karaoke_scroller")
            ) {
                // key = s.start ensures stable item identity across document changes,
                // preventing full relayout when a new book is loaded.
                itemsIndexed(sentences, key = { _, s -> s.start }) { idx, sentence ->
                    val isActive = idx == activeSentenceIndex

                    // Non-active sentences: memoized by sentence text only — NOT invalidated on every
                    // word change. Active sentence: also includes currentWordRange in the key so only
                    // the currently-spoken sentence rebuilds its AnnotatedString on each word advance.
                    val annotatedText: AnnotatedString = if (isActive) {
                        remember(sentence.text, currentWordRange, isTranslating) {
                            buildAnnotatedString {
                                if (currentWordRange != null && !isTranslating) {
                                    val readLength = (currentWordRange.second - sentence.start)
                                        .coerceIn(0, sentence.text.length)
                                    withStyle(SpanStyle(background = Color(0xFF2E43FA), color = Color.White)) {
                                        append(sentence.text.substring(0, readLength))
                                    }
                                    withStyle(SpanStyle(color = Color.White)) {
                                        append(sentence.text.substring(readLength))
                                    }
                                } else {
                                    withStyle(SpanStyle(color = Color.White)) { append(sentence.text) }
                                }
                            }
                        }
                    } else {
                        // Inactive: only re-builds when the sentence text itself changes (essentially never)
                        remember(sentence.text) {
                            buildAnnotatedString { append(sentence.text) }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onDoubleClick = {
                                    autoFollowEnabled = true
                                    onSentenceJump(idx)
                                },
                                onLongClick = { onLongPressBookmark(idx, sentence.text) }
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                lineHeight = 30.sp,
                                fontSize = 18.sp,
                                letterSpacing = 0.1.sp
                            ),
                            color = if (isActive) Color.White else Color.White.copy(alpha = 0.40f)
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
