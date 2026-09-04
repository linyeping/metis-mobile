package com.mrgreenapps.a11ypilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mrgreenapps.a11ypilot.R
import com.mrgreenapps.a11ypilot.data.AppThemeMode
import com.mrgreenapps.a11ypilot.data.AppThemeStyle

// Anthropic Mono Variable is the UI face requested for Metis. Android's normal glyph fallback
// supplies CJK characters that are not present in the Latin-focused font file.
val AnthropicMonoFamily = FontFamily(
    Font(R.font.anthropic_mono_variable, FontWeight.Normal)
)

// Keep the old symbol as a source-compatible alias for screens that import it directly.
val ClaudeFontFamily = AnthropicMonoFamily

// FiraCode is reserved for code blocks and terminal output.
val FiraCodeFamily = FontFamily(
    Font(R.font.fira_code_regular, FontWeight.Normal)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ClaudeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

// Claude Light Theme
private val ClaudeLightScheme = lightColorScheme(
    primary = ClaudeColors.LightCoral,
    onPrimary = ClaudeColors.LightOnPrimary,
    primaryContainer = ClaudeColors.CoralDisabled,
    onPrimaryContainer = ClaudeColors.DarkInk,
    secondary = ClaudeColors.LightAccentTeal,
    onSecondary = ClaudeColors.LightOnPrimary,
    background = ClaudeColors.Cream,
    onBackground = ClaudeColors.BodyText,
    surface = ClaudeColors.CreamCard,
    onSurface = ClaudeColors.BodyText,
    surfaceVariant = ClaudeColors.CreamCard,
    onSurfaceVariant = ClaudeColors.MutedText,
    error = ClaudeColors.LightError,
    onError = ClaudeColors.LightOnPrimary,
    outline = ClaudeColors.Border,
    outlineVariant = ClaudeColors.SoftBorder
)

// Claude Dark Theme
private val ClaudeDarkScheme = darkColorScheme(
    primary = ClaudeColors.Coral,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = ClaudeColors.DarkCoralActive,
    onPrimaryContainer = ClaudeColors.OnDarkText,
    secondary = ClaudeColors.AccentTeal,
    onSecondary = ClaudeColors.DarkInk,
    background = ClaudeColors.DarkBg,
    onBackground = ClaudeColors.OnDarkText,
    surface = ClaudeColors.DarkSurface,
    onSurface = ClaudeColors.OnDarkText,
    surfaceVariant = ClaudeColors.DarkElevated,
    onSurfaceVariant = ClaudeColors.OnDarkSoft,
    error = ClaudeColors.Error,
    onError = androidx.compose.ui.graphics.Color.White,
    outline = ClaudeColors.OnDarkMuted,
    outlineVariant = ClaudeColors.DarkSurfaceSoft
)

// Codex Light Theme
private val CodexLightScheme = lightColorScheme(
    primary = CodexColors.TechBlue,
    onPrimary = CodexColors.White,
    primaryContainer = CodexColors.TechBlueLight,
    onPrimaryContainer = CodexColors.White,
    secondary = CodexColors.AccentBlue,
    onSecondary = CodexColors.White,
    background = CodexColors.LightBg,
    onBackground = CodexColors.BodyText,
    surface = CodexColors.LightSurface,
    onSurface = CodexColors.BodyText,
    surfaceVariant = CodexColors.LightBg,
    onSurfaceVariant = CodexColors.MutedText,
    error = CodexColors.Error,
    onError = CodexColors.White,
    outline = CodexColors.LightBorder,
    outlineVariant = CodexColors.LightBorder
)

// Codex Dark Theme
private val CodexDarkScheme = darkColorScheme(
    primary = CodexColors.CyanAccent,
    onPrimary = CodexColors.DarkBg,
    primaryContainer = CodexColors.CyanGlow,
    onPrimaryContainer = CodexColors.OnDarkText,
    secondary = CodexColors.EmeraldAccent,
    onSecondary = CodexColors.DarkBg,
    background = CodexColors.DarkBg,
    onBackground = CodexColors.OnDarkText,
    surface = CodexColors.DarkSurface,
    onSurface = CodexColors.OnDarkText,
    surfaceVariant = CodexColors.DarkSurfaceElevated,
    onSurfaceVariant = CodexColors.OnDarkMuted,
    error = CodexColors.Error,
    onError = CodexColors.White,
    outline = CodexColors.DarkBorder,
    outlineVariant = CodexColors.DarkBorder
)

@Composable
fun A11yPilotTheme(
    themeStyle: AppThemeStyle = AppThemeStyle.CLAUDE,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> systemInDarkTheme
    }

    val colorScheme = when (themeStyle) {
        AppThemeStyle.CLAUDE -> if (darkTheme) ClaudeDarkScheme else ClaudeLightScheme
        AppThemeStyle.CODEX -> if (darkTheme) CodexDarkScheme else CodexLightScheme
    }

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale.coerceIn(0.8f, 1.4f))
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
