package com.example.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Captures the host Activity window's real system navigation bar and status bar inset heights.
 *
 * Why: On Android 13-15 (especially Samsung One UI), full-screen Compose [Dialog] and
 * [ModalBottomSheet] windows with `decorFitsSystemWindows = false` can report `0.dp` for
 * `WindowInsets.navigationBars` inside the dialog window even though they draw behind the
 * system navigation bar. Providing the host window's measured nav-bar bottom inset allows
 * [safeNavigationBarsPadding] and [safeBottomBarPadding] to apply the exact missing floor
 * ONLY when the dialog window drops insets (zero double-padding when native insets work).
 */
val LocalHostNavigationBarBottomDp = compositionLocalOf<Dp> { 0.dp }
val LocalHostStatusBarTopDp = compositionLocalOf<Dp> { 0.dp }

/**
 * Applies `navigationBarsPadding()` with a fallback to the host Activity's measured
 * navigation bar height when a Dialog/Sheet window reports `0.dp`.
 */
fun Modifier.safeNavigationBarsPadding(): Modifier = composed {
    val localNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hostNavBottom = LocalHostNavigationBarBottomDp.current
    val fallbackExtraBottom = (hostNavBottom - localNavBottom).coerceAtLeast(0.dp)
    this
        .navigationBarsPadding()
        .padding(bottom = fallbackExtraBottom)
}

/**
 * Standard bottom action bar inset modifier for every Edit/Detail screen and ModalBottomSheet:
 * pins the action row above both the Android system navigation bar (3-button or gesture pill)
 * and the software keyboard (IME), without double-padding.
 */
fun Modifier.safeBottomBarPadding(): Modifier = composed {
    val localNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hostNavBottom = LocalHostNavigationBarBottomDp.current
    val fallbackExtraBottom = (hostNavBottom - localNavBottom).coerceAtLeast(0.dp)
    this
        .navigationBarsPadding()
        .padding(bottom = fallbackExtraBottom)
        .imePadding()
}

/**
 * Applies `statusBarsPadding()` with a fallback to the host Activity's measured status bar
 * height when a Dialog window reports `0.dp`.
 */
fun Modifier.safeStatusBarsPadding(): Modifier = composed {
    val localStatusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hostStatusTop = LocalHostStatusBarTopDp.current
    val fallbackExtraTop = (hostStatusTop - localStatusTop).coerceAtLeast(0.dp)
    this
        .statusBarsPadding()
        .padding(top = fallbackExtraTop)
}
