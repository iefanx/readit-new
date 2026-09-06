package com.iefan.readout.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.data.Document
import com.iefan.readout.utils.rememberHapticTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOptionsDialog(
    document: Document,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val hapticTrigger = rememberHapticTrigger()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101012),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color(0xFF38383C))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            // Book header preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover preview
                val localCoverBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = document.coverPath) {
                    value = withContext(Dispatchers.IO) {
                        document.coverPath?.let { path ->
                            try {
                                com.iefan.readout.utils.BitmapOptimizer.decodeSampledBitmapFromFile(path, 180, 240)?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 74.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF242426)),
                    contentAlignment = Alignment.Center
                ) {
                    if (localCoverBitmap != null) {
                        Image(
                            bitmap = localCoverBitmap!!,
                            contentDescription = "Cover thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Default cover",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = document.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val charCount = document.contentLength.takeIf { it > 0 } ?: document.content.length
                    val wordsCount = charCount / 5
                    val sourceText = if (!document.sourceUrl.isNullOrEmpty()) {
                        document.sourceUrl
                    } else {
                        "Local Book · ~$wordsCount words"
                    }
                    Text(
                        text = sourceText,
                        fontSize = 13.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF24242A), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Action Items
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 1. Favorite
                OptionActionRow(
                    icon = if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    iconTint = if (document.isFavorite) Color(0xFFFFD700) else Color.White,
                    title = if (document.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    onClick = {
                        hapticTrigger()
                        onDismiss()
                        onToggleFavorite()
                    }
                )

                // 2. Collection
                OptionActionRow(
                    icon = Icons.Default.FolderOpen,
                    iconTint = Color.White,
                    title = "Add to Collection",
                    onClick = {
                        hapticTrigger()
                        onDismiss()
                        onAddToCollection()
                    }
                )

                // 3. Edit
                OptionActionRow(
                    icon = Icons.Default.Edit,
                    iconTint = Color.White,
                    title = "Edit Title & Cover",
                    onClick = {
                        hapticTrigger()
                        onDismiss()
                        onEdit()
                    }
                )

                // 4. Delete (Destructive)
                OptionActionRow(
                    icon = Icons.Default.DeleteOutline,
                    iconTint = Color(0xFFFF5252),
                    title = "Delete Book",
                    textColor = Color(0xFFFF5252),
                    containerColor = Color(0x18FF5252),
                    borderColor = Color(0x33FF5252),
                    onClick = {
                        hapticTrigger()
                        onDismiss()
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    textColor: Color = Color.White,
    containerColor: Color = Color(0xFF161619),
    borderColor: Color = Color(0xFF24242A),
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
