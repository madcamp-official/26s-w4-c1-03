package com.gamdo.app.ui.camera

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val TAG = "CameraBindingOwner"

/**
 * The lifecycle CameraX is bound to — **the Activity's, never this destination's.**
 *
 * ## The trap
 *
 * `LocalLifecycleOwner.current` does not mean what it looks like inside a
 * Navigation-Compose `composable { }`. `NavHost` wraps each destination in
 * `LocalOwnersProvider`, which replaces `LocalLifecycleOwner` with that
 * destination's own [androidx.navigation.NavBackStackEntry]. Reading it there and
 * handing it to `bindToLifecycle` binds the camera to **the screen**, so
 * navigating to the album drops the entry below STARTED and CameraX detaches the
 * use cases by itself:
 *
 * ```
 * Use cases [...] now DETACHED for camera
 * ImageCaptureException: Camera is closed.
 *   at TakePictureManager.abortRequests(TakePictureManager.java:159)
 *   at ImageCapture.onStateDetached(ImageCapture.java:1029)
 *   at Camera2CameraImpl.notifyStateDetachedToUseCases(Camera2CameraImpl.java:1058)
 * ```
 *
 * That is `onStateDetached`, not `unbind()`. It is the third way the same photo
 * was being destroyed, after the cancelled shutter coroutine and the explicit
 * `unbind()` in `onDispose`, and it is the one that fires *first* — measured on
 * SM-G970N 2026-07-30, the detach lands 7ms after the album tap, long before
 * anything of ours runs. [CameraTeardownGate] never even saw the capture, which
 * is why its counters all read zero while the photo still went missing.
 *
 * Nothing in the type system distinguishes the two owners: both are
 * `LifecycleOwner`, both are correct-looking, and the wrong one fails only in the
 * one scenario nobody tests by hand. Hence a named function, a KDoc, and
 * `CameraBindingOwnerTest` pinning that `CameraScreen` does not read
 * `LocalLifecycleOwner` at all.
 *
 * ## Why the Activity and not something wider
 *
 * The Activity is the narrowest owner that outlives a destination change, and its
 * STOP is exactly the moment the camera *should* go away: pressing home or
 * switching apps must release the hardware, and binding here keeps that behaviour
 * for free — `LifecycleCamera` detaches on `ON_STOP` and re-attaches on
 * `ON_START`, as it always did. `ProcessLifecycleOwner` would have the same
 * semantics but needs `androidx.lifecycle:lifecycle-process`, which this module
 * does not depend on, and `androidx.activity.compose.LocalActivity` needs
 * activity-compose 1.10; this project is on 1.9.3.
 *
 * **What this does not do is release the camera when the screen goes away.** That
 * was previously a side effect of binding to the destination, and it is now
 * [CameraTeardownGate]'s job alone — see its KDoc for why every binding is still
 * released within about four seconds of the screen being disposed.
 */
@Composable
internal fun rememberCameraBindingOwner(): LifecycleOwner {
    val context = LocalContext.current
    // Read, and used only as the fallback below. Naming it is deliberate: the
    // point of this whole file is that this value is *not* what the camera binds
    // to, and a reader has to be able to see the one it rejected.
    val destinationOwner = LocalLifecycleOwner.current
    return remember(context, destinationOwner) {
        context.findComponentActivity() ?: destinationOwner.also {
            // Not silent, because the fallback reinstates the bug. There is no
            // configuration in this app that reaches it — the camera screen is
            // hosted by a ComponentActivity — so if this ever prints, the hosting
            // changed and the shutter needs looking at again.
            Log.w(
                TAG,
                "no ComponentActivity behind the camera's context; binding to the " +
                    "destination lifecycle instead. Navigating away mid-capture will " +
                    "abort the capture.",
            )
        }
    }
}

/**
 * Walks the `ContextWrapper` chain to the hosting Activity.
 *
 * Compose's `LocalContext` is the Activity in this app, but it is legal for it to
 * be a themed wrapper around one, so the chain is walked rather than cast.
 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
