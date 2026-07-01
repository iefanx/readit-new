package com.iefan.readout.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.data.Bookmark
import com.iefan.readout.data.Chapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TocTab { CHAPTERS, BOOKMARKS }

/**
 * A [ModalBottomSheet] that shows a tabbed interface for Chapters and Bookmarks.
 *
 * Behavior:
 * - If [chapters] is empty, only the Bookmarks tab is shown (selected by default).
 * - If [chapters] is non-empty, both tabs are shown; Chapters is selected by default.
 *
 * @param chapters           List of extracted chapters for the current document.
 * @param bookmarks          List of saved bookmarks for the current document.
 * @param currentCharOffset  The character offset of the currently playing sentence.
 * @param onChapterSelected  Called when the user taps a chapter row.
 * @param onBookmarkSelected Called when the user taps a bookmark row.
 * @param onBookmarkDeleted  Called when the user taps the delete icon on a bookmark.
 * @param onDismiss          Called to close the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableOfContentsSheet(
    chapters: List<Chapter>,
    bookmarks: List<Bookmark>,
    currentCharOffset: Int,
    onChapterSelected: (Chapter) -> Unit,
    onBookmarkSelected: (Bookmark) -> Unit,
    onBookmarkDeleted: (Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    val hasChapters = chapters.isNotEmpty()
    val initialTab = if (hasChapters) TocTab.CHAPTERS else TocTab.BOOKMARKS
    var selectedTab by remember { mutableStateOf(initialTab) }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141416),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A3A3C))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
        ) {

            // ── Tab Row ──────────────────────────────────────────────────────
            if (hasChapters) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TocTabChip(
                        label = "Chapters",
                        icon = { Icon(Icons.AutoMirrored.Filled.Toc, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        selected = selectedTab == TocTab.CHAPTERS,
                        count = chapters.size,
                        onClick = { selectedTab = TocTab.CHAPTERS },
                        modifier = Modifier.weight(1f)
                    )
                    TocTabChip(
                        label = "Bookmarks",
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        selected = selectedTab == TocTab.BOOKMARKS,
                        count = bookmarks.size,
                        onClick = { selectedTab = TocTab.BOOKMARKS },
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(color = Color(0xFF2C2C2E), thickness = 0.5.dp)
            } else {
                // No chapters — show a fixed Bookmarks header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 4.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Bookmarks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (bookmarks.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${bookmarks.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8E8E93)
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF2C2C2E), thickness = 0.5.dp)
            }

            // ── Content ──────────────────────────────────────────────────────
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (targetState == TocTab.BOOKMARKS) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                                slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                                slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "toc_tab_anim",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { tab ->
                when (tab) {
                    TocTab.CHAPTERS -> ChaptersContent(
                        chapters = chapters,
                        currentCharOffset = currentCharOffset,
                        onChapterSelected = {
                            onChapterSelected(it)
                            onDismiss()
                        }
                    )
                    TocTab.BOOKMARKS -> BookmarksContent(
                        bookmarks = bookmarks,
                        onBookmarkSelected = {
                            onBookmarkSelected(it)
                            onDismiss()
                        },
                        onBookmarkDeleted = onBookmarkDeleted
                    )
                }
            }
        }
    }
}

// ─── Chapters tab content ────────────────────────────────────────────────────

@Composable
private fun ChaptersContent(
    chapters: List<Chapter>,
    currentCharOffset: Int,
    onChapterSelected: (Chapter) -> Unit
) {
    val activeChapter = remember(chapters, currentCharOffset) {
        chapters.lastOrNull { currentCharOffset >= it.startCharOffset } ?: chapters.firstOrNull()
    }
    val activeIndex = remember(chapters, activeChapter) {
        if (activeChapter != null) chapters.indexOf(activeChapter) else -1
    }
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex in chapters.indices) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    if (chapters.isEmpty()) {
        EmptyStateMessage(message = "No chapters found in this document.")
    } else {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(chapters) { index, chapter ->
                val isSelected = chapter == activeChapter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color(0xFF1C1C1E)
                        )
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            else Color(0xFF2C2C2E),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { onChapterSelected(chapter) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Chapter index badge
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                else Color(0xFF2C2C2E)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93)
                        )
                    }
                    Text(
                        text = chapter.title.ifBlank { "Chapter ${index + 1}" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

// ─── Bookmarks tab content ───────────────────────────────────────────────────

@Composable
private fun BookmarksContent(
    bookmarks: List<Bookmark>,
    onBookmarkSelected: (Bookmark) -> Unit,
    onBookmarkDeleted: (Bookmark) -> Unit
) {
    if (bookmarks.isEmpty()) {
        EmptyStateMessage(
            message = "No bookmarks yet.\nLong-press any line while listening to add one."
        )
    } else {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    onClick = { onBookmarkSelected(bookmark) },
                    onDelete = { onBookmarkDeleted(bookmark) }
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateLabel = remember(bookmark.createdAt) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(bookmark.createdAt))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1E))
            .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.BookmarkBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = bookmark.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E8E93),
                fontSize = 11.sp
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete bookmark",
                tint = Color(0xFF48484A),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Shared tab chip ─────────────────────────────────────────────────────────

@Composable
private fun TocTabChip(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color(0xFF1C1C1E)
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFF2C2C2E),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93)
        ) {
            icon()
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93)
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else Color(0xFF2C2C2E)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$count",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93)
                )
            }
        }
    }
}

// ─── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF48484A),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}
