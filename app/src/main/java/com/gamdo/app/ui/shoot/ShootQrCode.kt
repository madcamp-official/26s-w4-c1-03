package com.gamdo.app.ui.shoot

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/** P2 QR seam: P1 only needs to display the returned bitmap in its share sheet. */
object ShootQrCode {
    fun encode(url: String, size: Int = 512): Bitmap {
        require(url.startsWith("https://") || url.startsWith("http://")) { "QR must contain an absolute share URL" }
        val matrix = MultiFormatWriter().encode(
            url, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        return matrix.toBitmap()
    }

    private fun BitMatrix.toBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, if (get(x, y)) 0xff000000.toInt() else 0xffffffff.toInt())
    }
}
