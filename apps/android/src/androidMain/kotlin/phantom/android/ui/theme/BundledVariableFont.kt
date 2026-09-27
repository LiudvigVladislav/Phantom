// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.theme

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.FontRes
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

internal fun bundledVariableFont(
    @FontRes resourceId: Int,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
): Font = BundledVariableFont(resourceId, weight, style)

private data class BundledVariableFont(
    @FontRes val resourceId: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = BundledVariableFontLoader,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private object BundledVariableFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val bundled = font as BundledVariableFont
        val base = ResourcesCompat.getFont(context, bundled.resourceId) ?: return null
        if (Build.VERSION.SDK_INT < 26) return base

        // Compose 1.7.6 reuses a Paint whose variation string survives setTypeface.
        // Android then skips equal settings for a different font. A fresh Paint
        // applies the axis on every cache miss, independent of font load order.
        return Paint().apply {
            typeface = base
            fontVariationSettings = "'wght' ${bundled.weight.weight}"
        }.typeface
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        loadBlocking(context, font)
}
