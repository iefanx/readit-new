package com.iefan.readout.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val HighDensityLightColorScheme = lightColorScheme(
  primary = OledPrimary,
  onPrimary = OledOnPrimary,
  primaryContainer = OledPrimaryContainer,
  onPrimaryContainer = OledOnPrimaryContainer,
  secondary = OledSecondary,
  onSecondary = OledOnSecondary,
  secondaryContainer = OledSecondaryContainer,
  onSecondaryContainer = OledOnSecondaryContainer,
  background = OledBg,
  onBackground = OledOnBg,
  surface = OledBg,
  onSurface = OledOnSurface,
  surfaceVariant = OledSurfaceCard,
  onSurfaceVariant = OledOnSurfaceVariant,
  outlineVariant = Color(0xFF212124)
)

private val HighDensityDarkColorScheme = darkColorScheme(
  primary = OledPrimary,
  onPrimary = OledOnPrimary,
  primaryContainer = OledPrimaryContainer,
  onPrimaryContainer = OledOnPrimaryContainer,
  secondary = OledSecondary,
  onSecondary = OledOnSecondary,
  secondaryContainer = OledSecondaryContainer,
  onSecondaryContainer = OledOnSecondaryContainer,
  background = OledBg,
  onBackground = OledOnBg,
  surface = OledBg,
  onSurface = OledOnSurface,
  surfaceVariant = OledSurfaceCard,
  onSurfaceVariant = OledOnSurfaceVariant,
  outlineVariant = Color(0xFF212124)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Custom theme option: set dynamicColor = false to preserve the High Density look
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> HighDensityDarkColorScheme
      else -> HighDensityLightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
