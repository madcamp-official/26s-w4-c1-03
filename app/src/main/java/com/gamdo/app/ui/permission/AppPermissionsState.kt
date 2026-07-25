package com.gamdo.app.ui.permission

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gamdo.app.core.AppPermissions
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Shared permission-state hook — the single place any screen reads/triggers the
 * app's runtime permissions (the Compose equivalent of the spec's `usePermissions`).
 *
 * Use [MultiplePermissionsState.allPermissionsGranted] to gate features and
 * [MultiplePermissionsState.launchMultiplePermissionRequest] to prompt.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberAppPermissionsState(): MultiplePermissionsState {
    val permissions = remember { AppPermissions.required() }
    return rememberMultiplePermissionsState(permissions)
}

/**
 * Whether the app is effectively permitted — CAMERA plus *any* photo-read grant.
 * Weaker than [MultiplePermissionsState.allPermissionsGranted] on purpose:
 * Android 14+'s "selected photos" leaves READ_MEDIA_IMAGES denied while granting
 * READ_MEDIA_VISUAL_USER_SELECTED, which counts as permitted here.
 */
@OptIn(ExperimentalPermissionsApi::class)
fun MultiplePermissionsState.isSatisfied(): Boolean {
    val mediaAlternatives = AppPermissions.mediaReadAlternatives()
    val cameraGranted = permissions.any {
        it.permission == Manifest.permission.CAMERA && it.status.isGranted
    }
    val mediaGranted = permissions.any {
        it.permission in mediaAlternatives && it.status.isGranted
    }
    return cameraGranted && mediaGranted
}
