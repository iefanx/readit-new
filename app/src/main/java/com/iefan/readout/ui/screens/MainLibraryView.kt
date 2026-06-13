package com.iefan.readout.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.data.Document
import com.iefan.readout.ui.components.styleOfCaption
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
    onSelectDocument: (Document) -> Unit,
    onDeleteDocument: (Document) -> Unit,
    onAddDocument: (String, String, String?, String?) -> Unit,
    onOpenBenchmark: () -> Unit,
    isImporting: Boolean = false,
    onUrlImport: (String, String?) -> Unit = { _, _ -> },
    onUriImport: (Uri, String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var activeInputType by remember { mutableStateOf(AddInputType.PASTE) }
    
    // State management for raw picked uri
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    val context = LocalContext.current

    // Activity launcher for picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
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
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = "▲",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
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
                    IconButton(onClick = onOpenBenchmark) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Hardware Benchmark",
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
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Elegant, clean non-clickable static hero banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF261066), Color(0xFF09090A))
                                )
                            )
                            .border(1.dp, Color(0xFF24242A), RoundedCornerShape(24.dp))
                            .padding(24.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "TRANSFORM YOUR READING",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                                color = Color(0xFF9E82F5)
                            )
                            Text(
                                text = "Upload any document and read it as an audiobook in your preferred speed",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }

                item {
                    // Heading 1: Text to Speech
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Text to Speech",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))

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
                                    fileLauncher.launch("*/*")
                                },
                                modifier = Modifier.weight(1f)
                            )
                            // Paste Text Action Card
                            ActionCard(
                                title = "Paste Text",
                                icon = Icons.Default.ContentPaste,
                                onClick = { 
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
                                    selectedFileUri = null
                                    activeInputType = AddInputType.URL
                                    showAddDialog = true 
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
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
                                items(allDocuments) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onSelect = { onSelectDocument(doc) },
                                        onDelete = { onDeleteDocument(doc) }
                                    )
                                }
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(40.dp))
                }
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
                    onUriImport(uri, title)
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
                            color = Color(0xFF7F4FFD),
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
            .height(110.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF141416)
        )
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
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun DocumentCard(
    document: Document,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val isPastedText = document.sourceUrl.isNullOrEmpty() || document.title.lowercase().contains("paste")
    
    // Retrieve cover photo locally if present or render a clean typographic preview
    val localCoverBitmap = remember(document.coverPath) {
        document.coverPath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .width(135.dp)
            .padding(bottom = 8.dp)
    ) {
        // Document Cover Container
        Box(
            modifier = Modifier
                .width(135.dp)
                .height(175.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onSelect() }
                .testTag("document_card_${document.id}")
        ) {
            if (localCoverBitmap != null) {
                // Real Extracted Vector/PDF Thumbnail Preview Cover
                Image(
                    bitmap = localCoverBitmap,
                    contentDescription = "Document Cover Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isPastedText) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF3F1D77), // Premium dark purple
                                    Color(0xFF160E2A)  // Deep indigo
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pasted Text",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                // Paper document look with elegant styling
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 16.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .fillMaxSize()
                    ) {
                        Text(
                            text = document.title,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 4,
                            lineHeight = 10.sp,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Fake visual line segments representing real paragraphs
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (i in 0 until 12) {
                                val lineFraction = if (i % 4 == 0) 0.5f else if (i % 6 == 0) 0.3f else 0.95f
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(lineFraction)
                                        .height(2.5.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(Color.Black.copy(alpha = 0.08f))
                                )
                            }
                        }
                    }
                }
            }

            // Small, sleek, round floating Delete Button on top of card
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }

            // Visual Progress Strip at the bottom of the cover
            val percentage = if (document.content.isNotEmpty()) {
                (document.playbackPosition.getOrZeroPercent().toFloat() / document.content.length.toFloat()).coerceIn(0f, 1f)
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
                        .background(Color(0xFF7F4FFD))
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

        Spacer(modifier = Modifier.height(2.dp))

        val positionPercentage = if (document.content.isNotEmpty()) {
            (document.playbackPosition.getOrZeroPercent() * 100 / document.content.length).coerceIn(0, 100)
        } else 0
        
        Text(
            text = "Progress: $positionPercentage%",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF9E82F5)
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

    // Set initial title when a file is picked
    LaunchedEffect(selectedUri, selectedFileName) {
        if (selectedUri != null && currentTab == AddInputType.FILE) {
            title = selectedFileName.substringBeforeLast(".")
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
                                .background(if (isSelected) Color(0xFF7F4FFD) else Color.Transparent)
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
                                    focusedBorderColor = Color(0xFF7F4FFD),
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
                                color = Color(0xFF9E82F5)
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
                                        url = "https://example.com/edge-ai-revolution"
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1D1B28),
                                        contentColor = Color(0xFF9E82F5)
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
                                        url = "https://example.com/mindful-computing"
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1D1B28),
                                        contentColor = Color(0xFF9E82F5)
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
                                    focusedBorderColor = Color(0xFF7F4FFD),
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
                                    focusedBorderColor = Color(0xFF7F4FFD),
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
                                    focusedBorderColor = Color(0xFF7F4FFD),
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
                                    focusedBorderColor = Color(0xFF7F4FFD),
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
                                onAdd(title, content, url.ifBlank { null })
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
                    containerColor = Color(0xFF7F4FFD),
                    disabledContainerColor = Color(0xFF32284E)
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
