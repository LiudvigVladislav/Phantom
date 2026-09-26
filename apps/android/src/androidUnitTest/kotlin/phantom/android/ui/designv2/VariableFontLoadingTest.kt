// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.core.content.res.ResourcesCompat
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import phantom.android.R
import phantom.android.testing.PaparazziTestEngine
import phantom.android.ui.theme.PhantomFontGeist
import phantom.android.ui.theme.PhantomFontInter
import phantom.android.ui.theme.PhantomFontMono

@Category(PaparazziTestEngine::class)
class VariableFontLoadingTest {
    @get:Rule val paparazzi = Paparazzi()

    @Test
    fun equal_weights_across_families_match_independent_android_loading() {
        val resolver = createFontFamilyResolver(paparazzi.context)
        val families = listOf(
            R.font.geist_variable to PhantomFontGeist,
            R.font.inter_variable to PhantomFontInter,
            R.font.jetbrains_mono_variable to PhantomFontMono,
        )
        for ((resource, _) in families) {
            assertFalse(
                "The raster comparison must distinguish regular and semibold: $resource",
                raster(reference(resource, FontWeight.Normal))
                    .contentEquals(raster(reference(resource, FontWeight.SemiBold))),
            )
        }
        for (weight in listOf(FontWeight.SemiBold, FontWeight.Medium, FontWeight.Bold, FontWeight.Normal)) {
            for ((resource, family) in families) {
                val actual = resolver.resolve(
                    fontFamily = family,
                    fontWeight = weight,
                    fontStyle = FontStyle.Normal,
                    fontSynthesis = FontSynthesis.None,
                ).value as Typeface
                assertArrayEquals(
                    "resource=$resource weight=${weight.weight}",
                    raster(reference(resource, weight)),
                    raster(actual),
                )
            }
        }
    }

    private fun reference(resource: Int, weight: FontWeight): Typeface = Paint().apply {
        typeface = ResourcesCompat.getFont(paparazzi.context, resource)
        fontVariationSettings = "'wght' ${weight.weight}"
    }.typeface

    private fun raster(typeface: Typeface): IntArray {
        val bitmap = Bitmap.createBitmap(640, 100, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 40f
            color = android.graphics.Color.WHITE
        }
        Canvas(bitmap).drawText("Preview Phantom Pro 012345", 0f, 60f, paint)
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }
}
