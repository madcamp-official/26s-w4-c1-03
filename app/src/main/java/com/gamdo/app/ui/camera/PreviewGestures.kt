package com.gamdo.app.ui.camera

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Preview gesture installation (§1-5) — pure Kotlin, no `android.*`, no Compose.
 *
 * Lives outside the composable for the same reason as `TapFocusGeometry`: the one
 * decision it encodes is the difference between the first tap of the session working
 * and being swallowed, and that decision is reachable from a JVM test. See
 * `PreviewGesturesTest`.
 */

/**
 * Runs the preview's two gestures — tap-to-focus and pinch-to-zoom — as siblings
 * inside the caller's `Modifier.pointerInput` block, **both started undispatched**.
 *
 * ## Why `UNDISPATCHED`, and why it was worth chasing
 *
 * The symptom was that the first preview gesture after a cold start did nothing,
 * every launch, on SM-G970N; instrumentation showed gesture #1 arriving as a Release
 * with no Press, and #2 arriving whole. That was read as Compose starting the
 * `pointerInput` coroutine lazily on the first event and therefore missing the DOWN
 * that started it — which would have meant the fix lay in how `PreviewView` is bound
 * (a `surfaceProvider` instead of a `CameraController`), well outside this file.
 *
 * The bytecode says otherwise. `SuspendingPointerInputModifierNodeImpl.onPointerEvent`
 * in compose-ui 1.7.5 is:
 *
 * ```
 * if (pointerInputJob == null) {
 *     launch(coroutineScope, null, CoroutineStart.UNDISPATCHED, ...)   // (1)
 * }
 * dispatchPointerEvent(pointerEvent, pass)                             // (2)
 * ```
 *
 * The start *is* lazy, but it is deliberately `UNDISPATCHED` and it happens before
 * (2) — Compose runs the handler body inline precisely so it can park on
 * `awaitPointerEventScope` before the event that started it is delivered.
 * `awaitPointerEventScope` cooperates: it appends to `pointerHandlers` and resumes
 * the block synchronously, inside its `suspendCancellableCoroutine`.
 *
 * So Compose handed us a working first event and we dropped it. The old body was
 *
 * ```
 * coroutineScope {
 *     launch { detectTapGestures { … } }
 *     launch { detectTransformGestures { … } }
 * }
 * ```
 *
 * and a plain `launch` is `CoroutineStart.DEFAULT` — *dispatched*. Both children were
 * posted to the main dispatcher and had not reached `awaitPointerEventScope` when (2)
 * ran, so `pointerHandlers` was empty and the DOWN went nowhere. By the time the UP
 * arrived, a loop-turn later, both were parked — hence "a Release with no Press".
 * Starting them undispatched closes the window: `detectTapGestures` and
 * `detectTransformGestures` both open with `awaitEachGesture`, which reaches
 * `awaitPointerEventScope` with no suspension in between, so both are registered
 * before this function returns control to the node.
 *
 * The same mechanism explains the other measured symptom recorded in
 * `CameraPreviewPane`: keying the `pointerInput` on `aspect` dropped the first tap
 * after every 4:5 ↔ 1:1 switch, 3/3 in both directions. A key change calls
 * `resetPointerInputHandler()`, which nulls `pointerInputJob`, putting the next event
 * back through the same path. That call site keeps reading the aspect through
 * `rememberUpdatedState` regardless — not restarting the handler is still better than
 * restarting it correctly — but it is no longer the only thing standing between the
 * user and a lost tap.
 *
 * ## What this deliberately does not do
 *
 * It does not replay, synthesise or buffer anything. A dropped tap is a far smaller
 * bug than a focus pull the user did not ask for, so the fix is only ever "be
 * listening in time" — every gesture still has to arrive as a real DOWN followed by a
 * real UP. Both handlers see identical input to what they saw before; they simply see
 * it from the first event instead of the second.
 *
 * @param tap the tap-to-focus handler, normally `detectTapGestures { … }`.
 * @param pinch the pinch-to-zoom handler, normally `detectTransformGestures { … }`.
 *   Installed second, so [tap] registers first and keeps the dispatch order the
 *   dispatched version happened to have.
 */
suspend fun installPreviewGestures(
    tap: suspend () -> Unit,
    pinch: suspend () -> Unit,
) {
    coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) { tap() }
        launch(start = CoroutineStart.UNDISPATCHED) { pinch() }
    }
}
