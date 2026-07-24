package com.gamdo.app.ui.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi

/**
 * Which permission screen to show while the app is not yet fully permitted.
 */
enum class PermissionStage {
    /** Not yet asked — explain why, then request. */
    INTRO,

    /** Denied at least once but the system will still show the dialog on retry. */
    RATIONALE,

    /** Denied with "don't ask again" — only Settings can grant it now. */
    BLOCKED,
}

/**
 * Gates [content] behind the app's runtime permissions (§1-2).
 *
 * When permissions are missing it shows an intro / rationale / blocked screen
 * instead — so denying everything never crashes the app, it lands on guidance.
 * Accompanist re-checks permission status on lifecycle resume, so returning
 * from Settings with the grant applied flips straight through to [content].
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberAppPermissionsState()

    // Whether we've launched a request at least once this process. Combined with
    // shouldShowRationale it separates first-run from "don't ask again".
    var requested by rememberSaveable { mutableStateOf(false) }

    if (state.allPermissionsGranted) {
        content()
        return
    }

    val stage = when {
        requested && !state.shouldShowRationale -> PermissionStage.BLOCKED
        requested || state.shouldShowRationale -> PermissionStage.RATIONALE
        else -> PermissionStage.INTRO
    }

    PermissionScreen(
        stage = stage,
        modifier = modifier,
        onRequest = {
            requested = true
            state.launchMultiplePermissionRequest()
        },
    )
}
