package com.yueyin.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val QR_DARK = 0xFF0B0E13.toInt()
private const val QR_LIGHT = 0xFFFFFFFF.toInt()

/** 二维码由 zxing 在本地生成，不联网、不申请任何权限。 */
private fun buildQrBitmap(content: String, pixels: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val width = matrix.width
    val height = matrix.height
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            argb[row + x] = if (matrix[x, y]) QR_DARK else QR_LIGHT
        }
    }
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}.getOrNull()

@Composable
internal fun FnQrCode(
    content: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { buildQrBitmap(content, 512)?.asImageBitmap() }
    Box(
        modifier
            .size(size)
            // 白底同时充当二维码静区，深色界面上扫码更稳。
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(6.dp)
            .semantics { contentDescription = "二维码：$content" },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal const val REPOSITORY_URL = "https://github.com/Traceci/yueyin-tv"
internal const val ISSUE_URL = "$REPOSITORY_URL/issues/new/choose"
internal const val REPOSITORY_LABEL = "github.com/Traceci/yueyin-tv"
