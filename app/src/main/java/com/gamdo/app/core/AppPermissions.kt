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

    /**
     * The classifier behind [PhotoAccessLevel.of] — kept here (not inline in
     * `of`) only so its KDoc has somewhere to live; see that function.
     */
    enum class PhotoAccessLevel {
        /** The app can see the whole device photo library. */
        FULL,

        /**
         * API 34+ only: the user picked "Select photos…" instead of "Allow all".
         * [Manifest.permission.READ_MEDIA_IMAGES] is denied but
         * [Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] is granted, and the
         * MediaStore query the app already runs is transparently scoped by the OS to
         * just the photos the user chose — this is not a denial and must not be
         * treated as [NONE] (W3.5-1).
         */
        PARTIAL,

        /** Neither the full-library nor the partial-selection permission is held. */
        NONE,
        ;

        companion object {
            /**
             * Classifies the current media-read grant for [sdkInt], given the set of
             * permission strings the system currently reports as granted.
             *
             * - API ≤ 32: only [Manifest.permission.READ_EXTERNAL_STORAGE] exists.
             *   Granted → [FULL], otherwise → [NONE]. There is no partial tier.
             * - API 33: only [Manifest.permission.READ_MEDIA_IMAGES] exists (the
             *   partial-selection permission was not introduced until 34, so even if
             *   [granted] somehow contained it — the system does not grant it on this
             *   version — it must not be read as [PARTIAL] here).
             * - API 34+: [Manifest.permission.READ_MEDIA_IMAGES] granted → [FULL]
             *   outright, regardless of the partial permission. Otherwise, if
             *   [Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] is granted →
             *   [PARTIAL]. Otherwise → [NONE].
             *
             * This device is API 31, so the 34+ branch cannot be exercised on real
             * hardware in this pass — see the JVM tests in `AppPermissionsTest` for
             * the API-34 behaviour instead.
             */
            fun of(sdkInt: Int, granted: Set<String>): PhotoAccessLevel {
                val fullPermission = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    @Suppress("DEPRECATION")
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                if (fullPermission in granted) return FULL
                if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in granted
                ) {
                    return PARTIAL
                }
                return NONE
            }
        }
    }
}
