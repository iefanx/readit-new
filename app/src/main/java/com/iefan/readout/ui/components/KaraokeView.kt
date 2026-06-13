package com.iefan.readout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.tts.SpeechSentence
import kotlinx.coroutines.launch

@Composable
fun KaraokeView(
    sentences: List<SpeechSentence>,
    activeSentenceIndex: Int,
    currentWordRange: Pair<Int, Int>?,
    onSentenceJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeightPx by remember { mutableStateOf(0) }

    // Precompute character offsets of each sentence in the combined document text
    val sentenceRanges = remember(sentences) {
        var currentLength = 0
        sentences.map { sentence ->
            val start = currentLength
            val end = start + sentence.text.length
            currentLength += sentence.text.length + 1 // +1 for the space separator
            start to end
        }
    }

    // Smooth scroll to keep the active sentence centered in the viewport
    LaunchedEffect(activeSentenceIndex, textLayoutResult, viewportHeightPx) {
        val layoutResult = textLayoutResult
        if (sentences.isNotEmpty() && activeSentenceIndex >= 0 && activeSentenceIndex < sentences.size && layoutResult != null) {
            val range = sentenceRanges.getOrNull(activeSentenceIndex)
            if (range != null) {
                val startOffset = range.first
                val line = layoutResult.getLineForOffset(startOffset)
                val lineTop = layoutResult.getLineTop(line)
                val lineBottom = layoutResult.getLineBottom(line)
                
                val targetScroll = if (viewportHeightPx > 0) {
                    val elementCenter = (lineTop + lineBottom) / 2f
                    val desiredScroll = elementCenter - (viewportHeightPx / 2f)
                    desiredScroll.coerceIn(0f, (layoutResult.size.height - viewportHeightPx).coerceAtLeast(0).toFloat())
                } else {
                    (lineTop - 200f).coerceAtLeast(0f)
                }
                
                coroutineScope.launch {
                    scrollState.animateScrollTo(targetScroll.toInt())
                }
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
            // Build the single continuous annotated document text
            val annotatedText = remember(sentences, activeSentenceIndex, currentWordRange, sentenceRanges) {
                buildAnnotatedString {
                    sentences.forEachIndexed { idx, sentence ->
                        val isActive = idx == activeSentenceIndex
                        
                        if (isActive) {
                            // Style for active sentence text (background highlight drawn below in Modifier.drawBehind)
                            withStyle(
                                SpanStyle(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                if (currentWordRange != null) {
                                    val wordStart = currentWordRange.first
                                    val wordEnd = currentWordRange.second
                                    
                                    var lastIndex = sentence.start
                                    
                                    for (word in sentence.words) {
                                        if (word.start >= lastIndex) {
                                            if (word.start > lastIndex) {
                                                val spaceText = sentence.text.substring(
                                                    (lastIndex - sentence.start).coerceIn(0, sentence.text.length),
                                                    (word.start - sentence.start).coerceIn(0, sentence.text.length)
                                                )
                                                append(spaceText)
                                            }
                                            
                                            val isWordActivelySpoken = (word.start >= wordStart && word.end <= wordEnd) ||
                                                                       (wordStart >= word.start && wordStart < word.end)
                                            
                                            if (isWordActivelySpoken) {
                                                withStyle(
                                                    SpanStyle(
                                                        background = Color(0xFFD0BCFF), // Bright lavender spotlight for the spoken word
                                                        color = Color.Black,
                                                        fontWeight = FontWeight.Black
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
                                    
                                    if (lastIndex - sentence.start < sentence.text.length) {
                                        append(sentence.text.substring(lastIndex - sentence.start))
                                    }
                                } else {
                                    append(sentence.text)
                                }
                            }
                        } else {
                            // Style for inactive text (dimmed)
                            withStyle(
                                SpanStyle(
                                    color = Color.White.copy(alpha = 0.40f)
                                )
                            ) {
                                append(sentence.text)
                            }
                        }
                        append(" ")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .onGloballyPositioned { coordinates ->
                        viewportHeightPx = coordinates.size.height
                    }
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 160.dp) // bottom padding allows scrolling past floating capsule
            ) {
                Text(
                    text = annotatedText,
                    onTextLayout = { textLayoutResult = it },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, // Elegant serif layout
                        lineHeight = 34.sp,
                        fontSize = 19.sp,
                        letterSpacing = 0.1.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            textLayoutResult?.let { layoutResult ->
                                val range = sentenceRanges.getOrNull(activeSentenceIndex) ?: return@drawBehind
                                val startOffset = range.first
                                val endOffset = range.second
                                
                                val startLine = layoutResult.getLineForOffset(startOffset)
                                val endLine = layoutResult.getLineForOffset(endOffset)
                                
                                val highlightColor = Color(0x664F26C4) // Translucent violet/indigo highlight background (40% opacity)
                                
                                for (line in startLine..endLine) {
                                    val top = layoutResult.getLineTop(line)
                                    val bottom = layoutResult.getLineBottom(line)
                                    
                                    val left = if (line == startLine) {
                                        layoutResult.getHorizontalPosition(startOffset, true)
                                    } else {
                                        layoutResult.getLineLeft(line)
                                    }
                                    
                                    val right = if (line == endLine) {
                                        layoutResult.getHorizontalPosition(endOffset, true)
                                    } else {
                                        layoutResult.getLineRight(line)
                                    }
                                    
                                    val xStart = minOf(left, right)
                                    val xEnd = maxOf(left, right)
                                    
                                    val padX = 6.dp.toPx()
                                    val padY = 2.dp.toPx()
                                    
                                    if (xEnd > xStart) {
                                        drawRoundRect(
                                            color = highlightColor,
                                            topLeft = androidx.compose.ui.geometry.Offset(xStart - padX, top - padY),
                                            size = androidx.compose.ui.geometry.Size(xEnd - xStart + 2 * padX, bottom - top + 2 * padY),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                        .pointerInput(sentenceRanges, onSentenceJump) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    textLayoutResult?.let { layoutResult ->
                                        val charIndex = layoutResult.getOffsetForPosition(offset)
                                        val clickedSentenceIdx = sentenceRanges.indexOfFirst { range ->
                                            charIndex >= range.first && charIndex < range.second
                                        }
                                        if (clickedSentenceIdx != -1) {
                                            onSentenceJump(clickedSentenceIdx)
                                        }
                                    }
                                }
                            )
                        }
                )
            }
        }
    }
}

// Ext helper styles to avoid code repetition (used by ActivePlayerView)
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
