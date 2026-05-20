package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.viewmodel.BenchmarkResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BenchmarkDialog(
    progress: Float?,
    result: BenchmarkResult?,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
    onSelectTier: (String) -> Unit
) {
    Dialog(onDismissRequest = { if (progress == null) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.DeveloperBoard,
                    contentDescription = "Diagnostic Probe",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                
                Text(
                    text = "Hardware Profiler",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Check on-device neural synthesis compatibility",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(20.dp))

                if (progress != null) {
                    // Running state
                    ProfilingTerminal(progress = progress)
                } else if (result != null) {
                    // Completed status
                    ProfileResultView(
                        result = result,
                        onApply = {
                            onSelectTier(result.recommendedTier)
                            onDismiss()
                        },
                        onSelectTier = onSelectTier
                    )
                } else {
                    // Start screen
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "AuraBook runs dedicated neural text-to-speech models fully on-device. This 3-second benchmark profiles CPU cores, active memory throughput, and DSP pipelines to match your phone is silicon exactly.",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onRun,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("run_benchmark_btn")
                        ) {
                            Text("Begin Diagnostic Profile")
                        }
                    }
                }

                if (progress == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun ProfilingTerminal(progress: Float) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Simulate terminal logs flowing
    val logs = remember(progress) {
        val list = mutableListOf<String>()
        list.add("▸ [INIT] Initializing local ONNX runtime pipeline...")
        if (progress > 0.15f) list.add("▸ [CPU] Probing instruction acceleration matrices...")
        if (progress > 0.35f) {
            val cores = Runtime.getRuntime().availableProcessors()
            list.add("▸ [CPU] Detected $cores processing cores available.")
        }
        if (progress > 0.50f) list.add("▸ [MEM] Writing volatile synthetic bytes to estimate bus rate...")
        if (progress > 0.65f) list.add("▸ [ACC] Searching Snapdragon/Tensor/Exynos NPU libraries...")
        if (progress > 0.82f) list.add("▸ [ACC] Local tensor engines detected on system fallback.")
        if (progress > 0.95f) list.add("▸ [CALC] Compiling optimal acoustic voice matrix...")
        list
    }

    LaunchedEffect(logs.size) {
        scope.launch {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0C0E14))
                .border(1.dp, Color(0xFF232936), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = if (log.contains("[SUCCESS]") || log.contains("[INFO]")) Color(0xFF81C784) else Color(0xFF90A4AE),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Analyzing synthetic model pipelines... ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
fun ProfileResultView(
    result: BenchmarkResult,
    onApply: () -> Unit,
    onSelectTier: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                .padding(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Success",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "SPECIFICATION RATINGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${result.cores} Core CPU | ~${String.format("%.1f", result.memoryGb)} GB allocated RAM",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Recommended Model Tier:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        val tierTitle = when (result.recommendedTier) {
            "ULTRA_LIGHT" -> "Ultra-Light (Piper TTS)"
            "BALANCED" -> "Balanced (Kokoro-82M)"
            else -> "High-Fidelity (MeloTTS)"
        }
        
        val tierDesc = when (result.recommendedTier) {
            "ULTRA_LIGHT" -> "Perfect for lightweight synthetic execution, zero loading latency, minimal heat emission."
            "BALANCED" -> "Outstanding structural prosody, human-like rhythm, and rapid background compilation."
            else -> "Flagship studio quality vocalization, comprehensive accents, deep acoustic resonance."
        }

        Text(
            text = tierTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = tierDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onApply,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("apply_benchmark_btn")
        ) {
            Text("Apply Recommendation")
        }
    }
}
