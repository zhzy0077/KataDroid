package io.github.zhzy0077.katadroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GoColorScheme = lightColorScheme(
    primary = GoColors.Primary,
    onPrimary = Color.White,
    primaryContainer = GoColors.Container,
    onPrimaryContainer = GoColors.Ink,
    secondary = GoColors.Muted,
    onSecondary = Color.White,
    secondaryContainer = GoColors.Navigation,
    onSecondaryContainer = GoColors.Ink,
    background = GoColors.Background,
    onBackground = GoColors.Ink,
    surface = GoColors.Surface,
    onSurface = GoColors.Ink,
    onSurfaceVariant = GoColors.Muted,
    surfaceVariant = GoColors.Container,
    outline = GoColors.Outline,
    outlineVariant = GoColors.Outline,
    surfaceContainer = GoColors.Navigation,
    surfaceContainerHigh = GoColors.Container,
    surfaceContainerHighest = GoColors.Container,
    surfaceContainerLow = GoColors.Surface,
    surfaceContainerLowest = GoColors.Surface,
    inverseSurface = GoColors.Ink,
    inverseOnSurface = GoColors.Background,
)

@Composable
fun KataDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GoColorScheme, typography = Typography, content = content)
}
