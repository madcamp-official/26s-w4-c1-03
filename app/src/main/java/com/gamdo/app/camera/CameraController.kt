package com.gamdo.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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

/**
 * Thin wrapper over CameraX's [LifecycleCameraController] (§1-5). Provides
 * preview + image capture, front/back switching, and (via PreviewView) tap-to-focus
 * and pinch-to-zoom for free. ImageAnalysis is layered on in Day 2.
 */
class CameraController(context: Context) {

    private var observedZoomState: LiveData<ZoomState>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachZoomRunnable = Runnable { attachZoomObserver() }

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
        }

    var isFront: Boolean = false
        private set

    fun bind(owner: LifecycleOwner) {
        camera.bindToLifecycle(owner)
        attachZoomObserver()
        mainHandler.postDelayed(attachZoomRunnable, 300L)
    }

    fun unbind() {
        mainHandler.removeCallbacks(attachZoomRunnable)
        observedZoomState?.removeObserver(zoomObserver)
        observedZoomState = null
        camera.unbind()
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

    fun setAnalyzer(executor: Executor, analyzer: ImageAnalysis.Analyzer) {
        camera.setImageAnalysisAnalyzer(executor, analyzer)
    }

    fun clearAnalyzer() {
        camera.clearImageAnalysisAnalyzer()
    }

    /**
     * Captures a photo and returns an upright bitmap (rotation baked into pixels,
     * front camera mirrored to match the preview). The viewport cropRect —
     * attached by LifecycleCameraController from the PreviewView — is applied
     * first so the result contains exactly what the preview showed (WYSIWYG).
     * Cropping to the selected aspect ratio is left to the caller.
     *
     * Decode/rotate run on a background dispatcher, not the main thread: a
     * full-resolution JPEG decode is hundreds of ms and ~50MB per copy.
     */
    suspend fun capture(): Bitmap =
        suspendCancellableCoroutine { cont ->
            camera.takePicture(
                Dispatchers.Default.asExecutor(),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            var bitmap = image.toBitmap()
                                .cropped(image.cropRect)
                                .rotated(image.imageInfo.rotationDegrees)
                            if (isFront) bitmap = bitmap.mirroredHorizontally()
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
