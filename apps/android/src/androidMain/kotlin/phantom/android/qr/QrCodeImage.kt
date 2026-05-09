// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    // ARGB_8888 not RGB_565 — `setPixel(Color.BLACK / WHITE)` writes ARGB
    // ints, which RGB_565 (16-bit, no alpha) silently corrupts on some
    // Android 13+ vendor stacks (Tecno, several emulator AVDs). Result
    // observed in the field: the QR card rendered as a solid black square
    // on the user's Profile screen. ARGB_8888 keeps full int semantics
    // and adds only ~340 KiB per 512×512 bitmap, which is fine here.
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}

@Composable
fun QrCodeImage(content: String, size: Dp = 220.dp) {
    val bitmap = remember(content) { generateQrBitmap(content) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(12.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = Modifier.size(size),
        )
    }
}
