package com.iefan.readout.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DiscoveredDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val extension: String,
    val lastModified: Long,
    val pathHint: String? = null
)

enum class DocumentScanFormat(
    val title: String,
    val subtitle: String,
    val extensions: Set<String>,
    val badgeColor: Color,
    val icon: ImageVector
) {
    EPUB(
        title = "EPUB Books",
        subtitle = "Standard electronic books (.epub)",
        extensions = setOf("epub"),
        badgeColor = Color(0xFFA855F7), // Violet
        icon = Icons.AutoMirrored.Filled.MenuBook
    ),
    PDF(
        title = "PDF Documents",
        subtitle = "Textbooks, papers & reports (.pdf)",
        extensions = setOf("pdf"),
        badgeColor = Color(0xFFFF5252), // Coral Red
        icon = Icons.Default.PictureAsPdf
    ),
    TEXT(
        title = "Word & Text",
        subtitle = "Word documents & plain text (.docx, .txt)",
        extensions = setOf("docx", "txt"),
        badgeColor = Color(0xFF3B82F6), // Blue
        icon = Icons.AutoMirrored.Filled.Article
    ),
    WEB(
        title = "Web Pages",
        subtitle = "Saved offline web articles (.html, .htm)",
        extensions = setOf("html", "htm"),
        badgeColor = Color(0xFF10B981), // Emerald
        icon = Icons.Default.Language
    )
}

enum class ScannerStep {
    CONFIG,
    SCANNING,
    RESULTS
}

enum class DiscoveredDocSort(val label: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    NAME_ASC("Name (A to Z)"),
    NAME_DESC("Name (Z to A)"),
    SIZE_DESC("Largest First"),
    SIZE_ASC("Smallest First")
}

/**
 * Checks whether full device storage access or read external storage permission is granted.
 */
fun hasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Requests storage permission by opening Android Settings.
 */
fun requestStoragePermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    } else {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Scans a user-selected folder tree via Storage Access Framework (SAF).
 * Does not require MANAGE_EXTERNAL_STORAGE permission.
 */
suspend fun scanDocumentTree(
    context: Context,
    treeUri: Uri,
    formats: Set<DocumentScanFormat>
): List<DiscoveredDocument> = withContext(Dispatchers.IO) {
    val results = mutableListOf<DiscoveredDocument>()
    val seenNames = mutableSetOf<String>()
    val supportedExtensions = formats.flatMap { it.extensions }.map { it.lowercase() }.toSet()
    if (supportedExtensions.isEmpty()) return@withContext emptyList()

    try {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: Throwable) {}

        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)

        fun traverse(docId: String, depth: Int) {
            if (depth > 4) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childId = if (idCol != -1) cursor.getString(idCol) ?: "" else ""
                    val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "" else ""
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val lastMod = if (modCol != -1) cursor.getLong(modCol) else 0L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (childId.isNotBlank()) {
                            traverse(childId, depth + 1)
                        }
                    } else {
                        val ext = name.substringAfterLast(".", "").lowercase()
                        if (ext in supportedExtensions && seenNames.add(name.lowercase())) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            results.add(
                                DiscoveredDocument(
                                    uri = fileUri,
                                    name = name,
                                    sizeBytes = size,
                                    extension = ext.uppercase(),
                                    lastModified = lastMod
                                )
                            )
                        }
                    }
                }
            }
        }

        traverse(treeDocId, 0)
    } catch (_: Throwable) {}

    results.sortedByDescending { it.lastModified }
}

/**
 * Scans device storage (Downloads, Documents, MediaStore, public root) for user-selected document formats.
 */
suspend fun scanDeviceForDocuments(
    context: Context,
    formats: Set<DocumentScanFormat> = DocumentScanFormat.values().toSet()
): List<DiscoveredDocument> = withContext(Dispatchers.IO) {
    val results = mutableListOf<DiscoveredDocument>()
    val seenNames = mutableSetOf<String>()

    val supportedExtensions = formats.flatMap { it.extensions }.map { it.lowercase() }.toSet()
    if (supportedExtensions.isEmpty()) return@withContext emptyList()

    val root = Environment.getExternalStorageDirectory()
    val searchFolders = listOfNotNull(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        File(root, "Download"),
        File(root, "Downloads"),
        File(root, "Documents"),
        File(root, "Books"),
        File(root, "eBooks"),
        File(root, "ReadEra"),
        File(root, "Kindle"),
        root
    ).distinct()

    for (folder in searchFolders) {
        try {
            if (folder.exists() && folder.isDirectory && folder.canRead()) {
                val maxDepth = if (folder == root) 3 else 4
                folder.walkTopDown()
                    .maxDepth(maxDepth)
                    .onEnter { dir ->
                        val dName = dir.name.lowercase()
                        !dName.startsWith(".") && dName != "android" && dName != "data" && dName != "obb"
                    }
                    .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                    .forEach { file ->
                        val lowerName = file.name.lowercase()
                        if (seenNames.add(lowerName)) {
                            results.add(
                                DiscoveredDocument(
                                    uri = Uri.fromFile(file),
                                    name = file.name,
                                    sizeBytes = file.length(),
                                    extension = file.extension.uppercase(),
                                    lastModified = file.lastModified(),
                                    pathHint = file.parentFile?.name
                                )
                            )
                        }
                    }
            }
        } catch (_: Exception) {}
    }

    // 2. Query MediaStore.Files for indexed documents
    try {
        val queryUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val conditions = supportedExtensions.map { ext ->
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.$ext'"
        }
        val selection = conditions.joinToString(" OR ")

        context.contentResolver.query(
            queryUri,
            projection,
            selection,
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                val lowerName = name.lowercase()
                val ext = name.substringAfterLast(".", "").uppercase()
                if (name.isNotBlank() && ext.lowercase() in supportedExtensions && seenNames.add(lowerName)) {
                    val id = if (idCol != -1) cursor.getLong(idCol) else 0L
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else 0L
                    val docUri = ContentUris.withAppendedId(queryUri, id)
                    results.add(
                        DiscoveredDocument(
                            uri = docUri,
                            name = name,
                            sizeBytes = size,
                            extension = ext,
                            lastModified = date
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {}

    results.sortedByDescending { it.lastModified }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

fun formatDocumentDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val oneDay = 24 * 60 * 60 * 1000L
    return when {
        diff < 0 -> ""
        diff < 60 * 60 * 1000L -> "${(diff / (60 * 1000L)).coerceAtLeast(1)}m ago"
        diff < oneDay -> "${diff / (60 * 60 * 1000L)}h ago"
        diff < 7 * oneDay -> "${diff / oneDay}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

fun cleanDiscoveredTitle(rawName: String): String {
    val clean = rawName.substringBeforeLast(".")
    val spaced = clean.replace(Regex("[_\\-]+"), " ")
    return spaced.split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        }
        .ifBlank { rawName }
}

@Composable
private fun ScanningRadarView(formatsText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(170.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )

            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FindInPage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Scanning Device Storage...",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Locating $formatsText across storage, books & downloads",
            fontSize = 13.sp,
            color = Color(0xFF8E8E93),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScannerSheet(
    onDismiss: () -> Unit,
    onSelectDocument: (Uri, String) -> Unit,
    onImportMultipleDocuments: ((List<DiscoveredDocument>) -> Unit)? = null,
    onBrowseAll: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHapticTrigger()

    var currentStep by remember { mutableStateOf(ScannerStep.CONFIG) }

    var selectedFormats by remember {
        mutableStateOf(
            setOf(DocumentScanFormat.EPUB, DocumentScanFormat.PDF, DocumentScanFormat.TEXT)
        )
    }

    var documents by remember { mutableStateOf<List<DiscoveredDocument>?>(null) }
    var selectedDocuments by remember { mutableStateOf<Set<DiscoveredDocument>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFilterFormat by remember { mutableStateOf<DocumentScanFormat?>(null) }
    var currentSort by remember { mutableStateOf(DiscoveredDocSort.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var hasPerm by remember { mutableStateOf(hasStoragePermission(context)) }

    LaunchedEffect(Unit) {
        hasPerm = hasStoragePermission(context)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            currentStep = ScannerStep.SCANNING
            scope.launch {
                val results = scanDocumentTree(context, treeUri, selectedFormats)
                documents = results
                selectedDocuments = emptySet()
                currentStep = ScannerStep.RESULTS
            }
        }
    }

    fun performScan() {
        hasPerm = hasStoragePermission(context)
        currentStep = ScannerStep.SCANNING
        scope.launch {
            val results = scanDeviceForDocuments(context, selectedFormats)
            documents = results
            selectedDocuments = emptySet()
            currentStep = ScannerStep.RESULTS
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BackHandler {
        when (currentStep) {
            ScannerStep.RESULTS -> currentStep = ScannerStep.CONFIG
            ScannerStep.SCANNING -> currentStep = ScannerStep.CONFIG
            ScannerStep.CONFIG -> onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101014),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp),
                color = Color(0xFF383842),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 38.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
        ) {
            when (currentStep) {
                ScannerStep.CONFIG -> {
                    LaunchedEffect(Unit) {
                        hasPerm = hasStoragePermission(context)
                    }

                    // Top Bar (Fixed)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
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
                                    Icon(
                                        imageVector = Icons.Default.FindInPage,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = "Scan Device for Files",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Search local storage for readable books & files",
                                        fontSize = 12.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E1E24))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color(0xFFAAAAAA),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Middle Scrollable Content (Weight 1f)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        if (!hasPerm) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF221A14),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Storage Permission Recommended",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Text(
                                        text = "Android restricts full storage scans by default. Grant 'All Files Access' in Settings to scan all folders, or pick a specific folder without permissions.",
                                        fontSize = 11.5.sp,
                                        color = Color(0xFFB0B0B8),
                                        lineHeight = 15.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { requestStoragePermission(context) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp)
                                        ) {
                                            Text("Grant All Files", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                        OutlinedButton(
                                            onClick = { folderPickerLauncher.launch(null) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = BorderStroke(1.dp, Color(0xFF484852)),
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp)
                                        ) {
                                            Text("Scan Folder...", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        Text(
                            text = "FILE TYPES TO SEARCH",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7A7A85),
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DocumentScanFormat.values().forEach { format ->
                                val isSelected = selectedFormats.contains(format)
                                Surface(
                                    onClick = {
                                        haptic()
                                        selectedFormats = if (isSelected) selectedFormats - format else selectedFormats + format
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) Color(0xFF181822) else Color(0xFF131317),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) format.badgeColor.copy(alpha = 0.5f) else Color(0xFF222228)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(format.badgeColor.copy(alpha = 0.18f))
                                                .border(1.dp, format.badgeColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = format.icon,
                                                contentDescription = null,
                                                tint = format.badgeColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = format.title,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = format.subtitle,
                                                fontSize = 11.5.sp,
                                                color = Color(0xFF8E8E93)
                                            )
                                        }

                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                haptic()
                                                selectedFormats = if (checked) selectedFormats + format else selectedFormats - format
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = format.badgeColor,
                                                uncheckedColor = Color(0xFF484850),
                                                checkmarkColor = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedFormats.size} formats selected",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { selectedFormats = DocumentScanFormat.values().toSet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Select All", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                TextButton(
                                    onClick = { selectedFormats = emptySet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Clear", fontSize = 12.sp, color = Color(0xFF8E8E93))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Bottom Bar (Fixed - always visible above navigation bar)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF131318),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { performScan() },
                                enabled = selectedFormats.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (selectedFormats.isEmpty()) "Select at least 1 format" else "Start Device Scan",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0B0B8))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Choose Specific Folder to Scan", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                ScannerStep.SCANNING -> {
                    val formatsText = selectedFormats.joinToString { it.title.substringBefore(" ") }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            ScanningRadarView(formatsText = formatsText)
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF131318),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { currentStep = ScannerStep.CONFIG },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0B0B8))
                                ) {
                                    Text("Cancel Scan", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                ScannerStep.RESULTS -> {
                    val allFoundDocs = documents ?: emptyList()

                    val filteredAndSortedDocs = remember(allFoundDocs, searchQuery, selectedFilterFormat, currentSort) {
                        var result = allFoundDocs
                        if (selectedFilterFormat != null) {
                            result = result.filter { doc ->
                                selectedFilterFormat!!.extensions.contains(doc.extension.lowercase())
                            }
                        }
                        if (searchQuery.isNotBlank()) {
                            val q = searchQuery.trim().lowercase()
                            result = result.filter {
                                it.name.lowercase().contains(q) || cleanDiscoveredTitle(it.name).lowercase().contains(q)
                            }
                        }
                        when (currentSort) {
                            DiscoveredDocSort.DATE_DESC -> result.sortedByDescending { it.lastModified }
                            DiscoveredDocSort.DATE_ASC -> result.sortedBy { it.lastModified }
                            DiscoveredDocSort.NAME_ASC -> result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                            DiscoveredDocSort.NAME_DESC -> result.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
                            DiscoveredDocSort.SIZE_DESC -> result.sortedByDescending { it.sizeBytes }
                            DiscoveredDocSort.SIZE_ASC -> result.sortedBy { it.sizeBytes }
                        }
                    }

                    // Results Top Bar (Fixed)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF101014))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(
                                    onClick = { currentStep = ScannerStep.CONFIG },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1E1E24))
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Scan Setup",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Discovered Documents",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "${allFoundDocs.size}",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = currentSort.label,
                                        fontSize = 11.5.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        isSearchActive = !isSearchActive
                                        if (!isSearchActive) searchQuery = ""
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = if (isSearchActive) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                }

                                Box {
                                    IconButton(onClick = { showSortMenu = true }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Sort,
                                            contentDescription = "Sort",
                                            tint = Color.White
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false },
                                        modifier = Modifier.background(Color(0xFF1C1C22))
                                    ) {
                                        DiscoveredDocSort.values().forEach { sort ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        if (currentSort == sort) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        } else {
                                                            Spacer(modifier = Modifier.size(16.dp))
                                                        }
                                                        Text(
                                                            text = sort.label,
                                                            color = if (currentSort == sort) MaterialTheme.colorScheme.primary else Color.White
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    currentSort = sort
                                                    showSortMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(visible = isSearchActive) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search by filename...", fontSize = 13.5.sp, color = Color(0xFF6E6E78)) },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(18.dp))
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF8E8E93), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF16161C),
                                    unfocusedContainerColor = Color(0xFF16161C),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF282832),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        // Format filter chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedFilterFormat == null,
                                onClick = { selectedFilterFormat = null },
                                label = { Text("All (${allFoundDocs.size})", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            selectedFormats.forEach { format ->
                                val count = allFoundDocs.count { doc -> format.extensions.contains(doc.extension.lowercase()) }
                                if (count > 0) {
                                    FilterChip(
                                        selected = selectedFilterFormat == format,
                                        onClick = {
                                            selectedFilterFormat = if (selectedFilterFormat == format) null else format
                                        },
                                        label = { Text("${format.title.substringBefore(" ")} ($count)", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = format.badgeColor.copy(alpha = 0.2f),
                                            selectedLabelColor = format.badgeColor
                                        )
                                    )
                                }
                            }
                        }

                        // Selection summary row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (selectedDocuments.isEmpty()) "${filteredAndSortedDocs.size} documents found" else "${selectedDocuments.size} selected",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93)
                            )

                            if (filteredAndSortedDocs.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        selectedDocuments = if (selectedDocuments.size == filteredAndSortedDocs.size) {
                                            emptySet()
                                        } else {
                                            filteredAndSortedDocs.toSet()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (selectedDocuments.size == filteredAndSortedDocs.size) "Deselect All" else "Select All",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Middle Content (Weight 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (filteredAndSortedDocs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (searchQuery.isNotBlank()) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.SearchOff, contentDescription = null, tint = Color(0xFF555560), modifier = Modifier.size(48.dp))
                                        Text("No documents match '$searchQuery'", fontSize = 14.sp, color = Color.White)
                                        TextButton(onClick = { searchQuery = "" }) {
                                            Text("Clear Search", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(24.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF555560), modifier = Modifier.size(52.dp))
                                        Text("No documents found in storage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        Text(
                                            text = "Try picking a specific folder directly or browse files manually.",
                                            fontSize = 12.5.sp,
                                            color = Color(0xFF8E8E93),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) {
                                                Text("Scan Folder...", fontSize = 12.sp)
                                            }
                                            Button(onClick = { onDismiss(); onBrowseAll() }) {
                                                Text("Browse Manually", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(filteredAndSortedDocs, key = { it.uri.toString() + it.name }) { doc ->
                                    val isChecked = selectedDocuments.contains(doc)
                                    DiscoveredDocumentSelectableRow(
                                        doc = doc,
                                        isSelected = isChecked,
                                        onToggle = {
                                            selectedDocuments = if (isChecked) selectedDocuments - doc else selectedDocuments + doc
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Bar (Fixed - always visible above navigation bar)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF131318),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            if (selectedDocuments.size > 1) {
                                Button(
                                    onClick = {
                                        haptic()
                                        onDismiss()
                                        onImportMultipleDocuments?.invoke(selectedDocuments.toList())
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Import ${selectedDocuments.size} Documents to Library",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else if (selectedDocuments.size == 1) {
                                val singleDoc = selectedDocuments.first()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            haptic()
                                            onDismiss()
                                            onSelectDocument(singleDoc.uri, singleDoc.name)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                    ) {
                                        Text("Customize & Add", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    Button(
                                        onClick = {
                                            haptic()
                                            onDismiss()
                                            if (onImportMultipleDocuments != null) {
                                                onImportMultipleDocuments(listOf(singleDoc))
                                            } else {
                                                onSelectDocument(singleDoc.uri, singleDoc.name)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Add to Library", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onDismiss()
                                            onBrowseAll()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0B0B8)),
                                        border = BorderStroke(1.dp, Color(0xFF2E2E38))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Browse Manually", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }

                                    Button(
                                        onClick = { currentStep = ScannerStep.CONFIG },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Re-scan Storage", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDocumentSelectableRow(
    doc: DiscoveredDocument,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val haptic = rememberHapticTrigger()
    val extColor = when (doc.extension.uppercase()) {
        "EPUB" -> Color(0xFFA855F7) // Violet
        "PDF" -> Color(0xFFFF5252)  // Coral Red
        "DOCX" -> Color(0xFF3B82F6) // Blue
        "TXT" -> Color(0xFF10B981)  // Emerald
        "HTML", "HTM" -> Color(0xFFF59E0B) // Amber
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        onClick = {
            haptic()
            onToggle()
        },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color(0xFF1E1E28) else Color(0xFF141418),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color(0xFF222228)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = {
                    haptic()
                    onToggle()
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = Color(0xFF484850),
                    checkmarkColor = Color.White
                ),
                modifier = Modifier.size(22.dp)
            )

            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(extColor.copy(alpha = 0.16f))
                    .border(1.dp, extColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = doc.extension.uppercase(),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = extColor,
                    letterSpacing = 0.5.sp
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cleanDiscoveredTitle(doc.name),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (doc.sizeBytes > 0) {
                        Text(
                            text = formatFileSize(doc.sizeBytes),
                            fontSize = 11.5.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }
                    val dateStr = formatDocumentDate(doc.lastModified)
                    if (dateStr.isNotBlank()) {
                        Text(
                            text = "•",
                            fontSize = 11.5.sp,
                            color = Color(0xFF55555C)
                        )
                        Text(
                            text = dateStr,
                            fontSize = 11.5.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }
                    if (!doc.pathHint.isNullOrBlank()) {
                        Text(
                            text = "•",
                            fontSize = 11.5.sp,
                            color = Color(0xFF55555C)
                        )
                        Text(
                            text = doc.pathHint,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
