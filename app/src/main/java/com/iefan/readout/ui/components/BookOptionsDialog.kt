package com.iefan.readout.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.window.Dialog
import com.iefan.readout.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BookOptionsDialog(
    document: Document,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(1.dp, Color(0xFF242426), RoundedCornerShape(24.dp)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Book header preview in dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Small cover preview
                    val localCoverBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = document.coverPath) {
                        value = withContext(Dispatchers.IO) {
                            document.coverPath?.let { path ->
                                try {
                                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 54.dp, height = 70.dp)
                            .clip(RoundedCornerShape(8.dp))
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
                            fontSize = 16.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (document.sourceUrl.isNullOrEmpty()) "Local Document" else document.sourceUrl,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Actions list
                val actions = listOf(
                    Triple(
                        if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (document.isFavorite) "Remove Favorite" else "Add to Favorites",
                        if (document.isFavorite) Color(0xFFFFD700) else Color.White
                    ),
                    Triple(Icons.Default.FolderOpen, "Add to Collection", Color.White),
                    Triple(Icons.Default.Edit, "Edit Details", Color.White),
                    Triple(Icons.Default.Delete, "Delete Book", Color(0xFFFF5252))
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    actions.forEachIndexed { index, (icon, text, color) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1E1E22))
                                .border(1.dp, Color(0xFF2E2E32).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .clickable {
                                    onDismiss()
                                    when (index) {
                                        0 -> onToggleFavorite()
                                        1 -> onAddToCollection()
                                        2 -> onEdit()
                                        3 -> onDelete()
                                    }
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = text,
                                tint = color,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}
