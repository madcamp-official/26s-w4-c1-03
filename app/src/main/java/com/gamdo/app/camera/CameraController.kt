package com.gamdo.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.Observer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class ZoomBounds(val min: Float = 1f, val max: Float = 1f)

private const val TAG = "CameraController"

/** Seconds a tap-driven focus lock holds before CameraX returns to continuous AF. */
private const val AUTO_CANCEL_SECONDS = 3L

/**
 * Thin wrapper over CameraX's [LifecycleCameraController] (§1-5). Provides
 * preview + image capture, front/back switching, and analysis (Day 2).
 *
 * **Both built-in touch gestures are switched off below and the app drives them
 * itself.** They are not a loss we absorbed: they were never reachable. `PreviewView`
 * feeds them from `onTouchEvent`, and the Compose pinch surface stacked over the
 * preview consumes every DOWN before the view sees it. Leaving them enabled meant
 * one change in layer order would silently double-apply pinch and resurrect a
 * tap-to-focus that focuses on the letterbox bars — see `ui/camera/TapFocusGeometry.kt`.
 */
class CameraController(context: Context) {

    private var observedZoomState: LiveData<ZoomState>? = null
    private var previewEffect: CameraEffect? = null
    private var isBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachZoomRunnable = Runnable { attachZoomObserver() }

    private val appContext = context.applicationContext

    /**
     * W3-1 thermal observation, scoped to the camera session because that is the
     * only interval §7-1 asks about. Debug-only and null in release; see
     * [ThermalStatusProbe].
     */
    private var thermalProbe: ThermalStatusProbe? = null

    private val zoomObserver = Observer<ZoomState> { state ->
        _zoomRatio.value = state.zoomRatio
        _zoomBounds.value = ZoomBounds(state.minZoomRatio, state.maxZoomRatio)
    }

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _zoomBounds = MutableStateFlow(ZoomBounds())
    val zoomBounds: StateFlow<ZoomBounds> = _zoomBounds.asStateFlow()

    val camera: LifecycleCameraController =
        LifecycleCameraController(context.applicationContext).apply {
            setEnabledUseCases(
                LifecycleCameraController.IMAGE_CAPTURE or LifecycleCameraController.IMAGE_ANALYSIS,
            )
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // Off explicitly, not by accident. Compose owns both gestures now.
            // NOTE: these live on CameraController, not on PreviewView — the view
            // only forwards touches here. isTapToFocusEnabled gates *this* class's
            // internal onTapToFocus() and has no effect on [focusAt], which goes
            // straight to cameraControl.startFocusAndMetering.
            isPinchToZoomEnabled = false
            isTapToFocusEnabled = false
            // Backpressure: analysis may lag, preview must not (§2-1).
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            // Downscale analysis frames to ~640px long side, forced 4:3.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()
            // Match the preview FOV to the analysis (both 4:3) so overlay boxes
            // computed from analysis-normalized coords line up with the preview (§2-5).
            previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            // The capture has to share that FOV too, and until now it did not — it
            // took the device default. §3-3 projects the detector's normalized
            // subject box into stored-file coordinates (`SubjectProjection`), and
            // that projection starts from "the capture is 4:3". On SM-G970N it
            // happened to be (3024×4032), which is why the arithmetic matched the
            // measured file — but a device defaulting ImageCapture to 16:9 has a
            // different vertical field of view, and the box would land off the
            // person with nothing failing. This turns the coincidence into a
            // contract.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
        }

    var isFront: Boolean = false
        private set

    /**
     * O-14's preview colour effect, or null for none.
     *
     * Held rather than applied immediately when nothing is bound yet, because
     * `setEffects` on a *bound* controller unbinds and rebinds the whole use-case
     * group — a visible preview re-init. The GL pipeline is built asynchronously
     * (see `PreviewColorEffect.create`), so it usually is not ready before [bind]
     * and one rebind is paid; when it is ready in time, [bind] applies it as part of
     * the initial bind and nothing is paid at all.
     *
     * Null clears. That is the fallback path: a GL failure ends here, CameraX
     * rebinds the preview straight to its surface, and the camera keeps running
     * without colour.
     */
    fun setPreviewEffect(effect: CameraEffect?) {
        previewEffect = effect
        if (isBound) applyPreviewEffect()
    }

    private fun applyPreviewEffect() {
        val effect = previewEffect
        runCatching {
            if (effect == null) camera.clearEffects() else camera.setEffects(setOf(effect))
        }.onFailure {
            // An effect CameraX will not accept must not be the reason the camera
            // fails to start. Drop it and carry on uncoloured.
            Log.w(TAG, "preview effect rejected; continuing without it", it)
            previewEffect = null
            runCatching { camera.clearEffects() }
        }
    }

    fun bind(owner: LifecycleOwner) {
        applyPreviewEffect()
        camera.bindToLifecycle(owner)
        isBound = true
        attachZoomObserver()
        mainHandler.postDelayed(attachZoomRunnable, 300L)
        // Started here rather than in the camera screen because the screen belongs to
        // another vertical. This is also the tighter window: it opens when the camera
        // actually starts, so the observed duration in the `detach` line is time the
        // preview was running and not time the app was merely alive.
        thermalProbe = ThermalStatusProbe.start(appContext)
    }

    fun unbind() {
        isBound = false
        mainHandler.removeCallbacks(attachZoomRunnable)
        observedZoomState?.removeObserver(zoomObserver)
        observedZoomState = null
        camera.unbind()
        ThermalStatusProbe.stop(thermalProbe)
        thermalProbe = null
    }

    private fun attachZoomObserver() {
        val zoomState = camera.cameraInfo?.zoomState ?: return
        if (observedZoomState === zoomState) return
        observedZoomState?.removeObserver(zoomObserver)
        observedZoomState = zoomState
        zoomState.observeForever(zoomObserver)
        zoomState.value?.let { zoomObserver.onChanged(it) }
    }

    fun toggleLens() {
        isFront = !isFront
        camera.cameraSelector =
            if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // CameraX replaces cameraInfo when the lens changes; reconnect the
        // observer so the fixed on-screen readout follows the new lens.
        mainHandler.postDelayed(attachZoomRunnable, 300L)
    }

    /** Sets zoom, clamped to the device's supported range (e.g. no ultra-wide → min 1.0). */
    fun setZoom(ratio: Float) {
        val bounds = _zoomBounds.value
        val stepped = (ratio * 10f).roundToInt() / 10f
        camera.setZoomRatio(stepped.coerceIn(bounds.min, bounds.max))
        camera.cameraInfo?.zoomState?.value?.zoomRatio?.let { _zoomRatio.value = it }
    }

    /**
     * Drives AF+AE to a point, replacing the disabled built-in tap-to-focus.
     *
     * [factory] must be `PreviewView.meteringPointFactory` — it is the only factory
     * that knows the FILL_CENTER crop and the sensor orientation. [x] and [y] are
     * view pixels; `ui/camera/TapFocusGeometry.kt` decides which taps get here.
     *
     * Silent by design in both failure modes. Before the first bind `cameraControl`
     * is null, and a device can reject an unsupported metering point synchronously;
     * neither is actionable by the user, and §3-2 allows no focus ring to report it
     * to (feedback is the preview racking). The request auto-cancels back to
     * continuous AF after [AUTO_CANCEL_SECONDS] so a stale tap cannot hold focus.
     */
    fun focusAt(factory: MeteringPointFactory, x: Float, y: Float) {
        val control = camera.cameraControl ?: return
        val action = FocusMeteringAction
            .Builder(
                factory.createPoint(x, y),
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            )
            .setAutoCancelDuration(AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()
        runCatching { control.startFocusAndMetering(action) }
            .onFailure { Log.d(TAG, "focus request rejected", it) }
    }

    fun setAnalyzer(executor: Executor, analyzer: ImageAnalysis.Analyzer) {
        camera.setImageAnalysisAnalyzer(executor, analyzer)
    }

    fun clearAnalyzer() {
        camera.clearImageAnalysisAnalyzer()
    }

    /**
     * Captures a photo and returns the finished bitmap: viewport-cropped, upright,
     * front-camera mirrored, and cropped to [targetRatioWtoH] if one is given.
     *
     * The viewport cropRect — attached by LifecycleCameraController from the
     * PreviewView — is part of the same composition, so the result contains exactly
     * what the preview showed (WYSIWYG).
     *
     * Decode and transform run on a background dispatcher, not the main thread: a
     * full-resolution JPEG decode is hundreds of ms and ~45MB per copy.
     *
     * **The aspect crop belongs here, not in the caller.** It used to run on the
     * screen, one more full-resolution `createBitmap` after this function had
     * already made three; folding it into the same plan is what turns four copies
     * into one. [captureGeometryFor] composes the four steps as arithmetic and
     * `CaptureGeometryTest` proves the composition reads the same pixels the chain
     * did, including the front camera's off-by-one at odd trims.
     *
     * @param targetRatioWtoH width:height to centre-crop to (0.8 = 4:5, 1.0 = 1:1),
     *   or null to return the full uncropped frame.
     * @param trace debug instrumentation, null in release builds. Marks
     *   [CapturePhase.CAMERA_X] the instant CameraX hands the image over and
     *   [CapturePhase.DECODE] once the finished bitmap exists. **Those two are worth
     *   separating precisely because logcat cannot separate them**: the work below
     *   runs inside CameraX's own callback, so from the outside it is
     *   indistinguishable from CameraX being slow.
     */
    suspend fun capture(
        trace: CaptureTrace? = null,
        targetRatioWtoH: Float? = null,
    ): Bitmap =
        suspendCancellableCoroutine { cont ->
            camera.takePicture(
                Dispatchers.Default.asExecutor(),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // First statement in the callback: everything before it is
                        // CameraX, everything after it is ours.
                        trace?.mark(CapturePhase.CAMERA_X)
                        try {
                            val decoded = image.toBitmap()
                            // Planned against the decoded bitmap's own dimensions
                            // rather than the ImageProxy's, so nothing here depends
                            // on those two agreeing.
                            val plan = captureGeometryFor(
                                bufferWidth = decoded.width,
                                bufferHeight = decoded.height,
                                crop = image.cropRect.let {
                                    CropRect(it.left, it.top, it.right, it.bottom)
                                },
                                rotationDegrees = image.imageInfo.rotationDegrees,
                                mirror = isFront,
                                targetRatioWtoH = targetRatioWtoH,
                            )
                            val bitmap = decoded.transformedBy(plan)
                            trace?.mark(CapturePhase.DECODE)
                            cont.resume(bitmap)
                        } catch (t: Throwable) {
                            cont.resumeWithException(t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
}
