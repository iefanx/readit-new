package com.iefan.readout.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.iefan.readout.viewmodel.ImportTaskProgress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import com.iefan.readout.utils.CoverPreviewHelper
import com.iefan.readout.utils.DeviceScannerSheet
import com.iefan.readout.utils.rememberHapticTrigger
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import com.iefan.readout.ui.components.styleOfSubtitle

enum class DocumentFormatType(val label: String, val badgeText: String) {
    BOOK("Book", "BOOK"),
    DOCUMENT("Document", "DOC"),
    TEXT("Text", "TEXT"),
    WEB_ARTICLE("Web Article", "WEB")
}

data class DocumentClassification(
    val formatType: DocumentFormatType,
    val displayLabel: String,
    val badgeText: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val detailText: String
)

fun getDocumentClassification(document: Document): DocumentClassification {
    val sUrl = document.sourceUrl?.trim()
    val titleLower = document.title.lowercase()

    // 1. Web Link check
    val isLink = sUrl?.startsWith("http://", ignoreCase = true) == true ||
            sUrl?.startsWith("https://", ignoreCase = true) == true ||
            sUrl?.startsWith("www.", ignoreCase = true) == true
    if (isLink) {
        val hostLabel = try {
            val uriHost = java.net.URI(sUrl).host
            uriHost?.removePrefix("www.")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
        return DocumentClassification(
            formatType = DocumentFormatType.WEB_ARTICLE,
            displayLabel = "Web Article",
            badgeText = "WEB",
            icon = Icons.Default.Link,
            detailText = hostLabel ?: "Web Article"
        )
    }

    // 2. Pasted Text check
    val isPasted = sUrl.isNullOrEmpty() ||
            sUrl.equals("Pasted Text", ignoreCase = true) ||
            sUrl.equals("Shared Text", ignoreCase = true) ||
            titleLower.contains("pasted text") ||
            titleLower.contains("paste")
    if (isPasted) {
        return DocumentClassification(
            formatType = DocumentFormatType.TEXT,
            displayLabel = "Text",
            badgeText = "TEXT",
            icon = Icons.Default.ContentPaste,
            detailText = "Text"
        )
    }

    // 3. EPUB Book check
    val isEpub = sUrl.endsWith(".epub", ignoreCase = true) ||
            sUrl.equals("Homer", ignoreCase = true) ||
            titleLower.endsWith(".epub") ||
            titleLower.contains("the odyssey") ||
            document.coverPath?.contains("epub", ignoreCase = true) == true
    if (isEpub) {
        return DocumentClassification(
            formatType = DocumentFormatType.BOOK,
            displayLabel = "Book",
            badgeText = "BOOK",
            icon = Icons.AutoMirrored.Filled.MenuBook,
            detailText = "Book"
        )
    }

    // 4. File Document check (PDF, DOCX, TXT, HTML, etc.)
    val isPdf = sUrl.endsWith(".pdf", ignoreCase = true) ||
            titleLower.endsWith(".pdf") ||
            document.coverPath?.contains("pdf", ignoreCase = true) == true
    val badge = if (isPdf) "PDF" else "DOC"
    val icon = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Description
    return DocumentClassification(
        formatType = DocumentFormatType.DOCUMENT,
        displayLabel = "Document",
        badgeText = badge,
        icon = icon,
        detailText = "Document"
    )
}

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
    onAddDocument: (String, String, String?, String?, Boolean, Long?) -> Unit = { _, _, _, _, _, _ -> },
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onToggleFavorite: (Document) -> Unit,
    onAddDocumentToCollection: (Long, Long) -> Unit,
    onRemoveDocumentFromCollection: (Long, Long) -> Unit,
    onCreateCollection: (String, Long?, ((Long) -> Unit)?) -> Unit = { _, _, _ -> },
    onDeleteCollection: (CollectionEntity) -> Unit,
    onRenameCollection: (CollectionEntity, String) -> Unit,
    onCancelImport: () -> Unit = {},
    isImporting: Boolean = false,
    importProgress: ImportTaskProgress = ImportTaskProgress(),
    onBatchImport: ((List<SelectedDocumentDraft>) -> Unit)? = null,
    onUrlImport: (String, String?, Uri?, Boolean, Long?) -> Unit = { _, _, _, _, _ -> },
    onUriImport: (Uri, String?, Boolean, Uri?, Boolean, Long?) -> Unit = { _, _, _, _, _, _ -> },
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
    var showDeviceScanner by remember { mutableStateOf(false) }
    var initialScannedDrafts by remember { mutableStateOf<List<SelectedDocumentDraft>>(emptyList()) }
    var activeInputType by remember { mutableStateOf(AddInputType.PASTE) }
    var collectionTargetDoc by remember { mutableStateOf<Document?>(null) }
    var renameTargetCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }
    var collectionToDelete by remember { mutableStateOf<CollectionEntity?>(null) }
    var documentToEdit by remember { mutableStateOf<Document?>(null) }
    var activeOptionsDoc by remember { mutableStateOf<Document?>(null) }

    val recentReadsListState = rememberLazyListState()
    var previousDocCount by remember { mutableIntStateOf(allDocuments.size) }

    LaunchedEffect(allDocuments.size) {
        if (allDocuments.size > previousDocCount) {
            recentReadsListState.animateScrollToItem(0)
        }
        previousDocCount = allDocuments.size
    }

    val selectDocCallback = remember(onSelectDocument) {
        { doc: Document ->
            hapticTrigger()
            onSelectDocument(doc)
        }
    }
    val longSelectDocCallback = remember(hapticTrigger) {
        { doc: Document ->
            hapticTrigger()
            activeOptionsDoc = doc
        }
    }

    val nonEvictCollections = remember(allCollections, allCrossRefs, allDocuments) {
        // Group cross-refs by collectionId once (O(N)) instead of filtering per collection (O(N×M))
        val crossRefsByCol = allCrossRefs.groupBy { it.collectionId }
        allCollections.map { col ->
            val docIds = crossRefsByCol[col.id]?.map { it.documentId }?.toSet() ?: emptySet()
            col to allDocuments.filter { it.id in docIds }
        }.filter { it.second.isNotEmpty() }
    }

    val favoriteDocs = remember(allDocuments) { allDocuments.filter { it.isFavorite } }
    var localCollections by remember(nonEvictCollections) {
        mutableStateOf(nonEvictCollections)
    }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // State management for raw picked uri
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Activity launcher for picker supporting multi-upload
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size == 1) {
                val uri = uris[0]
                selectedFileUri = uri
                // Query off main thread to avoid ANR risk on slow content providers
                scope.launch {
                    var displayName = "Imported File"
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                displayName = cursor.getString(nameIndex)
                            }
                        }
                    }
                    selectedFileName = displayName
                    activeInputType = AddInputType.FILE
                    showAddDialog = true
                }
            } else {
                // Multi-upload flow: import all selected files sequentially off main thread
                scope.launch {
                    uris.forEach { uri ->
                        var displayName = "Imported File"
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1 && cursor.moveToFirst()) {
                                    displayName = cursor.getString(nameIndex)
                                }
                            }
                        }
                        val cleanTitle = displayName.substringBeforeLast(".")
                        onUriImport(uri, cleanTitle, false, null, false, null)
                    }
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
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Readout",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 21.sp,
                            letterSpacing = 0.3.sp,
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
                            imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
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
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Unified Action buttons side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // File Action Card (opens Add Document modal with File tab selected)
                        ActionCard(
                            title = "File",
                            icon = Icons.Default.Description,
                            onClick = { 
                                hapticTrigger()
                                selectedFileUri = null
                                selectedFileName = ""
                                activeInputType = AddInputType.FILE
                                showAddDialog = true 
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
                    // Heading 2: Continue Reading with clean Library navigation
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Continue Reading",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp,
                                color = Color.White
                            )

                            // Minimalist Library navigation link
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        hapticTrigger()
                                        onOpenLibrary()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Library",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Open Library",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))

                        if (allDocuments.isEmpty()) {
                            // Editorial Empty State card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f),
                                                Color(0xFF131316)
                                            )
                                        )
                                    )
                                    .border(
                                        androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            Brush.verticalGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                                    Color.White.copy(alpha = 0.05f)
                                                )
                                            )
                                        ),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .padding(vertical = 36.dp, horizontal = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoStories,
                                            contentDescription = "Empty Library",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Your Stories Await",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Import a PDF, EPUB, web link, or paste text to experience natural AI audio narration.",
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.60f),
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.fillMaxWidth(0.88f)
                                    )
                                }
                            }
                        } else {
                            // Scrollable Row of Documents styled beautifully as Cover sheets
                            LazyRow(
                                state = recentReadsListState,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recent_reads_row")
                            ) {
                                items(allDocuments, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = selectDocCallback,
                                        onLongSelect = longSelectDocCallback
                                    )
                                }
                            }
                        }
                    }
                }

                if (favoriteDocs.isNotEmpty()) {
                    item {
                        // Heading 3: Favorites
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Favorites",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            Spacer(modifier = Modifier.height(10.dp))

                            // Scrollable Row of Documents styled beautifully as Cover sheets
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(favoriteDocs, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = selectDocCallback,
                                        onLongSelect = longSelectDocCallback
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
                                    fontSize = 18.sp,
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
                            
                            Spacer(modifier = Modifier.height(10.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(colDocs, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = selectDocCallback,
                                        onLongSelect = longSelectDocCallback
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
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .height(72.dp)
                )
            }
        }

        if (showAddDialog) {
            AddDocumentDialog(
                initialType = activeInputType,
                selectedUri = selectedFileUri,
                selectedFileName = selectedFileName,
                initialDrafts = initialScannedDrafts,
                allCollections = allCollections,
                onDismiss = {
                    showAddDialog = false
                    initialScannedDrafts = emptyList()
                    selectedFileUri = null
                    selectedFileName = ""
                },
                onOpenDeviceScanner = {
                    showAddDialog = false
                    showDeviceScanner = true
                },
                onCreateCollection = { name, callback ->
                    onCreateCollection(name, null, callback)
                },
                onAdd = { title, content, customCoverUri, isFavorite, collectionId ->
                    val coverPath = saveCoverFromUri(context, customCoverUri)
                    onAddDocument(title, content, "Pasted Text", coverPath, isFavorite, collectionId)
                    showAddDialog = false
                    initialScannedDrafts = emptyList()
                },
                onUrlImport = { url, title, customCoverUri, isFavorite, collectionId ->
                    onUrlImport(url, title, customCoverUri, isFavorite, collectionId)
                    showAddDialog = false
                    initialScannedDrafts = emptyList()
                },
                onUriImport = { uri, title, customCoverUri, isFavorite, collectionId ->
                    onUriImport(uri, title, false, customCoverUri, isFavorite, collectionId)
                    showAddDialog = false
                    initialScannedDrafts = emptyList()
                },
                onBatchImport = { drafts ->
                    initialScannedDrafts = emptyList()
                    if (onBatchImport != null) {
                        onBatchImport(drafts)
                    } else {
                        scope.launch {
                            drafts.forEach { draft ->
                                onUriImport(
                                    draft.uri,
                                    draft.title.ifBlank { null },
                                    false,
                                    draft.customCoverUri,
                                    draft.isFavorite,
                                    draft.collectionId
                                )
                            }
                        }
                    }
                    showAddDialog = false
                }
            )
        }

        if (showDeviceScanner) {
            DeviceScannerSheet(
                onDismiss = { showDeviceScanner = false },
                onSelectDocument = { uri, name ->
                    showDeviceScanner = false
                    val cleanName = name.substringBeforeLast(".")
                    val spaced = cleanName.replace(Regex("[_\\-]+"), " ")
                    val cleanTitle = spaced.split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { word ->
                            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                        }
                    val ext = name.substringAfterLast(".", "").uppercase()
                    initialScannedDrafts = listOf(
                        SelectedDocumentDraft(
                            uri = uri,
                            fileName = name,
                            title = cleanTitle.ifBlank { name },
                            format = ext.ifBlank { "DOC" },
                            isExtractingCover = true,
                            isExpanded = true
                        )
                    )
                    selectedFileUri = null
                    selectedFileName = ""
                    activeInputType = AddInputType.FILE
                    showAddDialog = true
                },
                onImportMultipleDocuments = { docs ->
                    showDeviceScanner = false
                    val drafts = docs.map { doc ->
                        val cleanName = doc.name.substringBeforeLast(".")
                        val spaced = cleanName.replace(Regex("[_\\-]+"), " ")
                        val cleanTitle = spaced.split(" ")
                            .filter { it.isNotBlank() }
                            .joinToString(" ") { word ->
                                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                            }
                        val ext = doc.extension.uppercase()
                        SelectedDocumentDraft(
                            uri = doc.uri,
                            fileName = doc.name,
                            title = cleanTitle.ifBlank { doc.name },
                            format = ext.ifBlank { "DOC" },
                            isExtractingCover = true,
                            isExpanded = (docs.size == 1)
                        )
                    }
                    initialScannedDrafts = drafts
                    selectedFileUri = null
                    selectedFileName = ""
                    activeInputType = AddInputType.FILE
                    showAddDialog = true
                },
                onBrowseAll = {
                    showDeviceScanner = false
                    initialScannedDrafts = emptyList()
                    selectedFileUri = null
                    selectedFileName = ""
                    activeInputType = AddInputType.FILE
                    showAddDialog = true
                }
            )
        }

        // Granular, transparent progress dialog tracking extraction, speech analysis, and saving
        if (importProgress.isImporting) {
            ImportProgressDialog(progress = importProgress, onCancel = onCancelImport)
        }

        collectionTargetDoc?.let { doc ->
            CollectionAssignDialog(
                document = doc,
                allCollections = allCollections,
                allCrossRefs = allCrossRefs,
                onDismiss = { collectionTargetDoc = null },
                onAddRelation = { colId -> onAddDocumentToCollection(doc.id, colId) },
                onRemoveRelation = { colId -> onRemoveDocumentFromCollection(doc.id, colId) },
                onCreateCollection = { name -> onCreateCollection(name, doc.id, null) },
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
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF141417),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24242A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

// Shared gradient palette — rich, editorial tones for generated covers
private val COVER_GRADIENTS = listOf(
    listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)), // Midnight Sapphire
    listOf(Color(0xFF064E3B), Color(0xFF022C22)), // Nordic Emerald
    listOf(Color(0xFF4C1D95), Color(0xFF1E1B4B)), // Royal Obsidian
    listOf(Color(0xFF164E63), Color(0xFF083344)), // Deep Ocean Teal
    listOf(Color(0xFF701A75), Color(0xFF2E1065)), // Wine Amethyst
    listOf(Color(0xFF1C1917), Color(0xFF0C0A09))  // Titanium Slate
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    document: Document,
    onSelect: (Document) -> Unit,
    onLongSelect: (Document) -> Unit,
    cardWidth: Dp = 116.dp,
    cardHeight: Dp = 156.dp
) {
    val classification = remember(document.id, document.sourceUrl, document.title, document.coverPath) {
        getDocumentClassification(document)
    }
    val percentage = remember(document.id, document.playbackPosition, document.contentLength) {
        if (document.contentLength > 0)
            (document.playbackPosition.getOrZeroPercent().toFloat() / document.contentLength.toFloat()).coerceIn(0f, 1f)
        else 0f
    }
    val coverGradient = remember(document.title) {
        COVER_GRADIENTS[Math.abs(document.title.hashCode()) % COVER_GRADIENTS.size]
    }
    val iconVector = classification.icon

    // Subtitle label: progress, reading time / word count, or clean document classification
    val subtitleText = remember(percentage, document.contentLength, classification) {
        when {
            percentage >= 0.98f -> "Completed"
            percentage > 0.01f -> "${(percentage * 100).toInt()}% completed"
            else -> classification.displayLabel
        }
    }

    // Load cover bitmap from coverPath asynchronously, utilizing CoverCache.
    val cachedBitmap = remember(document.coverPath) {
        document.coverPath?.let { path ->
            if (CoverCache.isFailed(path)) null else CoverCache.get(path)
        }
    }

    val localCoverBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = cachedBitmap,
        key1 = document.coverPath
    ) {
        if (cachedBitmap == null) {
            value = document.coverPath?.let { path ->
                if (CoverCache.isFailed(path)) {
                    null
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val bitmap = com.iefan.readout.utils.BitmapOptimizer
                                .decodeSampledBitmapFromFile(path, 360, 540)
                                ?.asImageBitmap()
                            if (bitmap != null) CoverCache.put(path, bitmap)
                            else CoverCache.markFailed(path)
                            bitmap
                        } catch (e: Exception) {
                            CoverCache.markFailed(path)
                            null
                        }
                    }
                }
            }
        }
    }

    val isCompact = cardWidth < 110.dp

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
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(13.dp), clip = false)
                .clip(RoundedCornerShape(13.dp))
                .border(1.dp, Color(0xFF26262C), RoundedCornerShape(13.dp))
                .combinedClickable(
                    onClick = { onSelect(document) },
                    onLongClick = { onLongSelect(document) }
                )
                .testTag("document_card_${document.id}")
        ) {
            val coverBitmap = cachedBitmap ?: localCoverBitmap
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = "Document Cover Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Editorial typography for generated cover
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(coverGradient))
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top format badge + micro icon
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = classification.badgeText,
                                fontSize = if (isCompact) 8.sp else 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = Color.White.copy(alpha = 0.50f)
                            )
                            Icon(
                                imageVector = iconVector,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(13.dp)
                            )
                        }

                        // Bottom section: accent bar and serif title
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(Color.White.copy(alpha = 0.40f))
                            )
                            Text(
                                text = document.title,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                fontSize = if (isCompact) 10.5.sp else 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.95f),
                                lineHeight = if (isCompact) 13.5.sp else 15.sp,
                                maxLines = if (isCompact) 3 else 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Hardcover Book Spine crease highlight along left edge
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.20f),
                                Color.White.copy(alpha = 0.04f),
                                Color.Black.copy(alpha = 0.35f)
                            )
                        )
                    )
            )

            // Subtle top edge highlight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(Color.White.copy(alpha = 0.10f))
            )

            // Visual Progress Strip at the bottom of the cover
            if (percentage > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.65f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(percentage)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = document.title,
            maxLines = 2,
            fontSize = if (isCompact) 11.5.sp else 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (isCompact) 14.5.sp else 15.5.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (percentage >= 0.98f) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = subtitleText,
                fontSize = if (isCompact) 10.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (percentage >= 0.98f) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun saveCoverFromUri(context: android.content.Context, uri: Uri?): String? {
    if (uri == null) return null
    return try {
        val file = java.io.File(context.filesDir, "cover_${System.currentTimeMillis()}.png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ImportProgressDialog(
    onCancel: () -> Unit = {},
    progress: ImportTaskProgress,
    onDismissRequest: () -> Unit = {}
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "importProgressAnim"
    )

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF141418),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = onCancel) { Text("Cancel import") }
                val headerText = if (progress.totalItems > 1) {
                    "Importing Documents (${progress.currentItemIndex}/${progress.totalItems})"
                } else {
                    "Importing Document"
                }

                Text(
                    text = headerText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (progress.currentTitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = progress.currentTitle,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.08f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = progress.currentStage.ifBlank { "Processing..." },
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

data class SelectedDocumentDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val fileName: String,
    val title: String,
    val format: String,
    val customCoverUri: Uri? = null,
    val customCoverBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    val autoCoverBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    val isExtractingCover: Boolean = false,
    val isFavorite: Boolean = false,
    val collectionId: Long? = null,
    val isExpanded: Boolean = false
)

private fun getFormatColor(format: String, primaryColor: Color): Color = when (format.uppercase()) {
    "EPUB" -> Color(0xFFA855F7) // Violet
    "PDF" -> Color(0xFFFF5252)  // Coral Red
    "DOCX" -> Color(0xFF3B82F6) // Blue
    "TXT" -> Color(0xFF10B981)  // Emerald
    "HTML", "HTM" -> Color(0xFFF59E0B) // Amber
    else -> primaryColor
}

@Composable
fun CreateCategoryDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val isDuplicate = remember(name, existingNames) {
        val trimmed = name.trim()
        trimmed.isNotEmpty() && existingNames.any { it.equals(trimmed, ignoreCase = true) }
    }
    val isValid = name.trim().isNotBlank() && !isDuplicate

    LaunchedEffect(Unit) {
        delay(120)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161A),
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        "New Category",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF222228))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Create a custom category to organize and group your reading items.",
                    fontSize = 12.sp,
                    color = Color(0xFF8E8E93),
                    lineHeight = 16.sp
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    placeholder = { Text("Category name (e.g. Science, Philosophy)", fontSize = 13.sp, color = Color(0xFF636366)) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = if (isDuplicate) Color(0xFFEF4444) else Color(0xFF2A2A32),
                        focusedContainerColor = Color(0xFF101014),
                        unfocusedContainerColor = Color(0xFF101014)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (isValid) {
                            onConfirm(name.trim())
                        }
                    })
                )
                if (isDuplicate) {
                    Text(
                        text = "A category with this name already exists.",
                        fontSize = 11.5.sp,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onConfirm(name.trim())
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Create", color = if (isValid) Color.White else Color(0xFF8E8E93), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel", color = Color(0xFF8E8E93))
            }
        }
    )
}

@Composable
fun CategorySelectionRow(
    isFavorite: Boolean,
    selectedCollectionId: Long?,
    allCollections: List<CollectionEntity>,
    onToggleFavorite: () -> Unit,
    onSelectCollection: (Long?) -> Unit,
    onOpenCreateCategory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // + New Category Chip (placed cleanly in front of Favorites)
        Surface(
            onClick = onOpenCreateCategory,
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF19191E),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.height(32.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create new category",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "New",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Favorites Chip
        FilterChip(
            selected = isFavorite,
            onClick = onToggleFavorite,
            leadingIcon = {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) Color(0xFFEF4444) else Color(0xFF8E8E93),
                    modifier = Modifier.size(14.dp)
                )
            },
            label = { Text("Favorites", fontSize = 11.5.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF3B181E),
                selectedLabelColor = Color(0xFFFFB3B8),
                containerColor = Color(0xFF101014),
                labelColor = Color(0xFFC4C4C8)
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = isFavorite,
                borderColor = Color(0xFF26262E),
                selectedBorderColor = Color(0xFFEF4444).copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(8.dp)
        )

        // User Collection / Category Chips
        allCollections.forEach { col ->
            val isSelected = selectedCollectionId == col.id
            FilterChip(
                selected = isSelected,
                onClick = { onSelectCollection(if (isSelected) null else col.id) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93),
                        modifier = Modifier.size(14.dp)
                    )
                },
                label = { Text(col.name, fontSize = 11.5.sp, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    containerColor = Color(0xFF101014),
                    labelColor = Color(0xFFC4C4C8)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = Color(0xFF26262E),
                    selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@Composable
private fun SelectedDraftItemCard(
    draft: SelectedDocumentDraft,
    allCollections: List<CollectionEntity>,
    onToggleExpand: () -> Unit,
    onTitleChange: (String) -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectCollection: (Long?) -> Unit,
    onOpenCreateCategory: () -> Unit = {},
    onRemoveDraft: () -> Unit
) {
    val extColor = getFormatColor(draft.format, MaterialTheme.colorScheme.primary)
    val displayedCover = draft.customCoverBitmap ?: draft.autoCoverBitmap

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF16161A),
        border = BorderStroke(
            1.dp,
            if (draft.isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color(0xFF24242A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Collapsed Header Row (Tap to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Cover Preview Thumbnail (40x54dp)
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 54.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFF202026))
                        .border(
                            1.dp,
                            if (displayedCover != null) extColor.copy(alpha = 0.45f) else Color(0xFF2E2E36),
                            RoundedCornerShape(7.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (displayedCover != null) {
                        Image(
                            bitmap = displayedCover,
                            contentDescription = "Cover preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (draft.isExtractingCover) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = when (draft.format) {
                                "EPUB" -> Icons.AutoMirrored.Filled.MenuBook
                                "PDF" -> Icons.Default.PictureAsPdf
                                else -> Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = extColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Middle Info Column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = draft.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Extension Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(extColor.copy(alpha = 0.15f))
                                .border(0.5.dp, extColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = draft.format,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = extColor
                            )
                        }

                        // Favorite badge if active
                        if (draft.isFavorite) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = "Favorite",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }

                        // Collection name badge if active
                        if (draft.collectionId != null) {
                            val colName = allCollections.firstOrNull { it.id == draft.collectionId }?.name
                            if (colName != null) {
                                Text(
                                    text = "• $colName",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF8E8E93),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Expand/Collapse Indicator
                Icon(
                    imageVector = if (draft.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (draft.isExpanded) "Collapse" else "Expand",
                    tint = Color(0xFFAAAAAA),
                    modifier = Modifier.size(20.dp)
                )

                // Remove Draft Icon
                IconButton(
                    onClick = onRemoveDraft,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove file",
                        tint = Color(0xFF71717A),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Expanded Options Section
            AnimatedVisibility(visible = draft.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF24242C), thickness = 0.6.dp)

                    // 1. Document Title Input
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = onTitleChange,
                        label = { Text("Document Title", fontSize = 11.5.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.5.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF2A2A32),
                            focusedContainerColor = Color(0xFF101014),
                            unfocusedContainerColor = Color(0xFF101014)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // 2. Book Cover Customization Option
                    Surface(
                        onClick = onPickCover,
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF101014),
                        border = BorderStroke(1.dp, Color(0xFF26262E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E1E24))
                                    .border(
                                        1.dp,
                                        if (displayedCover != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color(0xFF2E2E36),
                                        RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (displayedCover != null) {
                                    Image(
                                        bitmap = displayedCover,
                                        contentDescription = "Cover",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else if (draft.isExtractingCover) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        tint = Color(0xFF71717A),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Book Cover",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        draft.customCoverUri != null -> "Custom cover selected"
                                        draft.autoCoverBitmap != null -> "Detected from document"
                                        draft.isExtractingCover -> "Extracting preview..."
                                        else -> "No cover detected (tap to customize)"
                                    },
                                    fontSize = 11.sp,
                                    color = if (displayedCover != null) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (draft.customCoverUri != null) {
                                IconButton(
                                    onClick = onRemoveCover,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove Cover",
                                        tint = Color(0xFF8E8E93),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = Color(0xFF1E1E24),
                                    border = BorderStroke(1.dp, Color(0xFF2E2E36))
                                ) {
                                    Text(
                                        text = if (displayedCover != null) "Change" else "Add",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. Add to Category & Favorites Section
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Add to Category",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8E8E93)
                        )

                        CategorySelectionRow(
                            isFavorite = draft.isFavorite,
                            selectedCollectionId = draft.collectionId,
                            allCollections = allCollections,
                            onToggleFavorite = onToggleFavorite,
                            onSelectCollection = onSelectCollection,
                            onOpenCreateCategory = onOpenCreateCategory
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddDocumentDialog(
    initialType: AddInputType,
    selectedUri: Uri?,
    selectedFileName: String?,
    initialDrafts: List<SelectedDocumentDraft> = emptyList(),
    allCollections: List<CollectionEntity> = emptyList(),
    onDismiss: () -> Unit,
    onOpenDeviceScanner: () -> Unit = {},
    onCreateCollection: (String, (Long) -> Unit) -> Unit = { _, _ -> },
    onAdd: (String, String, Uri?, Boolean, Long?) -> Unit,
    onUrlImport: (String, String?, Uri?, Boolean, Long?) -> Unit,
    onUriImport: (Uri, String?, Uri?, Boolean, Long?) -> Unit,
    onBatchImport: ((List<SelectedDocumentDraft>) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTab = initialType

    // Multi-draft state for selected documents
    var selectedDrafts by remember(initialDrafts) { mutableStateOf<List<SelectedDocumentDraft>>(initialDrafts) }
    var targetCoverDraftId by remember { mutableStateOf<String?>(null) }

    // Category creation modal state
    var showCreateCategoryDialog by remember { mutableStateOf(false) }
    var createCategoryTargetDraftId by remember { mutableStateOf<String?>(null) }

    // State for Paste and URL tabs
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var pasteOrUrlCoverUri by remember { mutableStateOf<Uri?>(null) }
    var pasteOrUrlFavorite by remember { mutableStateOf(false) }
    var pasteOrUrlCollectionId by remember { mutableStateOf<Long?>(null) }

    // Multi-file Picker Launcher
    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val newDrafts = uris.map { uri ->
                    var displayName = "Document"
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1 && cursor.moveToFirst()) {
                                    displayName = cursor.getString(nameIndex) ?: displayName
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    val ext = displayName.substringAfterLast(".", "").uppercase()
                    val cleanName = displayName.substringBeforeLast(".")
                    val spaced = cleanName.replace(Regex("[_\\-]+"), " ")
                    val cleanTitle = spaced.split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { word ->
                            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                        }
                    SelectedDocumentDraft(
                        uri = uri,
                        fileName = displayName,
                        title = cleanTitle.ifBlank { displayName },
                        format = ext.ifBlank { "DOC" },
                        isExtractingCover = true,
                        isExpanded = (uris.size == 1 && selectedDrafts.isEmpty())
                    )
                }
                selectedDrafts = selectedDrafts + newDrafts

                // Extract covers asynchronously in background
                newDrafts.forEach { draft ->
                    val cover = CoverPreviewHelper.extractCoverPreview(context, draft.uri, draft.fileName)
                    selectedDrafts = selectedDrafts.map { d ->
                        if (d.id == draft.id) d.copy(autoCoverBitmap = cover, isExtractingCover = false) else d
                    }
                }
            }
        }
    }

    // Cover Image Picker Launcher for a specific draft item
    val draftCoverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val draftId = targetCoverDraftId
        if (draftId != null && uri != null) {
            scope.launch {
                val decoded = CoverPreviewHelper.decodeImageUri(context, uri)
                selectedDrafts = selectedDrafts.map { d ->
                    if (d.id == draftId) d.copy(customCoverUri = uri, customCoverBitmap = decoded) else d
                }
            }
        }
        targetCoverDraftId = null
    }

    // Single Cover Image Picker for Paste and URL tabs
    val singleCoverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        pasteOrUrlCoverUri = uri
    }

    // Initialize with selectedUri or initialDrafts if passed from outside
    LaunchedEffect(selectedUri, selectedFileName, initialDrafts) {
        if (initialDrafts.isNotEmpty()) {
            selectedDrafts = initialDrafts
            scope.launch {
                initialDrafts.forEach { draft ->
                    if (draft.autoCoverBitmap == null && draft.customCoverBitmap == null) {
                        val cover = CoverPreviewHelper.extractCoverPreview(context, draft.uri, draft.fileName)
                        selectedDrafts = selectedDrafts.map { d ->
                            if (d.id == draft.id) d.copy(autoCoverBitmap = cover, isExtractingCover = false) else d
                        }
                    }
                }
            }
        } else if (selectedUri != null && selectedDrafts.isEmpty()) {
            val fileName = selectedFileName ?: "Document"
            val ext = fileName.substringAfterLast(".", "").uppercase()
            val cleanName = fileName.substringBeforeLast(".")
            val spaced = cleanName.replace(Regex("[_\\-]+"), " ")
            val cleanTitle = spaced.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                }
            val initialDraft = SelectedDocumentDraft(
                uri = selectedUri,
                fileName = fileName,
                title = cleanTitle.ifBlank { fileName },
                format = ext.ifBlank { "DOC" },
                isExtractingCover = true,
                isExpanded = true
            )
            selectedDrafts = listOf(initialDraft)
            scope.launch {
                val cover = CoverPreviewHelper.extractCoverPreview(context, selectedUri, fileName)
                selectedDrafts = selectedDrafts.map {
                    if (it.id == initialDraft.id) it.copy(autoCoverBitmap = cover, isExtractingCover = false) else it
                }
            }
        }
    }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (currentTab) {
                                AddInputType.FILE -> Icons.Default.FolderOpen
                                AddInputType.PASTE -> Icons.Default.ContentPaste
                                AddInputType.URL -> Icons.Default.Link
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = when (currentTab) {
                            AddInputType.FILE -> "Import Documents"
                            AddInputType.PASTE -> "Paste Text"
                            AddInputType.URL -> "Import Web Article"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF222228))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (currentTab) {
                    AddInputType.FILE -> {
                        if (selectedDrafts.isEmpty()) {
                            // Elevated initial state using reclaimed modal space
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Add books, research papers, or articles to your library:",
                                    fontSize = 12.sp,
                                    color = Color(0xFF8E8E93),
                                    lineHeight = 16.sp
                                )

                                // Option 1: Scan Device for Files
                                Surface(
                                    onClick = onOpenDeviceScanner,
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FindInPage,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Scan Device Storage",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "Auto-detect EPUB, PDF & documents on your phone",
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                                lineHeight = 15.sp
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "Scan",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
                                            )
                                        }
                                    }
                                }

                                // Option 2: Choose Files Manually (supports multiple files)
                                Surface(
                                    onClick = { multiFilePickerLauncher.launch("*/*") },
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF16161A),
                                    border = BorderStroke(1.dp, Color(0xFF282832)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(Color(0xFF222228)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Browse Files Manually",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "Pick single or multiple files from file manager",
                                                fontSize = 11.5.sp,
                                                color = Color(0xFF8E8E93),
                                                lineHeight = 15.sp
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF222228),
                                            border = BorderStroke(1.dp, Color(0xFF353540))
                                        ) {
                                            Text(
                                                text = "Browse",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
                                            )
                                        }
                                    }
                                }

                                // Supported Formats Indicator Strip
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Formats:",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF636366),
                                        fontWeight = FontWeight.Medium
                                    )
                                    val formats = listOf(
                                        "EPUB" to Color(0xFFA855F7),
                                        "PDF" to Color(0xFFFF5252),
                                        "DOCX" to Color(0xFF3B82F6),
                                        "TXT" to Color(0xFF10B981)
                                    )
                                    formats.forEach { (name, color) ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(color.copy(alpha = 0.12f))
                                                .border(0.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                        ) {
                                            Text(
                                                text = name,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = color
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // File(s) selected: "Scan Device for Files" disappears!
                            // Displays clean header and expandable list of files.
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Action Header: Count, Add More, Clear All
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Selected Files (${selectedDrafts.size})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { multiFilePickerLauncher.launch("*/*") },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add More", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                        TextButton(
                                            onClick = { selectedDrafts = emptyList() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Clear All", fontSize = 12.sp, color = Color(0xFF8E8E93))
                                        }
                                    }
                                }

                                // Clean, compact category row for multiple files
                                if (selectedDrafts.size > 1) {
                                    val commonFav = selectedDrafts.all { it.isFavorite }
                                    val commonColId = selectedDrafts.map { it.collectionId }.distinct().let {
                                        if (it.size == 1) it.first() else null
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Category for all files:",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF8E8E93)
                                        )

                                        CategorySelectionRow(
                                            isFavorite = commonFav,
                                            selectedCollectionId = commonColId,
                                            allCollections = allCollections,
                                            onToggleFavorite = {
                                                val newFav = !commonFav
                                                selectedDrafts = selectedDrafts.map { it.copy(isFavorite = newFav) }
                                            },
                                            onSelectCollection = { colId ->
                                                selectedDrafts = selectedDrafts.map { it.copy(collectionId = colId) }
                                            },
                                            onOpenCreateCategory = {
                                                createCategoryTargetDraftId = "BATCH_ALL"
                                                showCreateCategoryDialog = true
                                            }
                                        )
                                    }
                                }

                                // Scrollable list of expandable drafts
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 380.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(selectedDrafts, key = { it.id }) { draft ->
                                        SelectedDraftItemCard(
                                            draft = draft,
                                            allCollections = allCollections,
                                            onToggleExpand = {
                                                selectedDrafts = selectedDrafts.map {
                                                    if (it.id == draft.id) it.copy(isExpanded = !it.isExpanded) else it
                                                }
                                            },
                                            onTitleChange = { newTitle ->
                                                selectedDrafts = selectedDrafts.map {
                                                    if (it.id == draft.id) it.copy(title = newTitle) else it
                                                }
                                            },
                                            onPickCover = {
                                                targetCoverDraftId = draft.id
                                                draftCoverPickerLauncher.launch("image/*")
                                            },
                                            onRemoveCover = {
                                                selectedDrafts = selectedDrafts.map {
                                                    if (it.id == draft.id) it.copy(customCoverUri = null, customCoverBitmap = null) else it
                                                }
                                            },
                                            onToggleFavorite = {
                                                selectedDrafts = selectedDrafts.map {
                                                    if (it.id == draft.id) it.copy(isFavorite = !it.isFavorite) else it
                                                }
                                            },
                                            onSelectCollection = { colId ->
                                                selectedDrafts = selectedDrafts.map {
                                                    if (it.id == draft.id) it.copy(collectionId = colId) else it
                                                }
                                            },
                                            onOpenCreateCategory = {
                                                createCategoryTargetDraftId = draft.id
                                                showCreateCategoryDialog = true
                                            },
                                            onRemoveDraft = {
                                                selectedDrafts = selectedDrafts.filter { it.id != draft.id }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    AddInputType.PASTE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Preload Sample Fast buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        title = "The Magic of Local AI"
                                        content = """For years, running large neural network architectures meant piping personal parameters back and forth to giant server racks hosted in centralized clouds. Today, modern micro-onnx ran pipelines make running speech synthesizers directly inside your pocket fully real. 

This is incredibly important for mobile computers, meaning zero networking costs, absolute tracking privacy, and uninterrupted playback inside planes or subway commutes."""
                                        url = ""
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "AI Preset",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        title = "Mindfulness & Flow State"
                                        content = """Achieving professional mastery is rarely about running faster; it is about learning how to calm the noise. Flow states happen when there is a perfect equilibrium between the difficulty of a challenge and your absolute dedicated focus. 

By eliminating the constant visual notifications of modern computers and turning text-heavy articles into an elegant audio-stream, you can consume long-form thinking while resting your eyes and keeping the mind in a deep flow channel."""
                                        url = ""
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SelfImprovement,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Flow Preset",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Title (Optional)", fontSize = 12.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2A2A32),
                                    focusedContainerColor = Color(0xFF16161A),
                                    unfocusedContainerColor = Color(0xFF16161A)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_title"),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("Article Content / Raw Text", fontSize = 12.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2A2A32),
                                    focusedContainerColor = Color(0xFF16161A),
                                    unfocusedContainerColor = Color(0xFF16161A)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .testTag("doc_add_content"),
                                maxLines = 8
                            )

                            // Optional cover and collections for pasted text
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { singleCoverPickerLauncher.launch("image/*") },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (pasteOrUrlCoverUri != null) "Cover Attached" else "Add Cover", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                if (pasteOrUrlCoverUri != null) {
                                    TextButton(onClick = { pasteOrUrlCoverUri = null }) {
                                        Text("Remove Cover", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                    }
                                }
                            }

                            // Category and favorites section
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Add to Category",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF8E8E93)
                                )

                                CategorySelectionRow(
                                    isFavorite = pasteOrUrlFavorite,
                                    selectedCollectionId = pasteOrUrlCollectionId,
                                    allCollections = allCollections,
                                    onToggleFavorite = { pasteOrUrlFavorite = !pasteOrUrlFavorite },
                                    onSelectCollection = { colId -> pasteOrUrlCollectionId = colId },
                                    onOpenCreateCategory = {
                                        createCategoryTargetDraftId = "PASTE_OR_URL"
                                        showCreateCategoryDialog = true
                                    }
                                )
                            }
                        }
                    }
                    AddInputType.URL -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Extracts full article content directly from web links & Wikipedia articles.",
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E93),
                                lineHeight = 15.sp
                            )

                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("Article URL / Address", fontSize = 12.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2A2A32),
                                    focusedContainerColor = Color(0xFF16161A),
                                    unfocusedContainerColor = Color(0xFF16161A)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_url"),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Title (Optional/Auto)", fontSize = 12.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2A2A32),
                                    focusedContainerColor = Color(0xFF16161A),
                                    unfocusedContainerColor = Color(0xFF16161A)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doc_add_title"),
                                singleLine = true
                            )

                            // Optional cover and collections for web links
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { singleCoverPickerLauncher.launch("image/*") },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (pasteOrUrlCoverUri != null) "Cover Attached" else "Add Cover", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                if (pasteOrUrlCoverUri != null) {
                                    TextButton(onClick = { pasteOrUrlCoverUri = null }) {
                                        Text("Remove Cover", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                    }
                                }
                            }

                            // Category and favorites section
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Add to Category",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF8E8E93)
                                )

                                CategorySelectionRow(
                                    isFavorite = pasteOrUrlFavorite,
                                    selectedCollectionId = pasteOrUrlCollectionId,
                                    allCollections = allCollections,
                                    onToggleFavorite = { pasteOrUrlFavorite = !pasteOrUrlFavorite },
                                    onSelectCollection = { colId -> pasteOrUrlCollectionId = colId },
                                    onOpenCreateCategory = {
                                        createCategoryTargetDraftId = "PASTE_OR_URL"
                                        showCreateCategoryDialog = true
                                    }
                                )
                            }
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
                            if (selectedDrafts.isNotEmpty()) {
                                if (selectedDrafts.size == 1) {
                                    val single = selectedDrafts.first()
                                    onUriImport(
                                        single.uri,
                                        single.title.ifBlank { null },
                                        single.customCoverUri,
                                        single.isFavorite,
                                        single.collectionId
                                    )
                                } else if (onBatchImport != null) {
                                    onBatchImport(selectedDrafts)
                                } else {
                                    selectedDrafts.forEach { draft ->
                                        onUriImport(
                                            draft.uri,
                                            draft.title.ifBlank { null },
                                            draft.customCoverUri,
                                            draft.isFavorite,
                                            draft.collectionId
                                        )
                                    }
                                }
                            }
                        }
                        AddInputType.PASTE -> {
                            if (content.isNotBlank()) {
                                onAdd(
                                    title.ifBlank { "Pasted Article" },
                                    content,
                                    pasteOrUrlCoverUri,
                                    pasteOrUrlFavorite,
                                    pasteOrUrlCollectionId
                                )
                            }
                        }
                        AddInputType.URL -> {
                            if (url.isNotBlank()) {
                                onUrlImport(
                                    url,
                                    title.ifBlank { null },
                                    pasteOrUrlCoverUri,
                                    pasteOrUrlFavorite,
                                    pasteOrUrlCollectionId
                                )
                            }
                        }
                    }
                },
                enabled = when (currentTab) {
                    AddInputType.FILE -> selectedDrafts.isNotEmpty()
                    AddInputType.PASTE -> content.isNotBlank()
                    AddInputType.URL -> url.isNotBlank()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("doc_submit_add_btn")
            ) {
                val btnLabel = when (currentTab) {
                    AddInputType.FILE -> {
                        if (selectedDrafts.size > 1) "Import ${selectedDrafts.size} Documents" else "Add to Library"
                    }
                    AddInputType.PASTE -> "Save to Library"
                    AddInputType.URL -> "Import Article"
                }
                Text(btnLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8E8E93))
            ) {
                Text("Cancel", fontSize = 13.sp)
            }
        },
        containerColor = Color(0xFF121215),
        shape = RoundedCornerShape(26.dp)
    )

    if (showCreateCategoryDialog) {
        CreateCategoryDialog(
            existingNames = allCollections.map { it.name },
            onDismiss = {
                showCreateCategoryDialog = false
                createCategoryTargetDraftId = null
            },
            onConfirm = { catName ->
                onCreateCollection(catName) { newColId ->
                    when (val target = createCategoryTargetDraftId) {
                        "BATCH_ALL" -> {
                            selectedDrafts = selectedDrafts.map { it.copy(collectionId = newColId) }
                        }
                        "PASTE_OR_URL" -> {
                            pasteOrUrlCollectionId = newColId
                        }
                        null -> {
                            if (selectedDrafts.size == 1) {
                                selectedDrafts = selectedDrafts.map { it.copy(collectionId = newColId) }
                            } else {
                                pasteOrUrlCollectionId = newColId
                            }
                        }
                        else -> {
                            selectedDrafts = selectedDrafts.map {
                                if (it.id == target) it.copy(collectionId = newColId) else it
                            }
                        }
                    }
                }
                showCreateCategoryDialog = false
                createCategoryTargetDraftId = null
            }
        )
    }
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
                .background(Color(0xE6141418))
                .border(
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.04f))
                        )
                    ),
                    RoundedCornerShape(36.dp)
                )
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
                    document.coverPath?.let { path ->
                        if (CoverCache.isFailed(path)) null else CoverCache.get(path)
                    }
                }

                val localCoverBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = cachedBitmap, key1 = document.coverPath) {
                    if (cachedBitmap == null) {
                        value = document.coverPath?.let { path ->
                            if (CoverCache.isFailed(path)) {
                                null
                            } else {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val bitmap = com.iefan.readout.utils.BitmapOptimizer
                                            .decodeSampledBitmapFromFile(path, 180, 270)
                                            ?.asImageBitmap()
                                        if (bitmap != null) {
                                            CoverCache.put(path, bitmap)
                                        } else {
                                            CoverCache.markFailed(path)
                                        }
                                        bitmap
                                    } catch (e: Exception) {
                                        CoverCache.markFailed(path)
                                        null
                                    }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                repeatDelayMillis = 1500,
                                velocity = 30.dp
                            )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    val infoText by remember(document, progressFraction) {
                        derivedStateOf {
                            val percentage = (progressFraction * 100).toInt().coerceIn(0, 100)
                            val charCount = document.contentLength.takeIf { it > 0 } ?: document.content.length.coerceAtLeast(1)
                            val totalWords = (charCount / 5).coerceAtLeast(1)
                            val wordsRemaining = ((1f - progressFraction) * totalWords).toInt().coerceAtLeast(0)
                            val speed = if (document.playbackSpeed > 0f) document.playbackSpeed else 1.0f
                            val totalSeconds = (wordsRemaining / (2.5f * speed)).toInt()
                            val timeRemaining = if (percentage >= 100 || totalSeconds <= 0) {
                                "Completed"
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
                            if (percentage >= 100) "100% · Completed" else "$percentage% · $timeRemaining"
                        }
                    }
                    Text(
                        text = infoText,
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

            // Sleek bottom progress track line
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Color(0x22FFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
