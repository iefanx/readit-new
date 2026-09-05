package com.iefan.readout.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A [ModalBottomSheet] that slides up when the user long-presses a sentence.
 * Lets the user name the bookmark before saving it.
 *
 * @param sentenceText  The raw sentence text (used to build a default label).
 * @param sentenceIndex The sentence index inside the document's parsed list.
 * @param charOffset    Character offset of the sentence (used for chapter positioning).
 * @param onConfirm     Called with the final label when the user taps "Save Bookmark".
 * @param onDismiss     Called when the sheet should be dismissed without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkActionSheet(
    sentenceText: String,
    sentenceIndex: Int,
    charOffset: Int,
    onConfirm: (label: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // Default label: first 60 chars of the sentence, trimmed
    val defaultLabel = remember(sentenceText) {
        sentenceText.trim().take(60).let { if (sentenceText.length > 60) "$it…" else it }
    }

    var label by remember { mutableStateOf(defaultLabel) }
    var saved by remember { mutableStateOf(false) }

    fun save() {
        if (label.isBlank()) return
        keyboardController?.hide()
        onConfirm(label.trim())
        saved = true
        scope.launch {
            delay(600)
            sheetState.hide()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        delay(300) // let the sheet animate in first
        focusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101012),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF38383C))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Header ───────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Animated icon: bookmark → bookmark-added
                Crossfade(targetState = saved, label = "bookmark_icon") { isSaved ->
                    Icon(
                        imageVector = if (isSaved) Icons.Default.BookmarkAdded else Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                }
                Column {
                    Text(
                        text = "Add Bookmark",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Give this moment a name",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp
                    )
                }
            }

            // ── Sentence preview ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF161619))
                    .border(1.dp, Color(0xFF24242A), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = sentenceText.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0B8),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Label input ──────────────────────────────────────────────────
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        text = "Bookmark name…",
                        color = Color(0xFF636366)
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF24242A),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = Color(0xFF161619),
                    unfocusedContainerColor = Color(0xFF161619)
                ),
                shape = RoundedCornerShape(14.dp)
            )

            // ── Actions ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        keyboardController?.hide()
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF161619),
                        contentColor = Color(0xFF9E9EA8)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF24242A))
                ) {
                    Text("Cancel", fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = ::save,
                    enabled = label.isNotBlank() && !saved,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledContentColor = Color.White.copy(alpha = 0.38f)
                    )
                ) {
                    Text(
                        text = if (saved) "Saved ✓" else "Save Bookmark",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
