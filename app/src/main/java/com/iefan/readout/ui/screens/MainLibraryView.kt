package com.iefan.readout.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import com.iefan.readout.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.iefan.readout.data.Document
import com.iefan.readout.data.CollectionEntity
import com.iefan.readout.data.DocumentCollectionCrossRef
import com.iefan.readout.ui.components.styleOfCaption
import com.iefan.readout.utils.CoverCache
import com.iefan.readout.utils.rememberHapticTrigger
import com.iefan.readout.ui.components.styleOfSubtitle

enum class AddInputType {
    FILE,
    PASTE,
    URL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLibraryView(
    allDocuments: List<Document>,
    allCollections: List<CollectionEntity>,
    allCrossRefs: List<DocumentCollectionCrossRef>,
    onSelectDocument: (Document) -> Unit,
    onDeleteDocument: (Document) -> Unit,
    onEditDocument: (Long, String, Uri?, Boolean) -> Unit = { _, _, _, _ -> },
    onAddDocument: (String, String, String?, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onToggleFavorite: (Document) -> Unit,
    onAddDocumentToCollection: (Long, Long) -> Unit,
    onRemoveDocumentFromCollection: (Long, Long) -> Unit,
    onCreateCollection: (String, Long?) -> Unit,
    onDeleteCollection: (CollectionEntity) -> Unit,
    onRenameCollection: (CollectionEntity, String) -> Unit,
    isImporting: Boolean = false,
    onUrlImport: (String, String?) -> Unit = { _, _ -> },
    onUriImport: (Uri, String?, Boolean) -> Unit = { _, _, _ -> },
    activeDocument: Document? = null,
    isPlaying: Boolean = false,
    progressFraction: Float = 0f,
    onTogglePlayback: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onExpandPlayer: () -> Unit = {},
    onCloseMiniPlayer: () -> Unit = {},
    onSeekToFraction: (Float) -> Unit = {},
    onReorderCollections: (List<Long>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticTrigger = rememberHapticTrigger()
    var showAddDialog by remember { mutableStateOf(false) }
    var activeInputType by remember { mutableStateOf(AddInputType.PASTE) }
    var collectionTargetDoc by remember { mutableStateOf<Document?>(null) }
    var renameTargetCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }
    var collectionToDelete by remember { mutableStateOf<CollectionEntity?>(null) }
    var documentToEdit by remember { mutableStateOf<Document?>(null) }
    var activeOptionsDoc by remember { mutableStateOf<Document?>(null) }

    val nonEvictCollections = remember(allCollections, allCrossRefs, allDocuments) {
        allCollections.map { col ->
            val docIds = allCrossRefs.filter { it.collectionId == col.id }.map { it.documentId }.toSet()
            col to allDocuments.filter { it.id in docIds }
        }.filter { it.second.isNotEmpty() }
    }
    var localCollections by remember(nonEvictCollections) {
        mutableStateOf(nonEvictCollections)
    }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // State management for raw picked uri
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    val context = LocalContext.current

    // Activity launcher for picker supporting multi-upload
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size == 1) {
                val uri = uris[0]
                selectedFileUri = uri
                var displayName = "Imported File"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
                selectedFileName = displayName
                activeInputType = AddInputType.FILE
                showAddDialog = true
            } else {
                // Multi-upload flow: import all selected files sequentially
                uris.forEach { uri ->
                    var displayName = "Imported File"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIndex)
                        }
                    }
                    val cleanTitle = displayName.substringBeforeLast(".")
                    onUriImport(uri, cleanTitle, false)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_logo),
                            contentDescription = "Readout Logo",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = "Readout",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = 0.5.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        hapticTrigger()
                        onOpenLibrary()
                    }) {
                        Icon(
                            imageVector = Icons.Default.LibraryBooks,
                            contentDescription = "Library",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        hapticTrigger()
                        onOpenSettings()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Readout Settings",
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Unified Action buttons side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // File Action Card (triggers Activity file picker)
                        ActionCard(
                            title = "File",
                            icon = Icons.Default.NoteAdd,
                            onClick = { 
                                hapticTrigger()
                                fileLauncher.launch("*/*")
                            },
                            modifier = Modifier.weight(1f)
                        )
                        // Paste Action Card
                        ActionCard(
                            title = "Paste",
                            icon = Icons.Default.ContentPaste,
                            onClick = { 
                                hapticTrigger()
                                selectedFileUri = null
                                activeInputType = AddInputType.PASTE
                                showAddDialog = true 
                            },
                            modifier = Modifier.weight(1f)
                        )
                        // Link Action Card
                        ActionCard(
                            title = "Link",
                            icon = Icons.Default.Link,
                            onClick = { 
                                hapticTrigger()
                                selectedFileUri = null
                                activeInputType = AddInputType.URL
                                showAddDialog = true 
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    // Heading 2: Continue Reading
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Continue Reading",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        if (allDocuments.isEmpty()) {
                            // Empty State card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF111113))
                                    .padding(vertical = 40.dp, horizontal = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = "Empty",
                                        tint = Color.DarkGray,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Your library is clean & empty",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Load an article preset or paste text above to start",
                                        fontSize = 11.sp,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        } else {
                            // Scrollable Row of Documents styled beautifully as Cover sheets
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recent_reads_row")
                            ) {
                                items(allDocuments, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = {
                                            hapticTrigger()
                                            onSelectDocument(doc)
                                        },
                                        onLongSelect = {
                                            hapticTrigger()
                                            activeOptionsDoc = doc
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val favoriteDocs = allDocuments.filter { it.isFavorite }
                if (favoriteDocs.isNotEmpty()) {
                    item {
                        // Heading 3: Favorites
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Favorites",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            // Scrollable Row of Documents styled beautifully as Cover sheets
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(favoriteDocs, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = {
                                            hapticTrigger()
                                            onSelectDocument(doc)
                                        },
                                        onLongSelect = {
                                            hapticTrigger()
                                            activeOptionsDoc = doc
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (localCollections.isNotEmpty()) {
                    itemsIndexed(localCollections, key = { _, pair -> pair.first.id }) { index, (col, colDocs) ->
                        val isDraggingThis = draggedIndex == index
                        val translationY = if (isDraggingThis) dragOffsetY else 0f

                        val currentIndex = rememberUpdatedState(index)
                        val currentCollections = rememberUpdatedState(localCollections)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    this.translationY = translationY
                                    this.scaleX = if (isDraggingThis) 1.02f else 1.0f
                                    this.scaleY = if (isDraggingThis) 1.02f else 1.0f
                                    this.shadowElevation = if (isDraggingThis) 8.dp.toPx() else 0f
                                }
                                .background(if (isDraggingThis) Color(0xFF141416) else Color.Transparent)
                                .pointerInput(col.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            draggedIndex = currentIndex.value
                                            dragOffsetY = 0f
                                            hapticTrigger()
                                        },
                                        onDragEnd = {
                                            onReorderCollections(currentCollections.value.map { it.first.id })
                                            draggedIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            onReorderCollections(currentCollections.value.map { it.first.id })
                                            draggedIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y

                                            val currentDragIdx = draggedIndex
                                            if (currentDragIdx != null) {
                                                val threshold = 230.dp.toPx()
                                                var targetIdx = currentDragIdx
                                                if (dragOffsetY > threshold / 2 && currentDragIdx < currentCollections.value.lastIndex) {
                                                    targetIdx = currentDragIdx + 1
                                                    dragOffsetY -= threshold
                                                } else if (dragOffsetY < -threshold / 2 && currentDragIdx > 0) {
                                                    targetIdx = currentDragIdx - 1
                                                    dragOffsetY += threshold
                                                }

                                                if (targetIdx != currentDragIdx) {
                                                    val list = currentCollections.value.toMutableList()
                                                    val item = list.removeAt(currentDragIdx)
                                                    list.add(targetIdx, item)
                                                    localCollections = list
                                                    draggedIndex = targetIdx
                                                    hapticTrigger()
                                                }
                                            }
                                        }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = col.name,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Box {
                                    var showColMenu by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = {
                                            hapticTrigger()
                                            showColMenu = true
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Collection Options",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showColMenu,
                                        onDismissRequest = { showColMenu = false },
                                        modifier = Modifier.background(Color(0xFF1D1D20))
                                    ) {
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename",
                                                    tint = Color.White
                                                )
                                            },
                                            text = { Text("Rename", color = Color.White) },
                                            onClick = {
                                                hapticTrigger()
                                                renameTargetCollection = col
                                                showColMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = Color.Red
                                                )
                                            },
                                            text = { Text("Delete", color = Color.Red) },
                                            onClick = {
                                                hapticTrigger()
                                                collectionToDelete = col
                                                showColMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(colDocs, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = {
                                            hapticTrigger()
                                            onSelectDocument(doc)
                                        },
                                        onLongSelect = {
                                            hapticTrigger()
                                            activeOptionsDoc = doc
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(95.dp))
                }
            }

            if (activeDocument != null) {
                MiniPlayer(
                    document = activeDocument,
                    isPlaying = isPlaying,
                    progressFraction = progressFraction,
                    onTogglePlayback = onTogglePlayback,
                    onExpand = onExpandPlayer,
                    onClose = onCloseMiniPlayer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .width(330.dp)
                        .height(72.dp)
                )
            }
        }

        if (showAddDialog) {
            AddDocumentDialog(
                initialType = activeInputType,
                selectedUri = selectedFileUri,
                selectedFileName = selectedFileName,
                onDismiss = { showAddDialog = false },
                onAdd = { title, content, url ->
                    onAddDocument(title, content, url, null)
                    showAddDialog = false
                },
                onUrlImport = { url, title ->
                    onUrlImport(url, title)
                    showAddDialog = false
                },
                onUriImport = { uri, title ->
                    onUriImport(uri, title, true)
                    showAddDialog = false
                }
            )
        }

        // Beautiful full-screen loader when background parsing is in progress
        if (isImporting) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = {},
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Ingesting & Analyzing Content...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Extracting clean text structure and loading high-fidelity vocal preview...",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                },
                containerColor = Color(0xFF141416),
                shape = RoundedCornerShape(24.dp)
            )
        }

        collectionTargetDoc?.let { doc ->
            CollectionAssignDialog(
                document = doc,
                allCollections = allCollections,
                allCrossRefs = allCrossRefs,
                onDismiss = { collectionTargetDoc = null },
                onAddRelation = { colId -> onAddDocumentToCollection(doc.id, colId) },
                onRemoveRelation = { colId -> onRemoveDocumentFromCollection(doc.id, colId) },
                onCreateCollection = { name -> onCreateCollection(name, doc.id) },
                onDeleteCollection = onDeleteCollection
            )
        }

        renameTargetCollection?.let { col ->
            var textVal by remember(col) { mutableStateOf(col.name) }
            AlertDialog(
                onDismissRequest = { renameTargetCollection = null },
                title = {
                    Text(
                        text = "Rename Collection",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    OutlinedTextField(
                        value = textVal,
                        onValueChange = { textVal = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF242426),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (textVal.isNotBlank()) {
                                onRenameCollection(col, textVal.trim())
                                renameTargetCollection = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Rename", color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { renameTargetCollection = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1D1D20)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242426)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF141416),
                shape = RoundedCornerShape(24.dp)
            )
        }

        documentToDelete?.let { doc ->
            AlertDialog(
                onDismissRequest = { documentToDelete = null },
                title = { Text("Delete Document") },
                text = { Text("Are you sure you want to permanently delete \"${doc.title}\"? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteDocument(doc)
                            documentToDelete = null
                        }
                    ) {
                        Text("Delete", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { documentToDelete = null }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF141416),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray,
                shape = RoundedCornerShape(24.dp)
            )
        }

        collectionToDelete?.let { col ->
            AlertDialog(
                onDismissRequest = { collectionToDelete = null },
                title = { Text("Delete Collection") },
                text = { Text("Are you sure you want to delete the collection \"${col.name}\"? The files inside this collection will not be deleted.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteCollection(col)
                            collectionToDelete = null
                        }
                    ) {
                        Text("Delete", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { collectionToDelete = null }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF141416),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray,
                shape = RoundedCornerShape(24.dp)
            )
        }

        documentToEdit?.let { doc ->
            com.iefan.readout.ui.components.EditBookDetailsDialog(
                document = doc,
                onDismiss = { documentToEdit = null },
                onSave = { newTitle, newCoverUri, removeCover ->
                    onEditDocument(doc.id, newTitle, newCoverUri, removeCover)
                    documentToEdit = null
                }
            )
        }

        activeOptionsDoc?.let { doc ->
            com.iefan.readout.ui.components.BookOptionsDialog(
                document = doc,
                onDismiss = { activeOptionsDoc = null },
                onToggleFavorite = { onToggleFavorite(doc) },
                onAddToCollection = { collectionTargetDoc = doc },
                onEdit = { documentToEdit = doc },
                onDelete = { documentToDelete = doc }
            )
        }
    }
}

@Composable
fun ActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(72.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF141416)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242426))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    document: Document,
    onSelect: () -> Unit,
    onLongSelect: () -> Unit,
    cardWidth: Dp = 135.dp,
    cardHeight: Dp = 175.dp
) {
    val isPastedText = document.sourceUrl.isNullOrEmpty() || document.title.lowercase().contains("paste")
    val isLink = document.sourceUrl?.startsWith("http", ignoreCase = true) == true ||
                 document.sourceUrl?.startsWith("www.", ignoreCase = true) == true

    val positionPercentage = if (document.contentLength > 0) {
        (document.playbackPosition.getOrZeroPercent() * 100 / document.contentLength).coerceIn(0, 100)
    } else 0

    // Load cover bitmap from coverPath asynchronously, utilizing CoverCache
    val cachedBitmap = remember(document.coverPath) {
        document.coverPath?.let { CoverCache.get(it) }
    }

    val localCoverBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = cachedBitmap, key1 = document.coverPath) {
        if (cachedBitmap == null) {
            value = document.coverPath?.let { path ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(path)?.asImageBitmap()
                        if (bitmap != null) {
                            CoverCache.put(path, bitmap)
                        }
                        bitmap
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }

    // Curated elegant gradients for default covers based on title hash
    val coverGradients = remember {
        listOf(
            listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)), // Sapphire Navy
            listOf(Color(0xFF0F1E36), Color(0xFF1E293B)), // Slate Steel
            listOf(Color(0xFF1E1B4B), Color(0xFF312E81)), // Twilight Midnight
            listOf(Color(0xFF0F2027), Color(0xFF2C5364)), // Deep Ocean Teal
            listOf(Color(0xFF022C22), Color(0xFF064E3B))  // Hunter Emerald
        )
    }

    val gradientIndex = remember(document.title) {
        Math.abs(document.title.hashCode()) % coverGradients.size
    }
    val coverGradient = coverGradients[gradientIndex]

    Column(
        modifier = Modifier
            .width(cardWidth)
            .padding(bottom = 8.dp)
    ) {
        // Document Cover Container
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = onLongSelect
                )
                .testTag("document_card_${document.id}")
        ) {
            val coverBitmap = cachedBitmap ?: localCoverBitmap
            if (coverBitmap != null) {
                // Real Extracted Vector/PDF Thumbnail Preview Cover
                Image(
                    bitmap = coverBitmap,
                    contentDescription = "Document Cover Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Typographic, highly premium minimalist cover design
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(coverGradient))
                        .padding(14.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top: Elegant typeset title with top padding
                        Text(
                            text = document.title,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.95f),
                            lineHeight = 16.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        // Bottom Left: Document type icon
                        val iconVector = when {
                            isPastedText -> Icons.Default.ContentPaste
                            isLink -> Icons.Default.Link
                            else -> Icons.Default.MenuBook
                        }
                        Icon(
                            imageVector = iconVector,
                            contentDescription = "Type",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Visual Progress Strip at the bottom of the cover
            val percentage = if (document.contentLength > 0) {
                (document.playbackPosition.getOrZeroPercent().toFloat() / document.contentLength.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.DarkGray.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(percentage)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Document Details beneath the cover card
        Text(
            text = document.title,
            maxLines = 2,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp
        )
    }
}

@Composable
fun AddDocumentDialog(
    initialType: AddInputType,
    selectedUri: Uri?,
    selectedFileName: String,
    onDismiss: () -> Unit,
    onAdd: (String, String, String?) -> Unit,
    onUrlImport: (String, String?) -> Unit,
    onUriImport: (Uri, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(initialType) }

    // Set initial title when a file is picked (replacing underscores/hyphens with spaces and capitalizing)
    LaunchedEffect(selectedUri, selectedFileName) {
        if (selectedUri != null && currentTab == AddInputType.FILE) {
            val cleanName = selectedFileName.substringBeforeLast(".")
            val spaced = cleanName.replace(Regex("[_\\-]+"), " ")
            title = spaced.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Import Document",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White
                )
                
                // Tab Selection headers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Color(0xFF1D1D21))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val tabs = listOf(
                        AddInputType.FILE to "File",
                        AddInputType.PASTE to "Paste Text",
                        AddInputType.URL to "Link / URL"
                    )
                    for ((tab, label) in tabs) {
                        val isSelected = currentTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(17.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { currentTab = tab },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Color.Gray
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (currentTab) {
                    AddInputType.FILE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1B1B1E))
                                    .border(1.dp, Color(0xFF333339), RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Attachment,
                                        contentDescription = "File attached",
                                        tint = Color(0xFF9E82F5)
                                    )
                                    Text(
                                        text = selectedFileName.ifBlank { "No File Chosen" },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedUri != null) Color.White else Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Document Title") },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF333339)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_title"),
                                singleLine = true
                            )
                        }
                    }
                    AddInputType.PASTE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Preload Sample Fast buttons
                            Text(
                                text = "QUICK LOAD PRESETS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        title = "The Magic of Local AI"
                                        content = """For years, running large neural network architectures meant piping personal parameters back and forth to giant server racks hosted in centralized clouds. Today, modern micro-onnx ran pipelines make running speech synthesizers directly inside your pocket fully real. 

This is incredibly important for mobile computers, meaning zero networking costs, absolute tracking privacy, and uninterrupted playback inside planes or subway commutes."""
                                        url = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Edge AI Preset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        title = "Mindfulness & Flow State"
                                        content = """Achieving professional mastery is rarely about running faster; it is about learning how to calm the noise. Flow states happen when there is a perfect equilibrium between the difficulty of a challenge and your absolute dedicated focus. 

By eliminating the constant visual notifications of modern computers and turning text-heavy articles into an elegant audio-stream, you can consume long-form thinking while resting your eyes and keeping the mind in a deep flow channel."""
                                        url = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Flow State Preset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFF1D1D21))

                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Document Title") },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF333339)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_title"),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("Article Content / Raw Text") },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF333339)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .testTag("doc_add_content"),
                                maxLines = 10
                            )
                        }
                    }
                    AddInputType.URL -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Paste a URL bookmark from Wikipedia or standard web articles to extract full-text content.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                            
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("Article URL / Address") },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF333339)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_url"),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Document Title (Optional/Auto)") },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF333339)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_title"),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    when (currentTab) {
                        AddInputType.FILE -> {
                            if (selectedUri != null) {
                                onUriImport(selectedUri, title)
                            }
                        }
                        AddInputType.PASTE -> {
                            if (content.isNotBlank()) {
                                onAdd(title, content, null)
                            }
                        }
                        AddInputType.URL -> {
                            if (url.isNotBlank()) {
                                onUrlImport(url, title.ifBlank { null })
                            }
                        }
                    }
                },
                enabled = when (currentTab) {
                    AddInputType.FILE -> selectedUri != null
                    AddInputType.PASTE -> content.isNotBlank()
                    AddInputType.URL -> url.isNotBlank()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
                modifier = Modifier.testTag("doc_submit_add_btn")
            ) {
                Text("Ingest & Synthesize", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray)
            ) {
                Text("Cancel", fontSize = 12.sp)
            }
        },
        containerColor = Color(0xFF141416),
        shape = RoundedCornerShape(24.dp)
    )
}

private fun Int.getOrZeroPercent(): Int = if (this < 0) 0 else this

@Composable
private fun SeekableProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = Color.White.copy(alpha = 0.15f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
    ) {
        val h = size.height
        val centerY = h / 2
        val strokeWidthVal = 4.dp.toPx()
        val thumbRadius = 6.dp.toPx()

        // Draw track
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = strokeWidthVal,
            cap = StrokeCap.Round
        )

        // Draw progress
        val progressX = progress * size.width
        if (progressX > 0f) {
            drawLine(
                color = primaryColor,
                start = Offset(0f, centerY),
                end = Offset(progressX, centerY),
                strokeWidth = strokeWidthVal,
                cap = StrokeCap.Round
            )
        }

        // Draw thumb circle
        drawCircle(
            color = primaryColor,
            radius = thumbRadius,
            center = Offset(progressX, centerY)
        )
    }
}

@Composable
fun MiniPlayer(
    document: Document,
    isPlaying: Boolean,
    progressFraction: Float,
    onTogglePlayback: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticTrigger = rememberHapticTrigger()
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(36.dp))
                .background(Color(0xD9141416))
                .border(1.dp, Color(0x99242426), RoundedCornerShape(36.dp))
                .clickable {
                    hapticTrigger()
                    onExpand()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cachedBitmap = remember(document.coverPath) {
                    document.coverPath?.let { CoverCache.get(it) }
                }

                val localCoverBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = cachedBitmap, key1 = document.coverPath) {
                    if (cachedBitmap == null) {
                        value = document.coverPath?.let { path ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val bitmap = BitmapFactory.decodeFile(path)?.asImageBitmap()
                                    if (bitmap != null) {
                                        CoverCache.put(path, bitmap)
                                    }
                                    bitmap
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                    }
                }
                
                // Gradient palettes matching DocumentCard for visual consistency
                val miniGradients = remember {
                    listOf(
                        listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)),
                        listOf(Color(0xFF0F1E36), Color(0xFF1E293B)),
                        listOf(Color(0xFF1E1B4B), Color(0xFF312E81)),
                        listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
                        listOf(Color(0xFF022C22), Color(0xFF064E3B))
                    )
                }
                val miniGradient = miniGradients[Math.abs(document.title.hashCode()) % miniGradients.size]

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(21.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val coverBitmap = cachedBitmap ?: localCoverBitmap
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = "Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(miniGradient))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = document.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    val percentage = (progressFraction * 100).toInt().coerceIn(0, 100)
                    val wordsRemaining = remember(document.contentLength, progressFraction) {
                        val totalWords = document.contentLength / 6
                        ((1f - progressFraction) * totalWords).toInt().coerceAtLeast(0)
                    }
                    val timeRemainingStr = remember(wordsRemaining, document.playbackSpeed) {
                        val speed = if (document.playbackSpeed > 0f) document.playbackSpeed else 1.0f
                        val totalSeconds = (wordsRemaining / (2.5f * speed)).toInt()
                        if (totalSeconds <= 0) {
                            "0s remaining"
                        } else if (totalSeconds < 60) {
                            "${totalSeconds}s remaining"
                        } else {
                            val totalMinutes = totalSeconds / 60
                            if (totalMinutes < 60) {
                                "${totalMinutes}m remaining"
                            } else {
                                val hours = totalMinutes / 60
                                val mins = totalMinutes % 60
                                if (mins > 0) {
                                    "${hours}h ${mins}m remaining"
                                } else {
                                    "${hours}h remaining"
                                }
                            }
                        }
                    }
                    Text(
                        text = "$percentage% / $timeRemainingStr",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .clickable {
                            hapticTrigger()
                            onTogglePlayback()
                        }
                        .testTag("mini_play_pause_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = {
                        hapticTrigger()
                        onClose()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Player",
                        tint = Color.Gray.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
