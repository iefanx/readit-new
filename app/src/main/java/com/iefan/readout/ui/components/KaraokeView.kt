package com.iefan.readout.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.tts.SpeechSentence
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KaraokeView(
    sentences: List<SpeechSentence>,
    activeSentenceIndex: Int,
    currentWordRange: Pair<Int, Int>?,
    onSentenceJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Smooth scroll to keep active sentence centered in view
    LaunchedEffect(activeSentenceIndex) {
        if (sentences.isNotEmpty() && activeSentenceIndex >= 0 && activeSentenceIndex < sentences.size) {
            coroutineScope.launch {
                val targetIndex = (activeSentenceIndex - 2).coerceAtLeast(0)
                listState.animateScrollToItem(targetIndex)
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
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp), // extra bottom padding for the floating player capsule
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("karaoke_scroller")
            ) {
                itemsIndexed(sentences) { idx, sentence ->
                    val isActive = idx == activeSentenceIndex
                    
                    val annotatedText = remember(sentence, isActive, currentWordRange) {
                        buildAnnotatedString {
                            if (isActive && currentWordRange != null) {
                                val wordStart = currentWordRange.first
                                val wordEnd = currentWordRange.second

                                var lastIndex = sentence.start

                                // Find valid segments in the sentence words
                                for (word in sentence.words) {
                                    // Word overlaps or fully matches the current active range
                                    if (word.start >= lastIndex) {
                                        // Append text between last word and this word
                                        if (word.start > lastIndex) {
                                            val spaceText = sentence.text.substring(
                                                (lastIndex - sentence.start).coerceIn(0, sentence.text.length),
                                                (word.start - sentence.start).coerceIn(0, sentence.text.length)
                                            )
                                            append(spaceText)
                                        }

                                        // Now render word with highlight if within spoken range
                                        val isWordActivelySpoken = (word.start >= wordStart && word.end <= wordEnd) ||
                                                                   (wordStart >= word.start && wordStart < word.end)
                                        
                                        if (isWordActivelySpoken) {
                                            withStyle(
                                                SpanStyle(
                                                    background = Color(0xFFD0BCFF), // Lavender glowing spotlight
                                                    color = Color.Black,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            ) {
                                                append(word.text)
                                            }
                                        } else {
                                            append(word.text)
                                        }
                                        lastIndex = word.end
                                    }
                                }
                                
                                // Append any remaining sentence text
                                if (lastIndex - sentence.start < sentence.text.length) {
                                    append(sentence.text.substring(lastIndex - sentence.start))
                                }
                            } else {
                                append(sentence.text)
                            }
                        }
                    }

                    // Soft container backing with subtle border (glowing container style from screenshot)
                    val containerBg = if (isActive) {
                        Color(0xFF231D3A).copy(alpha = 0.55f) // Traslucent dark purple/blue from Screen 2
                    } else {
                        Color.Transparent
                    }

                    val containerBorderStroke = if (isActive) {
                        androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = Color(0xFF9575CD).copy(alpha = 0.5f)
                        )
                    } else {
                        null
                    }

                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(containerBg)
                        .combinedClickable(
                            onClick = {
                                // Quick visual cursor selection feedback
                            },
                            onDoubleClick = {
                                onSentenceJump(idx)
                            }
                        )

                    val finalModifier = if (containerBorderStroke != null) {
                        itemModifier.border(containerBorderStroke, RoundedCornerShape(10.dp))
                    } else {
                        itemModifier
                    }

                    Box(
                        modifier = finalModifier.padding(horizontal = 14.dp, vertical = 12.dp)
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
