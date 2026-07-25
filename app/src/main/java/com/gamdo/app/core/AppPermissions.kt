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
     * Permissions the app *requests* at runtime, resolved for the running OS version:
     * - CAMERA always.
     * - Reading photos: READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE on 32 and below.
     * - API 34+ additionally requests READ_MEDIA_VISUAL_USER_SELECTED so the
     *   "selected photos only" choice results in a usable grant.
     */
    fun required(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }

    /**
     * Photo-read is satisfied when ANY of these is granted. On Android 14+ a
     * "selected photos" grant leaves READ_MEDIA_IMAGES denied — treating that as
     * blocked would dead-lock the gate even though the user granted access.
     */
    fun mediaReadAlternatives(): Set<String> = buildSet {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
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
