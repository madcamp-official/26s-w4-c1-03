package com.gamdo.app.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.gamdo.app.camera.CameraController
import com.gamdo.app.camera.gl.PreviewColorEffect
import com.gamdo.app.camera.gl.PreviewFilterSpec
import com.gamdo.app.edit.PhotoFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O-13 (1) — attaches the selected preset's colour to the live preview, and takes
 * it away again if the GPU cannot do it.
 *
 * Kept out of `CameraScreen` so the screen takes a one-line call: the lifecycle
 * here is three-sided (Compose, the GL thread, CameraX's rebind) and it reads much
 * worse inlined among the gesture and overlay wiring.
 *
 * ## What the user sees, and how to tell the two "no colour" states apart
 *
 * Selecting a preset changes the preview's colour. If the effect could not start —
 * or started and failed — the preview keeps running with the camera's own colour
 * and one `W/PreviewColorEffect` line appears in logcat. There is deliberately **no
 * on-screen indication**: D2 forbids status text over the preview, and a preview
 * that quietly looks like the camera is far better than a preview that does not
 * exist. Logcat is how the two are distinguished from "the user picked 원본".
 *
 * @param presetId the active style's `presets.json` id, or null for none. Maps onto
 *   a [PhotoFilters] recipe by the **same** `byId` the editor's strip uses, so a
 *   change to a preset moves preview and saved file together.
 */
@Composable
fun PreviewColorBinding(
    controller: CameraController,
    presetId: String?,
    aspect: CaptureAspect,
) {
    var effect by remember { mutableStateOf<PreviewColorEffect?>(null) }
    val scope = rememberCoroutineScope()
    // The detach callback arrives on the GL thread and must not capture a stale
    // controller across a recomposition.
    val currentController by rememberUpdatedState(controller)

    DisposableEffect(controller) {
        var created: PreviewColorEffect? = null
        val job = scope.launch {
            // GL setup — context creation and shader compilation — off the main
            // thread. It is also the point where a hostile driver fails, and
            // failing here costs nothing: the preview has already bound without us.
            val made = withContext(Dispatchers.Default) {
                PreviewColorEffect.create { _ ->
                    // Runs on the GL thread. Detaching is a CameraX rebind and has
                    // to happen on main.
                    scope.launch { currentController.setPreviewEffect(null) }
                // Published to `created` *inside* the block, before withContext can
                // return. Assigning on the next line instead would leak an entire
                // EGL context and its thread whenever the screen is left during the
                // ~tens of ms setup takes: withContext throws on resumption if the
                // scope was cancelled, so the assignment would never run and
                // onDispose would have nothing to release.
                }?.also { created = it }
            } ?: return@launch
            made.aspectRatioWtoH = aspect.ratioWtoH
            currentController.setPreviewEffect(made)
            made.onAttached()
            effect = made
        }
        onDispose {
            job.cancel()
            controller.setPreviewEffect(null)
            created?.release()
            effect = null
        }
    }

    LaunchedEffect(effect, presetId) {
        val active = effect ?: return@LaunchedEffect
        // Building a spec runs FilterEngine's tone curve and hue tables and packs a
        // 14 KB texture — small, but not something to do on the frame the user
        // tapped a preset chip on.
        active.spec = withContext(Dispatchers.Default) {
            PreviewFilterSpec.of(PhotoFilters.byId(presetId))
        }
    }

    LaunchedEffect(effect, aspect) {
        effect?.aspectRatioWtoH = aspect.ratioWtoH
    }
}
