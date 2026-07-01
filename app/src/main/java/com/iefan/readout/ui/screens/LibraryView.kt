package com.iefan.readout.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.iefan.readout.data.CollectionEntity
import com.iefan.readout.data.Document
import com.iefan.readout.data.DocumentCollectionCrossRef
import com.iefan.readout.data.Bookmark
import java.io.File
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import com.iefan.readout.utils.rememberHapticTrigger

enum class SortOption(val displayName: String) {
    LAST_READ("Last Read"),
    TITLE("Title"),
    DATE_ADDED("Date Added")
}

sealed class LibraryFilter {
    object All : LibraryFilter()
    object Favorites : LibraryFilter()
    object Bookmarks : LibraryFilter()
    data class Collection(val collection: CollectionEntity) : LibraryFilter()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    allDocuments: List<Document>,
    allCollections: List<CollectionEntity>,
    allCrossRefs: List<DocumentCollectionCrossRef>,
    allBookmarks: List<Bookmark> = emptyList(),
    onSelectBookmark: (Bookmark) -> Unit = {},
    onBack: () -> Unit,
    onSelectDocument: (Document) -> Unit,
    onToggleFavorite: (Document) -> Unit,
    onAddDocumentToCollection: (Long, Long) -> Unit,
    onRemoveDocumentFromCollection: (Long, Long) -> Unit,
    onCreateCollection: (String, Long?) -> Unit,
    onDeleteCollection: (CollectionEntity) -> Unit,
    onDeleteDocument: (Document) -> Unit,
    onEditDocument: (Long, String, Uri?, Boolean) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val hapticTrigger = rememberHapticTrigger()
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(SortOption.LAST_READ) }
    var showSortMenu by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }
    var isSearchActive by remember { mutableStateOf(false) }

    // Dialog state for collection assignment
    var collectionTargetDoc by remember { mutableStateOf<Document?>(null) }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }
    var documentToEdit by remember { mutableStateOf<Document?>(null) }
    var activeOptionsDoc by remember { mutableStateOf<Document?>(null) }

    // Filter and sort books
    val filteredAndSortedDocuments = remember(allDocuments, searchQuery, sortBy, activeFilter, allCrossRefs) {
        allDocuments.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    (it.sourceUrl?.contains(searchQuery, ignoreCase = true) == true)
        }.filter { doc ->
            when (activeFilter) {
                is LibraryFilter.All -> true
                is LibraryFilter.Favorites -> doc.isFavorite
                is LibraryFilter.Bookmarks -> false // Handled separately in the UI list
                is LibraryFilter.Collection -> {
                    val colId = (activeFilter as LibraryFilter.Collection).collection.id
                    allCrossRefs.any { it.collectionId == colId && it.documentId == doc.id }
                }
            }
        }.sortedWith { d1, d2 ->
            when (sortBy) {
                SortOption.LAST_READ -> d2.lastReadTime.compareTo(d1.lastReadTime)
                SortOption.TITLE -> d1.title.compareTo(d2.title, ignoreCase = true)
                SortOption.DATE_ADDED -> d2.addedDate.compareTo(d1.addedDate)
            }
        }
    }

    val filteredBookmarks = remember(allBookmarks, searchQuery) {
        allBookmarks.filter {
            it.label.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Library",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        hapticTrigger()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        hapticTrigger()
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = if (isSearchActive) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                    Box {
                        IconButton(onClick = {
                            hapticTrigger()
                            showSortMenu = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort Options",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier.background(Color(0xFF141416))
                        ) {
                            SortOption.values().forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.displayName,
                                            color = if (sortBy == option) MaterialTheme.colorScheme.primary else Color.White,
                                            fontWeight = if (sortBy == option) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        hapticTrigger()
                                        sortBy = option
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 12.dp)
        ) {
            // Search Input Field (Compact and Collapsible)
            if (isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search...", color = Color.Gray, fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(48.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF242426),
                        focusedContainerColor = Color(0xFF141416),
                        unfocusedContainerColor = Color(0xFF141416),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Scrollable filter chips
            val hasFavorites = remember(allDocuments) { allDocuments.any { it.isFavorite } }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChipButton(
                        text = "All",
                        selected = activeFilter is LibraryFilter.All,
                        onClick = { activeFilter = LibraryFilter.All }
                    )
                }

                item {
                    FilterChipButton(
                        text = "Bookmarks",
                        selected = activeFilter is LibraryFilter.Bookmarks,
                        onClick = { activeFilter = LibraryFilter.Bookmarks }
                    )
                }

                if (hasFavorites) {
                    item {
                        FilterChipButton(
                            text = "Favorites",
                            selected = activeFilter is LibraryFilter.Favorites,
                            onClick = { activeFilter = LibraryFilter.Favorites }
                        )
                    }
                }

                items(allCollections, key = { it.id }) { col ->
                    FilterChipButton(
                        text = col.name,
                        selected = activeFilter is LibraryFilter.Collection && (activeFilter as LibraryFilter.Collection).collection.id == col.id,
                        onClick = { activeFilter = LibraryFilter.Collection(col) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (activeFilter is LibraryFilter.Bookmarks) {
                if (filteredBookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.BookmarkBorder,
                                contentDescription = "No bookmarks",
                                tint = Color.DarkGray,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No matching bookmarks" else "No bookmarks saved yet",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(filteredBookmarks, key = { it.id }) { bookmark ->
                            val docTitle = remember(allDocuments, bookmark.documentId) {
                                allDocuments.firstOrNull { it.id == bookmark.documentId }?.title ?: "Unknown Document"
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF141416))
                                    .border(1.dp, Color(0xFF242426), RoundedCornerShape(12.dp))
                                    .clickable {
                                        hapticTrigger()
                                        onSelectBookmark(bookmark)
                                    }
                                    .padding(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = docTitle,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = bookmark.label,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Bookmark,
                                        contentDescription = "Bookmark",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (filteredAndSortedDocuments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = "No books",
                                tint = Color.DarkGray,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No match found" else "No books in Library",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(filteredAndSortedDocuments, key = { it.id }) { doc ->
                            DocumentCard(
                                document = doc,
                                onSelect = {
                                    hapticTrigger()
                                    onSelectDocument(doc)
                                },
                                onLongSelect = {
                                    hapticTrigger()
                                    activeOptionsDoc = doc
                                },
                                cardWidth = 95.dp,
                                cardHeight = 125.dp
                            )
                        }
                    }
                }
            }
        }
    }

    // Collection assignment dialog
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionAssignDialog(
    document: Document,
    allCollections: List<CollectionEntity>,
    allCrossRefs: List<DocumentCollectionCrossRef>,
    onDismiss: () -> Unit,
    onAddRelation: (Long) -> Unit,
    onRemoveRelation: (Long) -> Unit,
    onCreateCollection: (String) -> Unit,
    onDeleteCollection: (CollectionEntity) -> Unit
) {
    var newCollectionName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add to Collection",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Assign \"${document.title}\" to folders:",
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                // Scrollable collections list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(Color(0xFF0F0F10), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF242426), RoundedCornerShape(12.dp))
                ) {
                    if (allCollections.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No collections created yet.",
                                fontSize = 12.sp,
                                color = Color.DarkGray
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            items(allCollections, key = { it.id }) { col ->
                                val isChecked = remember(allCrossRefs, col.id, document.id) {
                                    allCrossRefs.any { it.collectionId == col.id && it.documentId == document.id }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isChecked) {
                                                onRemoveRelation(col.id)
                                            } else {
                                                onAddRelation(col.id)
                                            }
                                        }
                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                onAddRelation(col.id)
                                            } else {
                                                onRemoveRelation(col.id)
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = MaterialTheme.colorScheme.primary,
                                            uncheckedColor = Color.Gray,
                                            checkmarkColor = Color.White
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = col.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onDeleteCollection(col) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Collection",
                                            tint = Color.DarkGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Add collection text input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newCollectionName,
                        onValueChange = { newCollectionName = it },
                        placeholder = { Text("New collection name...", color = Color.Gray, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF242426),
                            focusedContainerColor = Color(0xFF0F0F10),
                            unfocusedContainerColor = Color(0xFF0F0F10),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            if (newCollectionName.isNotBlank()) {
                                onCreateCollection(newCollectionName.trim())
                                newCollectionName = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Collection",
                            tint = Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D1D20)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242426)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Done", color = Color.White)
            }
        },
        containerColor = Color(0xFF141416),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun FilterChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val hapticTrigger = rememberHapticTrigger()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF141416))
            .border(
                1.dp,
                if (selected) Color.Transparent else Color(0xFF242426),
                RoundedCornerShape(20.dp)
            )
            .clickable {
                hapticTrigger()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.Gray,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
