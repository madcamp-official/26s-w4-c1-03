package com.gamdo.app.ui.result

import android.net.Uri

/**
 * What the 보정 screen was opened on.
 *
 * The screen used to take a bare `captureId: String`, which had two problems the
 * moment the album started listing the device library (W3.5-6):
 *
 *  1. A `MediaStore` photo has no `captures` row, and an id with no row makes
 *     `capturesDao().get(id)` return null **forever** — the screen sat on
 *     "사진을 불러오는 중이에요" with nothing on its way. A sentinel id (`""`) for
 *     device photos would have landed in exactly that state.
 *  2. `captureId` is what decides whether auto-correction may run (O-12), and a
 *     `String` cannot express "there is no capture, on purpose" distinctly from
 *     "the capture has not loaded yet".
 *
 * Making it a sealed type means the source of a photo is known before anything is
 * read, both branches are total, and neither one can be reached without the value
 * it needs. [kind] is the pure projection the O-12 rules in `ResultFlowDecisions.kt`
 * are written against.
 */
sealed interface ResultTarget {

    /** Which side of O-12 this target falls on. */
    val kind: EditSourceKind

    /** A photo this app shot — a `captures` row with `conditions_json` behind it. */
    data class AppCapture(val captureId: String) : ResultTarget {
        override val kind: EditSourceKind get() = EditSourceKind.APP_CAPTURE
    }

    /**
     * A photo from the user's library, addressed by its `MediaStore` content Uri.
     *
     * Carrying the Uri rather than a path is not a style choice: scoped storage
     * makes `MediaStore.Images.Media.DATA` unreadable, so `ContentResolver
     * .openInputStream` is the only way in. See `edit/EditImageSource.kt`.
     */
    data class DevicePhoto(val uri: Uri) : ResultTarget {
        override val kind: EditSourceKind get() = EditSourceKind.DEVICE_PHOTO
    }
}
