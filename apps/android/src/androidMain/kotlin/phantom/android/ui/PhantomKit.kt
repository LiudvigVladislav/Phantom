// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.*
import kotlin.math.abs

// ── HSL → Color ─────────────────────────────────────────────
fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f  -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else     -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m)
}

// Deterministic gradient per username — matches design's gradientForName()
fun gradientBrushForName(name: String): Brush {
    var h = 0L
    for (c in name) h = (h * 31L + c.code) and 0xFFFFFFFFL
    val h1 = (h % 360).toFloat()
    val h2 = (h1 + 40f + (h % 60)) % 360f
    return Brush.linearGradient(
        colors = listOf(
            hslToColor(h1, 0.70f, 0.55f),
            hslToColor(h2, 0.75f, 0.40f),
        ),
    )
}

fun nameInitials(name: String): String =
    name.split(Regex("[\\s._\\-]+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")

// ── Gradient avatar with optional presence dot ───────────────
@Composable
fun GradientAvatar(
    name: String,
    size: Dp = 44.dp,
    online: Boolean? = null,
    ring: Boolean = false,
    brushOverride: Brush? = null,
    imageBitmap: ImageBitmap? = null,
) {
    Box(
        modifier = Modifier.size(size),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(brushOverride ?: gradientBrushForName(name))
                .then(
                    if (ring) Modifier.border(2.dp, CyanAccent, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = nameInitials(name).ifEmpty { name.take(1).uppercase() },
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = (size.value * 0.38f).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Default,
                )
            }
        }
        if (online != null) {
            val dotSize = (size.value * 0.26f).coerceAtLeast(10f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .offset(x = 1.dp, y = 1.dp)
                    .clip(CircleShape)
                    .background(if (online) Success else TextDim)
                    .border(2.dp, BgDeep, CircleShape),
            )
        }
    }
}

// ── Delivery status icon (Canvas-drawn, thin stroke) ─────────
enum class DeliveryStatus { CLOCK, SENT, DELIVERED, READ }

@Composable
fun DeliveryIcon(status: DeliveryStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        DeliveryStatus.READ -> CyanAccent
        else -> Color.White.copy(alpha = 0.55f)
    }
    Canvas(modifier = modifier.size(16.dp, 12.dp)) {
        when (status) {
            DeliveryStatus.CLOCK -> drawClock(color)
            DeliveryStatus.SENT  -> drawSingleCheck(color)
            DeliveryStatus.DELIVERED, DeliveryStatus.READ -> drawDoubleCheck(color)
        }
    }
}

private fun DrawScope.drawClock(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.height / 2f - 1f
    drawCircle(color = color, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.2.dp.toPx()))
    drawLine(color = color, start = Offset(cx, cy), end = Offset(cx, cy - r * 0.6f), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(cx, cy), end = Offset(cx + r * 0.4f, cy + r * 0.3f), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawSingleCheck(color: Color) {
    val sw = 1.4.dp.toPx()
    drawLine(color = color, start = Offset(1.dp.toPx(), size.height * 0.55f), end = Offset(size.width * 0.35f, size.height - 1.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(size.width * 0.35f, size.height - 1.dp.toPx()), end = Offset(size.width - 1.dp.toPx(), 1.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
}

private fun DrawScope.drawDoubleCheck(color: Color) {
    val sw = 1.4.dp.toPx()
    val h = size.height
    val w = size.width
    // first check (offset left)
    drawLine(color = color, start = Offset(1.dp.toPx(), h * 0.55f), end = Offset(w * 0.28f, h - 1.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(w * 0.28f, h - 1.dp.toPx()), end = Offset(w * 0.62f, 1.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
    // second check (offset right)
    drawLine(color = color, start = Offset(w * 0.38f, h * 0.65f), end = Offset(w * 0.56f, h - 1.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(w * 0.56f, h - 1.dp.toPx()), end = Offset(w - 1.dp.toPx(), 1.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
}
