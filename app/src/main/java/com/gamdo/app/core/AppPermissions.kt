package com.gamdo.app.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Runtime permission policy for the app, in one place so UI and future callers
 * agree on exactly what is required per OS version.
 */
object AppPermissions {

    /**
     * Permissions the app needs at runtime, resolved for the running OS version:
     * - CAMERA always.
     * - Reading photos: READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE on 32 and below.
     */
    fun required(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Intent to this app's details page in system Settings — the recovery path
     * when a permission was denied with "don't ask again".
     */
    fun appDetailsSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
