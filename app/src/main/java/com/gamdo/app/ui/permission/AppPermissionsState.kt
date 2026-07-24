package com.gamdo.app.ui.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gamdo.app.core.AppPermissions
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
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
