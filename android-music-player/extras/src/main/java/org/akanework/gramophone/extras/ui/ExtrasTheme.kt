/*
 *     Copyright (C) 2026 Gramophone extras contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.extras.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager

/**
 * The theme every Compose screen in :extras uses.
 *
 * Same tokens as the View-based screens (see integrate/palette.py), so an
 * activity from this module sits next to Gramophone's own without a seam.
 * Honours the app's own theme settings: `theme_mode` (follow system / light /
 * dark) and `pureDark` (true black surfaces for OLED).
 */
@Composable
fun ExtrasTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val prefs = remember(context) { AppearancePrefs.read(context) }
    val dark = when (prefs.themeMode) {
        AppearancePrefs.Mode.LIGHT -> false
        AppearancePrefs.Mode.DARK -> true
        AppearancePrefs.Mode.SYSTEM -> systemDark
    }
    val scheme = when {
        dark && prefs.pureDark -> DarkPalette.pureBlack()
        dark -> DarkPalette
        else -> LightPalette
    }
    MaterialTheme(colorScheme = scheme, typography = ExtrasTypography, content = content)
}

private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
)

/**
 * Slightly tighter headline weights than Material's defaults, which read as
 * a stock template. Body sizes are left alone for legibility.
 */
private val ExtrasTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Gramophone's two appearance settings, read once per screen. */
internal class AppearancePrefs(val themeMode: Mode, val pureDark: Boolean) {
    enum class Mode { SYSTEM, LIGHT, DARK }

    companion object {
        fun read(context: Context): AppearancePrefs {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            // Upstream's theme_switch_val array: "0" follow system, "1" dark,
            // "2" light.
            val mode = when (prefs.getString("theme_mode", "0")) {
                "1" -> Mode.DARK
                "2" -> Mode.LIGHT
                else -> Mode.SYSTEM
            }
            return AppearancePrefs(mode, prefs.getBoolean("pureDark", false))
        }
    }
}
