package com.iefan.readout.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.tts.SpeechSentence
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

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            autoFollowEnabled = true
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collectLatest {
                if (!isAutoScrolling) {
                    autoFollowEnabled = false
                }
            }
    }

    LaunchedEffect(activeSentenceIndex, autoFollowEnabled, sentences) {
        if (!autoFollowEnabled || activeSentenceIndex !in sentences.indices) return@LaunchedEffect

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val firstVisible = visibleItems.firstOrNull()?.index ?: -1
        val lastVisible = visibleItems.lastOrNull()?.index ?: -1
        val comfortablyVisible = activeSentenceIndex in (firstVisible + 1)..(lastVisible - 1)
        if (comfortablyVisible) return@LaunchedEffect

        isAutoScrolling = true
        try {
            listState.animateScrollToItem(index = maxOf(activeSentenceIndex - 1, 0))
        } finally {
            isAutoScrolling = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (sentences.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
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
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 160.dp), // extra bottom padding for the floating player capsule
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("karaoke_scroller")
            ) {
                itemsIndexed(sentences) { idx, sentence ->
                    val isActive = idx == activeSentenceIndex
                    
                    val annotatedText = remember(sentence, isActive, currentWordRange, isTranslating) {
                        buildAnnotatedString {
                            if (isActive && currentWordRange != null && !isTranslating) {
                                val wordEnd = currentWordRange.second
                                val readLength = (wordEnd - sentence.start).coerceIn(0, sentence.text.length)

                                val readText = sentence.text.substring(0, readLength)
                                val remainingText = sentence.text.substring(readLength)

                                withStyle(
                                    SpanStyle(
                                        background = Color(0xFF2E43FA),
                                        color = Color.White
                                    )
                                ) {
                                    append(readText)
                                }
                                withStyle(
                                    SpanStyle(
                                        color = Color.White
                                    )
                                ) {
                                    append(remainingText)
                                }
                            } else if (isActive && !isTranslating) {
                                // Sentence is active but no word range yet. Show all text white.
                                withStyle(SpanStyle(color = Color.White)) {
                                    append(sentence.text)
                                }
                            } else {
                                append(sentence.text)
                            }
                        }
                    }

                    // Distraction-free modifier: no container background, no border, simple click/double-click action
                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onDoubleClick = {
                                autoFollowEnabled = true
                                onSentenceJump(idx)
                            },
                            onLongClick = {
                                onLongPressBookmark(idx, sentence.text)
                            }
                        )

                    Box(
                        modifier = itemModifier.padding(vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = annotatedText,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, // elegant serif layout
                                    lineHeight = 30.sp,
                                    fontSize = 18.sp,
                                    letterSpacing = 0.1.sp
                                ),
                                color = if (isActive) {
                                    Color.White // high emphasis
                                } else {
                                    Color.White.copy(alpha = 0.40f) // dimmed for inactive lines
                                }
                            )
                        }
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
