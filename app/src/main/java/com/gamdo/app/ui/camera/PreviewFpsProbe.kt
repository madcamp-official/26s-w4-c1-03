package com.gamdo.app.ui.camera

import androidx.camera.view.PreviewView
import com.gamdo.app.BuildConfig

/**
 * Whether the preview rate (§7-1) can be read, and when it cannot, why.
 *
 * An enum rather than a nullable number because "no measurement" and "a measured
 * zero" are opposite findings — one means the instrument is absent, the other
 * means the preview is frozen — and W3-2 exists because those two were previously
 * indistinguishable on the HUD.
 */
enum class PreviewFpsAvailability {
    /** A per-frame source is attached; [com.gamdo.app.camera.PreviewStats] is real. */
    MEASURING,

    /** No preview surface yet, or the analyzer is detached. */
    NOT_ATTACHED,

    /**
     * `PreviewView` is in `PERFORMANCE` mode, so there is no per-frame callback to
     * attach — see [attachPreviewFrameProbe].
     */
    UNAVAILABLE_PERFORMANCE_MODE,

    /** The attach was rejected for some other reason; the message goes to logcat. */
    UNAVAILABLE_ERROR,
}

/**
 * Whether this build measures the preview rate, at the cost of rendering the
 * preview through a `TextureView`.
 *
 * **On in debug builds, off in release (owner decision, 2026-07-29).** The full
 * position:
 *
 * CameraX 1.4.1 does expose a genuine per-preview-frame callback —
 * `PreviewView.setFrameUpdateListener`, delivering one call per frame from
 * `TextureViewImplementation`'s `onSurfaceTextureUpdated`. It is not usable as the
 * app stands. The method's first act is
 * `if (mImplementationMode == PERFORMANCE) throw IllegalArgumentException`, and
 * this app never calls `setImplementationMode`, so `PreviewView` sits on its
 * `PERFORMANCE` default and renders through a `SurfaceView`. A `SurfaceView`'s
 * buffers are consumed by SurfaceFlinger without passing through the view tree,
 * which is why no callback exists for that path: the app process genuinely cannot
 * count those frames.
 *
 * So measuring costs a switch to `COMPATIBLE`, i.e. one extra copy per preview
 * frame — spending preview performance to measure preview performance. The trade
 * is accepted only in debug: a `TextureView` reading is a **lower bound** for the
 * cheaper `SurfaceView` path, so a debug reading of ≥30fps means the shipped path
 * is at least that fast — which is exactly the direction §7-1's "프리뷰 30FPS"
 * needs. Release keeps the `PERFORMANCE` default and reports 미측정.
 *
 * **This means the demo measures itself.** The build on the device is
 * `com.gamdo.app.debug`, so the rehearsal (W4-2) runs the `TextureView` path.
 * That is the conservative direction — the release build can only be faster — but
 * anyone comparing a debug preview against a release one should expect debug to
 * be the slower of the two, not treat the difference as a regression.
 *
 * Note the `PreviewView` factory in `CameraScreen` carries a comment claiming the
 * app already runs `COMPATIBLE`. It does not, and never has —
 * `git log -S "implementationMode ="` over `ui/camera/` is empty. The comment
 * arrived in 609f67b next to the `clipToBounds` fix, in the same commit whose
 * neighbouring comment records that `COMPATIBLE` "changed nothing" for the bug
 * being fixed.
 */
val MEASURE_PREVIEW_FPS: Boolean = BuildConfig.DEBUG

/**
 * Attaches a per-preview-frame tick to [onFrameNs], reporting whether it took.
 *
 * The callback runs on whatever thread `PreviewView` delivers frames on (the main
 * thread) via a direct executor: the work is one comparison and an occasional
 * `StateFlow` write, so posting 30 `Runnable`s a second to the main looper would
 * cost more than the measurement.
 *
 * The frame's own `SurfaceTexture` timestamp is deliberately discarded — its
 * timebase is not guaranteed across devices — in favour of delivery time, which is
 * also the honest reading of "how often did the preview update".
 */
fun PreviewView.attachPreviewFrameProbe(onFrameNs: (Long) -> Unit): PreviewFpsAvailability {
    if (implementationMode == PreviewView.ImplementationMode.PERFORMANCE) {
        return PreviewFpsAvailability.UNAVAILABLE_PERFORMANCE_MODE
    }
    return runCatching {
        setFrameUpdateListener({ command -> command.run() }) { onFrameNs(System.nanoTime()) }
        PreviewFpsAvailability.MEASURING
    }.getOrElse { PreviewFpsAvailability.UNAVAILABLE_ERROR }
}
