package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Finalized Branding & Dual Colour Theme — The Leasing Company, Both Divisions (Commercial & Residential)
 * Executive Energy Direction:
 * - Light background: #F8F7F4, Dark background: #121619
 * - Primary CTA: #E96932 (text/icons on primary: #121619)
 */

enum class TlcThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromString(raw: String?): TlcThemeMode =
            entries.firstOrNull {
                it.name.equals(raw?.trim(), ignoreCase = true) ||
                    it.label.equals(raw?.trim(), ignoreCase = true)
            } ?: LIGHT
    }
}

enum class TlcBusinessDivisionToken {
    COMMERCIAL,
    RESIDENTIAL
}

enum class TlcBusinessViewToken {
    COMMERCIAL,
    RESIDENTIAL,
    ALL_AUTHORIZED
}

data class TlcThemePreferenceContract(
    val appDisplayName: String = "The Leasing Company",
    val businessDivision: TlcBusinessDivisionToken = TlcBusinessDivisionToken.COMMERCIAL,
    val businessView: TlcBusinessViewToken = TlcBusinessViewToken.COMMERCIAL,
    val themeMode: TlcThemeMode = TlcThemeMode.LIGHT,
    val themePreferenceOwnerId: String? = null,
    val logoAssetRef: String = "tlc_brand_monogram_v1",
    val themeSchemaVersion: Int = 1
)

data class TlcColorTokens(
    val background: Color,
    val primaryAction: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val onPrimaryAction: Color,
    val selectedContainer: Color,
    val focusOutline: Color,
    val success: Color,
    val warning: Color,
    val error: Color
)

val LightTlcColorTokens = TlcColorTokens(
    background = Color(0xFFF8F7F4),
    primaryAction = Color(0xFFE96932),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFEEEA),
    textPrimary = Color(0xFF202629),
    textSecondary = Color(0xFF566168),
    border = Color(0xFFD7DCD9),
    onPrimaryAction = Color(0xFF121619),
    selectedContainer = Color(0xFFFCE8DF),
    focusOutline = Color(0xFFA33B13),
    success = Color(0xFF166534),
    warning = Color(0xFF854D0E),
    error = Color(0xFFB91C1C)
)

val DarkTlcColorTokens = TlcColorTokens(
    background = Color(0xFF121619),
    primaryAction = Color(0xFFE96932),
    surface = Color(0xFF1C2226),
    surfaceVariant = Color(0xFF252D32),
    textPrimary = Color(0xFFF5F5F2),
    textSecondary = Color(0xFFB4BEC5),
    border = Color(0xFF465158),
    onPrimaryAction = Color(0xFF121619),
    selectedContainer = Color(0xFF3A251D),
    focusOutline = Color(0xFFFFB58F),
    success = Color(0xFF86EFAC),
    warning = Color(0xFFFDE68A),
    error = Color(0xFFFCA5A5)
)

object TlcThemeController {
    var themeMode: TlcThemeMode by mutableStateOf(TlcThemeMode.LIGHT)
        private set

    val isDark: Boolean
        get() = themeMode == TlcThemeMode.DARK

    val tokens: TlcColorTokens
        get() = if (themeMode == TlcThemeMode.DARK) DarkTlcColorTokens else LightTlcColorTokens

    fun updateThemeMode(mode: TlcThemeMode) {
        themeMode = mode
    }
}

// ==========================================
// 1. Primary Palette (Mapped to Executive Energy Tokens)
// ==========================================
const val TLC_APP_DISPLAY_NAME = "The Leasing Company"

val PrimaryActionOrange: Color
    get() = TlcThemeController.tokens.primaryAction

val OnPrimaryActionColor: Color
    get() = TlcThemeController.tokens.onPrimaryAction

val SelectedContainerColor: Color
    get() = TlcThemeController.tokens.selectedContainer

val FocusOutlineColor: Color
    get() = TlcThemeController.tokens.focusOutline

val PrimaryNavy: Color
    get() = TlcThemeController.tokens.textPrimary

val PrimaryTeal: Color
    get() = TlcThemeController.tokens.primaryAction

val TealLight: Color
    get() = TlcThemeController.tokens.focusOutline

val TealDark: Color
    get() = TlcThemeController.tokens.focusOutline

val TealSubtle: Color
    get() = TlcThemeController.tokens.selectedContainer

// Surfaces & Backgrounds
val BackgroundSoft: Color
    get() = TlcThemeController.tokens.background

val SurfaceWhite: Color
    get() = TlcThemeController.tokens.surface

val SurfaceTint: Color
    get() = TlcThemeController.tokens.surfaceVariant

val SurfaceMuted: Color
    get() = TlcThemeController.tokens.surfaceVariant

val SelectedSurfaceContainer: Color
    get() = TlcThemeController.tokens.selectedContainer

val SelectedContainerFill: Color
    get() = TlcThemeController.tokens.selectedContainer

val BorderGrey: Color
    get() = TlcThemeController.tokens.border

val BorderSubtle: Color
    get() = TlcThemeController.tokens.border.copy(alpha = 0.65f)

// Secondary & Muted Text
val TextPrimary: Color
    get() = TlcThemeController.tokens.textPrimary

val SecondaryText: Color
    get() = TlcThemeController.tokens.textSecondary

val MutedText: Color
    get() = TlcThemeController.tokens.textSecondary

// High-Intent Accent
val AccentGold: Color
    get() = TlcThemeController.tokens.primaryAction

val AccentGoldLight: Color
    get() = TlcThemeController.tokens.focusOutline

val AccentGoldDark: Color
    get() = TlcThemeController.tokens.focusOutline

val AccentGoldSubtle: Color
    get() = TlcThemeController.tokens.selectedContainer

// Semantic Status Colors
val SuccessGreen: Color
    get() = TlcThemeController.tokens.success

val StatusSuccessGreen: Color
    get() = TlcThemeController.tokens.success

val SuccessGreenSubtle: Color
    get() = TlcThemeController.tokens.success.copy(alpha = 0.14f)

val ErrorRed: Color
    get() = TlcThemeController.tokens.error

val StatusDangerRed: Color
    get() = TlcThemeController.tokens.error

val ErrorRedSubtle: Color
    get() = TlcThemeController.tokens.error.copy(alpha = 0.14f)

val WarningAmber: Color
    get() = TlcThemeController.tokens.warning

val StatusWarningAmber: Color
    get() = TlcThemeController.tokens.warning

val StatusCheckAvailability: Color
    get() = TlcThemeController.tokens.warning

val StatusInfoBlue: Color
    get() = if (TlcThemeController.isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)

val WarningAmberSubtle: Color
    get() = TlcThemeController.tokens.warning.copy(alpha = 0.14f)

// ==========================================
// 2. Backward-Compatible Aliases (Reactive to Light/Dark Mode)
// ==========================================
val AscendGold: Color
    get() = PrimaryTeal

val AscendGoldLight: Color
    get() = TealLight

val AscendGoldDark: Color
    get() = TealDark

val AscendGoldSubtle: Color
    get() = TealSubtle

val ChampagneGold: Color
    get() = AccentGold

val ChampagneGoldLight: Color
    get() = AccentGoldLight

val ChampagneGoldDark: Color
    get() = AccentGoldDark

val BrandGold: Color
    get() = PrimaryTeal

val BrandGoldLight: Color
    get() = TealLight

val ResidentialGold: Color
    get() = AccentGold

val AureaGold: Color
    get() = AccentGold

// Neutral Foundation
val BrandCharcoalDark: Color
    get() = BackgroundSoft

val BrandCharcoalCard: Color
    get() = SurfaceWhite

val BrandCharcoalSurface: Color
    get() = SurfaceTint

val BrandCharcoalBorder: Color
    get() = BorderGrey

val BrandCharcoalBorderSubtle: Color
    get() = BorderSubtle

// Dark Canvas Aliases
val CanvasObsidianDark = Color(0xFF121619)
val CanvasObsidianSurface = Color(0xFF1C2226)
val CanvasObsidianCard = Color(0xFF252D32)
val CanvasObsidianBorder = Color(0xFF465158)

// Backward-compatible Off-White / Off-Black
val BrandOffBlack: Color
    get() = TextPrimary

val BrandOffWhiteBg: Color
    get() = BackgroundSoft

val BrandOffWhiteSurface: Color
    get() = SurfaceWhite

val BrandOffWhiteCard: Color
    get() = SurfaceWhite

val BrandOffWhiteBorder: Color
    get() = BorderGrey

// Text Colors
val TextPrimaryDark: Color
    get() = TextPrimary

val TextSecondaryDark: Color
    get() = SecondaryText

val TextMutedDark: Color
    get() = MutedText

val TextPrimaryLight = Color(0xFFF5F5F2)
val TextSecondaryLight = Color(0xFFB4BEC5)
val TextMutedLight = Color(0xFFB4BEC5)

// Status & Semantic Colors
val StatusAvailable: Color
    get() = SuccessGreen

val StatusAvailableSubtle: Color
    get() = SuccessGreenSubtle

val SageEmerald: Color
    get() = SuccessGreen

val StatusNotAvailable: Color
    get() = ErrorRed

val StatusNotAvailableSubtle: Color
    get() = ErrorRedSubtle

val StatusUnconfirmed: Color
    get() = WarningAmber

val StatusUnconfirmedSubtle: Color
    get() = WarningAmberSubtle

// Vertical Category Accents
val CommercialBlue: Color
    get() = PrimaryTeal

val CommercialBlueLight: Color
    get() = TealLight

val CommercialBlueSubtle: Color
    get() = TealSubtle

val ResidentialRose: Color
    get() = PrimaryTeal

val PreLeasedAmber: Color
    get() = WarningAmber

val UnderConstTeal: Color
    get() = PrimaryTeal
