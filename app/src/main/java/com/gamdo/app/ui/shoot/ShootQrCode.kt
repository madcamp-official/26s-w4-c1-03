package com.gamdo.app.ui.shoot

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * P2 QR seam: P1 only needs to display the returned bitmap in its share sheet.
 *
 * ## The QR's host is a **build-time** decision, and a local build breaks it
 *
 * The server returns `shareUrl` as a *relative* `/shoot/{token}`;
 * `ShootSessionRepository` makes it absolute with `GamdoApiClient.publicUrl()`, which
 * prefixes `BuildConfig.GAMDO_API_BASE_URL` minus its `api/v1/` suffix. So whatever
 * `-PgamdoApiBaseUrl` was passed at assemble time is the host a **second phone** has to
 * resolve after scanning this bitmap.
 *
 *  - Default (`https://api.anjonghwa.madcamp-kaist.org/api/v1/`) — works.
 *  - `-PgamdoApiBaseUrl=http://127.0.0.1:18000/api/v1/` (USB/tunnel) or
 *    `http://10.0.2.2:8000/api/v1/` (emulator) — the QR encodes a loopback address.
 *    The friend's phone resolves it to **itself** and gets nothing. Nothing in the app
 *    or the server can detect this: the session is created fine, the app polls fine,
 *    and the link is simply dead in someone else's hand.
 *  - A LAN address (`http://192.168.x.y:8000/api/v1/`) works only while both phones
 *    are on that network.
 *
 * A demo of this flow therefore has to be built against a host reachable from the
 * scanning device. This is not something [encode] can validate — a loopback URL is a
 * perfectly well-formed absolute URL — so it is written down here instead.
 */
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
