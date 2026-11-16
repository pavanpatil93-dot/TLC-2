package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun buildLightScheme(tokens: TlcColorTokens) = lightColorScheme(
    primary = tokens.primaryAction,
    onPrimary = tokens.onPrimaryAction,
    primaryContainer = tokens.selectedContainer,
    onPrimaryContainer = tokens.textPrimary,
    secondary = tokens.primaryAction,
    onSecondary = tokens.onPrimaryAction,
    secondaryContainer = tokens.surfaceVariant,
    onSecondaryContainer = tokens.textPrimary,
    tertiary = tokens.primaryAction,
    onTertiary = tokens.onPrimaryAction,
    background = tokens.background,
    onBackground = tokens.textPrimary,
    surface = tokens.surface,
    onSurface = tokens.textPrimary,
    surfaceVariant = tokens.surfaceVariant,
    onSurfaceVariant = tokens.textSecondary,
    outline = tokens.border,
    outlineVariant = tokens.border.copy(alpha = 0.65f),
    error = tokens.error,
    onError = Color.White
)

private fun buildDarkScheme(tokens: TlcColorTokens) = darkColorScheme(
    primary = tokens.primaryAction,
    onPrimary = tokens.onPrimaryAction,
    primaryContainer = tokens.selectedContainer,
    onPrimaryContainer = tokens.textPrimary,
    secondary = tokens.primaryAction,
    onSecondary = tokens.onPrimaryAction,
    secondaryContainer = tokens.surfaceVariant,
    onSecondaryContainer = tokens.textPrimary,
    tertiary = tokens.primaryAction,
    onTertiary = tokens.onPrimaryAction,
    background = tokens.background,
    onBackground = tokens.textPrimary,
    surface = tokens.surface,
    onSurface = tokens.textPrimary,
    surfaceVariant = tokens.surfaceVariant,
    onSurfaceVariant = tokens.textSecondary,
    outline = tokens.border,
    outlineVariant = tokens.border.copy(alpha = 0.65f),
    error = tokens.error,
    onError = Color(0xFF121619)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = TlcThemeController.isDark,
    content: @Composable () -> Unit,
) {
    val activeTokens = if (darkTheme) DarkTlcColorTokens else LightTlcColorTokens
    val scheme = if (darkTheme) buildDarkScheme(activeTokens) else buildLightScheme(activeTokens)
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun TlcPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryActionOrange,
            contentColor = OnPrimaryActionColor
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = OnPrimaryActionColor, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TlcSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderGrey),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextPrimary,
            containerColor = SurfaceWhite
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TlcDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StatusNotAvailable,
            contentColor = Color.White
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
