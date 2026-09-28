package dev.friendline.messenger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF5F3EC)
private val Ink = Color(0xFF1D2722)
private val Signal = Color(0xFF245B4D)
private val SignalSoft = Color(0xFFDDE9E2)
private val Brass = Color(0xFF855817)
private val Rule = Color(0xFFD9D8CF)
private val DarkPaper = Color(0xFF171D19)
private val DarkSurface = Color(0xFF202923)
private val LightInk = Color(0xFFE8EEE8)

private val LightColors = lightColorScheme(
    primary = Signal,
    onPrimary = Color.White,
    secondary = Brass,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFBFAF7),
    onSurface = Ink,
    surfaceVariant = SignalSoft,
    onSurfaceVariant = Color(0xFF394840),
    outline = Rule,
    error = Color(0xFF9A3025),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAACDBB),
    onPrimary = Color(0xFF133A2D),
    secondary = Color(0xFFE7BC77),
    onSecondary = Color(0xFF422C06),
    background = DarkPaper,
    onBackground = LightInk,
    surface = DarkSurface,
    onSurface = LightInk,
    surfaceVariant = Color(0xFF324039),
    onSurfaceVariant = Color(0xFFD0DCD3),
    outline = Color(0xFF68766D),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF5F160F),
)

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    val colors = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
