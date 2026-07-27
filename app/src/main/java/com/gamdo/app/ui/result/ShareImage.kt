package com.gamdo.app.ui.result

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "ShareImage"

/**
 * §6-3 "공유는 OS 공유 시트만" — hands one photo to the system chooser.
 *
 * The app picks no target and sends nothing itself; it opens the chooser and the
 * user decides where the photo goes, which is the only sharing D4 leaves room for.
 *
 * Photos live in internal storage (`filesDir/captures/`, `filesDir/edits/`), so a
 * `file://` URI would be unreadable to the receiving app and a `content://` one is
 * required. The grant is per-URI and read-only, and it lasts for the one Intent.
 *
 * @return false when there was nothing to share or no app to share with, so the
 *   caller can say so instead of appearing to do nothing.
 */
fun shareImage(context: Context, file: File): Boolean {
    if (!file.exists()) {
        Log.w(TAG, "nothing to share at ${file.name}")
        return false
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrElse {
        // Thrown when the file sits outside every <files-path> in file_paths.xml.
        Log.w(TAG, "not a shareable path: ${file.absolutePath}", it)
        return false
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // createChooser rather than the bare intent: without it a user who once picked
    // a default sees the photo leave immediately with no chance to redirect it.
    val chooser = Intent.createChooser(send, "사진 공유").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(chooser) }
        .onFailure { Log.w(TAG, "no activity to receive the share", it) }
        .isSuccess
}
