package com.iefan.readout.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch

@Composable
fun SoundWaveVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val phaseAnim = remember { Animatable(0f) }
    val pulseAnim = remember { Animatable(1f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            launch {
                phaseAnim.animateTo(
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    )
                )
            }
            launch {
                pulseAnim.animateTo(
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        } else {
            launch {
                phaseAnim.animateTo(0f, tween(500))
            }
            launch {
                pulseAnim.animateTo(1f, tween(500))
            }
        }
    }

    val phase = phaseAnim.value
    val pulseScale = pulseAnim.value

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        val barCount = 42
        val barWidth = (width / barCount) * 0.6f
        val spacing = (width / barCount) * 0.4f

        for (i in 0 until barCount) {
            val progress = i.toFloat() / barCount
            
            // Generate nested sine waves for natural acoustic vibration
            val rawSine = sin(progress * 3 * Math.PI + phase) + 
                          0.4 * sin(progress * 7 * Math.PI - phase * 1.5)
            
            val amplitude = if (isPlaying) {
                // Generates center-focused swelling wave
                val envelope = sin(progress * Math.PI)
                (rawSine * 0.45 * height * envelope * pulseScale).toFloat()
            } else {
                // Static resting wave
                val envelope = sin(progress * Math.PI)
                (sin(progress * 5 * Math.PI).coerceIn(-1.0, 1.0) * 4.dp.toPx() * envelope).toFloat()
            }

            val x = i * (barWidth + spacing) + spacing / 2
            val top = centerY - Math.abs(amplitude).coerceAtLeast(3.dp.toPx())
            val bottom = centerY + Math.abs(amplitude).coerceAtLeast(3.dp.toPx())

            val brush = Brush.verticalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.9f),
                    tertiaryColor.copy(alpha = 0.8f),
                    secondaryColor.copy(alpha = 0.6f)
                ),
                startY = top,
                endY = bottom
            )

            drawRoundRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(x, top),
                size = androidx.compose.ui.geometry.Size(barWidth, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}
