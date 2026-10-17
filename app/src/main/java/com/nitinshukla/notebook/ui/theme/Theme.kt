package com.nitinshukla.notebook.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.settings.EditorFont
import com.nitinshukla.notebook.core.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFF77536D),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE3E1EC),
    background = Color(0xFFFBF8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF0B1F8F),
    primaryContainer = Color(0xFF2638A3),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFFC3C5DD),
    secondaryContainer = Color(0xFF434559),
    tertiary = Color(0xFFE6BAD7),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF46464F),
    background = Color(0xFF131318),
)

val NotebookTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun NotebookTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = NotebookTypography, content = content)
}

/** Resolves a note color against the current theme; DEFAULT maps to the surface container. */
@Composable
fun NoteColor.toComposeColor(): Color {
    if (this == NoteColor.DEFAULT) return MaterialTheme.colorScheme.surfaceContainerLow
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return Color(if (dark) darkArgb else lightArgb)
}

/** Readable text color for a note background. */
@Composable
fun NoteColor.contentColor(): Color {
    if (this == NoteColor.DEFAULT) return MaterialTheme.colorScheme.onSurface
    val bg = toComposeColor()
    return if (bg.luminance() < 0.4f) Color(0xFFF2F2F2) else Color(0xFF1C1B1F)
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

fun EditorFont.toFontFamily(): FontFamily? = when (this) {
    EditorFont.DEFAULT -> null
    EditorFont.SANS_SERIF -> FontFamily.SansSerif
    EditorFont.SERIF -> FontFamily.Serif
    EditorFont.MONOSPACE -> FontFamily.Monospace
    EditorFont.CURSIVE -> FontFamily.Cursive
}
