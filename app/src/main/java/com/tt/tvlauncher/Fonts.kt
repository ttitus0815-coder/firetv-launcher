package com.tt.tvlauncher

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Fire OS ships Amazon Ember as a system font; use it so the launcher matches the rest of the TV.
 * On a device without it, Typeface.create falls back to the default sans-serif.
 */
object Fonts {
    private fun family(name: String, weight: Int) =
        FontFamily(Typeface.create(Typeface.create(name, Typeface.NORMAL), weight, false))

    val displayLight = family("amazon-ember-display", 300)
    val regular = family("amazon-ember", 400)
    val medium = family("amazon-ember", 500)
}
