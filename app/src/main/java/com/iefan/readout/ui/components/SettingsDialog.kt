package com.iefan.readout.ui.components

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iefan.readout.tts.VoiceInfo
import com.iefan.readout.tts.VoiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initialScreen: Int = 0,
    selectedVoiceId: String,
    previewingVoiceId: String? = null,
    availableVoices: List<VoiceInfo>,
    onSelectVoice: (String) -> Unit,
    onOfflineOnlyChange: (Boolean) -> Unit = {},
    onPreviewVoice: (String, String, java.util.Locale) -> Unit = { _, _, _ -> },
    translationTargetLang: String,
    onSelectTranslationLang: (String) -> Unit,
    themeColor: Color,
    onThemeColorChange: (Color) -> Unit,
    onImportData: () -> Unit,
    onExportData: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var offlineOnly by remember { mutableStateOf(context.getSharedPreferences("readout_prefs", android.content.Context.MODE_PRIVATE).getBoolean("offline_only", true)) }
    var currentScreen by remember(initialScreen) { mutableIntStateOf(initialScreen) } // 0 = Main Settings, 1 = Voice Selection, 2 = Translation, 3 = Theme Color
    var translationSearchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val translationLanguages = listOf(
        Pair("none", "Original Text (No Translation)"),
        Pair("en", "English"),
        Pair("es", "Spanish"),
        Pair("fr", "French"),
        Pair("de", "German"),
        Pair("it", "Italian"),
        Pair("pt", "Portuguese"),
        Pair("ru", "Russian"),
        Pair("hi", "Hindi"),
        Pair("bn", "Bengali"),
        Pair("zh", "Chinese (Simplified)"),
        Pair("ja", "Japanese"),
        Pair("ko", "Korean"),
        Pair("ar", "Arabic"),
        Pair("nl", "Dutch"),
        Pair("tr", "Turkish"),
        Pair("pl", "Polish"),
        Pair("vi", "Vietnamese"),
        Pair("id", "Indonesian"),
        Pair("uk", "Ukrainian"),
        Pair("ur", "Urdu"),
        Pair("ta", "Tamil"),
        Pair("te", "Telugu"),
        Pair("mr", "Marathi")
    )

    val currentVoiceName = remember(selectedVoiceId, availableVoices) {
        if (selectedVoiceId == "default" || selectedVoiceId.isEmpty()) {
            "Smart Autoselect (Default)"
        } else {
            availableVoices.firstOrNull { it.id == selectedVoiceId }?.displayName ?: selectedVoiceId
        }
    }

    val currentTranslationName = remember(translationTargetLang) {
        translationLanguages.firstOrNull { it.first == translationTargetLang }?.second ?: "Original Text"
    }

    BackHandler {
        if (currentScreen != 0) {
            currentScreen = 0
        } else {
            onDismiss()
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
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Compact Modern Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentScreen == 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { currentScreen = 0 },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to settings",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = when (currentScreen) {
                                    1 -> "Voice Persona"
                                    2 -> "Translation"
                                    else -> "Theme Accent"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    thickness = 0.5.dp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Content depending on current screen
                when (currentScreen) {
                    0 -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Use offline voices only")
                                    Text("Online voices may send text to your speech provider. Translation always uses online services.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = offlineOnly, onCheckedChange = { offlineOnly = it; onOfflineOnlyChange(it) })
                            }
                            // Category: Voice Selection
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF181820))
                                    .border(
                                        BorderStroke(1.dp, Color(0xFF282834)),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable { currentScreen = 1 }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = "Voice Persona",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Voice Persona",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = currentVoiceName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF9E9EA8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Go to voice selection",
                                    tint = Color(0xFF6B6B78),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Category: Translation Language
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF181820))
                                    .border(
                                        BorderStroke(1.dp, Color(0xFF282834)),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable { currentScreen = 2 }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Translate,
                                        contentDescription = "Translation",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Translation Language (online)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = currentTranslationName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF9E9EA8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Go to translation settings",
                                    tint = Color(0xFF6B6B78),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Category: Theme Color
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF181820))
                                    .border(
                                        BorderStroke(1.dp, Color(0xFF282834)),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable { currentScreen = 3 }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Palette,
                                        contentDescription = "Theme Color",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Theme Accent Color",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(themeColor)
                                        )
                                        Text(
                                            text = String.format("#%06X", 0xFFFFFF and themeColor.toArgb()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF9E9EA8)
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Go to theme color settings",
                                    tint = Color(0xFF6B6B78),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Category: Backup & Restore
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF181820))
                                    .border(
                                        BorderStroke(1.dp, Color(0xFF282834)),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Storage,
                                            contentDescription = "Backup & Restore",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Backup & Restore",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Export or restore library and bookmarks",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF9E9EA8)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onImportData,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF22222C),
                                            contentColor = Color.White
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF333342)),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Upload,
                                            contentDescription = "Import",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Import",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }

                                    Button(
                                        onClick = onExportData,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF22222C),
                                            contentColor = Color.White
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF333342)),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Export",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Export",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. Smart Autoselect Option
                            item {
                                val isAutoselect = selectedVoiceId == "default" || selectedVoiceId.isEmpty()
                                val isPreviewing = previewingVoiceId == "default"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isAutoselect) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else Color(0xFF181820)
                                        )
                                        .border(
                                            1.dp,
                                            if (isAutoselect) MaterialTheme.colorScheme.primary
                                            else Color(0xFF282834),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable {
                                            onSelectVoice("default")
                                            onPreviewVoice("default", "Default Voice", java.util.Locale.getDefault())
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Auto Select",
                                        tint = if (isAutoselect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Smart Autoselect (Default)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAutoselect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Intelligently selects the highest-scoring Neural or Wavenet voice online, falling back to enhanced offline models.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 14.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onPreviewVoice("default", "Default Voice", java.util.Locale.getDefault())
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Audition Voice",
                                            tint = if (isPreviewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (isAutoselect) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            // 2. Individual Voice Persona Items
                            items(availableVoices) { voiceInfo ->
                                val isSelected = selectedVoiceId == voiceInfo.id
                                val isPreviewing = previewingVoiceId == voiceInfo.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else Color(0xFF181820)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color(0xFF282834),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable {
                                            onSelectVoice(voiceInfo.id)
                                            onPreviewVoice(voiceInfo.id, voiceInfo.displayName, voiceInfo.locale)
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = voiceInfo.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            // Minimalist status icon
                                            if (voiceInfo.status == VoiceStatus.DOWNLOADED) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.DownloadDone,
                                                    contentDescription = "Downloaded / Offline Ready",
                                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            } else if (voiceInfo.status == VoiceStatus.DOWNLOADABLE) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Download,
                                                    contentDescription = "Download Required",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "System identifier: ${voiceInfo.id}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onPreviewVoice(voiceInfo.id, voiceInfo.displayName, voiceInfo.locale)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Audition Voice",
                                            tint = if (isPreviewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Direct System Install Link Button
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val fallback = Intent("com.android.settings.TTS_SETTINGS")
                                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(fallback)
                                    } catch (err: Exception) {
                                        Log.e("SettingsDialog", "Failed to launch TTS settings", err)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Install Voices",
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Install Offline Voices & Language Packs",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    2 -> {
                        val filteredTranslationLanguages = remember(translationLanguages, translationSearchQuery) {
                            if (translationSearchQuery.isBlank()) translationLanguages
                            else translationLanguages.filter {
                                it.second.contains(translationSearchQuery, ignoreCase = true) ||
                                it.first.contains(translationSearchQuery, ignoreCase = true)
                            }
                        }

                        OutlinedTextField(
                            value = translationSearchQuery,
                            onValueChange = { translationSearchQuery = it },
                            placeholder = { Text("Search languages...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (translationSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { translationSearchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredTranslationLanguages) { (langCode, langName) ->
                                val isSelected = translationTargetLang == langCode
                                val isDownloaded = remember(langCode) {
                                    com.iefan.readout.tts.ReadoutTtsEngine.instance?.isLanguageDownloaded(langCode) == true
                                }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else Color(0xFF181820)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color(0xFF282834),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable { onSelectTranslationLang(langCode) }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = langName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (langCode != "none" && isDownloaded) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.DownloadDone,
                                                contentDescription = "Voice Pack Downloaded",
                                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                    
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // Presets
                        val presets = listOf(
                            Pair(Color(0xFF305CDE), "Royal Blue (Default)"),
                            Pair(Color(0xFF4CAF50), "Emerald Green"),
                            Pair(Color(0xFFAB47BC), "Purple Accent"),
                            Pair(Color(0xFFFF7043), "Sunset Orange"),
                            Pair(Color(0xFFEF5350), "Crimson Red")
                        )

                        Text(
                            text = "Preset Colors",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            presets.forEach { (presetColor, name) ->
                                val isSelected = Math.abs(presetColor.red - themeColor.red) < 0.02f &&
                                                 Math.abs(presetColor.green - themeColor.green) < 0.02f &&
                                                 Math.abs(presetColor.blue - themeColor.blue) < 0.02f

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(presetColor)
                                        .border(
                                            2.dp,
                                            if (isSelected) Color.White else Color.Transparent,
                                            RoundedCornerShape(18.dp)
                                        )
                                        .clickable { onThemeColorChange(presetColor) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Custom color picker section
                        Text(
                            text = "Custom Color Picker",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val spectrumColors = remember {
                            listOf(
                                Color(0xFFEF5350), // Red
                                Color(0xFFFF9800), // Orange
                                Color(0xFFFFEB3B), // Yellow
                                Color(0xFF4CAF50), // Green
                                Color(0xFF009688), // Teal
                                Color(0xFF2196F3), // Blue
                                Color(0xFF3F51B5), // Royal Blue
                                Color(0xFF9C27B0), // Purple
                                Color(0xFFE91E63), // Pink
                                Color(0xFFEF5350)  // Red
                            )
                        }

                        // Gradient Bar
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitFirstDown()
                                            val initialFraction = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            onThemeColorChange(interpolateColorInSpectrum(spectrumColors, initialFraction))
                                            
                                            drag(down.id) { change ->
                                                val dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                                onThemeColorChange(interpolateColorInSpectrum(spectrumColors, dragFraction))
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                        ) {
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = spectrumColors
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Live Color Preview Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(themeColor)
                                    .border(1.5.dp, Color.White, RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = String.format("#%06X", 0xFFFFFF and themeColor.toArgb()),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (currentScreen != 0) currentScreen = 0 else onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentScreen != 0) Color(0xFF22222C) else MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = if (currentScreen != 0) "Back to Settings" else "Done",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

private fun interpolateColorInSpectrum(colors: List<Color>, fraction: Float): Color {
    if (colors.isEmpty()) return Color.White
    if (colors.size == 1) return colors[0]
    val segmentCount = colors.size - 1
    val scaledFraction = fraction * segmentCount
    val index = scaledFraction.toInt().coerceIn(0, segmentCount - 1)
    val localFraction = scaledFraction - index
    val c1 = colors[index]
    val c2 = colors[index + 1]
    return Color(
        red = c1.red + (c2.red - c1.red) * localFraction,
        green = c1.green + (c2.green - c1.green) * localFraction,
        blue = c1.blue + (c2.blue - c1.blue) * localFraction,
        alpha = 1.0f
    )
}
