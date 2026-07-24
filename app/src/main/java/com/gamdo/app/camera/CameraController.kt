package com.gamdo.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper over CameraX's [LifecycleCameraController] (§1-5). Provides
 * preview + image capture, front/back switching, and (via PreviewView) tap-to-focus
 * and pinch-to-zoom for free. ImageAnalysis is layered on in Day 2.
 */
class CameraController(context: Context) {

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

    fun bind(owner: LifecycleOwner) = camera.bindToLifecycle(owner)

    fun unbind() = camera.unbind()

    fun toggleLens() {
        isFront = !isFront
        camera.cameraSelector =
            if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun setZoom(ratio: Float) {
        camera.setZoomRatio(ratio)
    }

    fun setAnalyzer(executor: Executor, analyzer: ImageAnalysis.Analyzer) {
        camera.setImageAnalysisAnalyzer(executor, analyzer)
    }

    fun clearAnalyzer() {
        camera.clearImageAnalysisAnalyzer()
    }

    /**
     * Captures a photo and returns an upright bitmap (rotation baked into pixels,
     * front camera mirrored to match the preview). Cropping to aspect ratio is
     * left to the caller.
     */
    suspend fun capture(context: Context): Bitmap =
        suspendCancellableCoroutine { cont ->
            camera.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            var bitmap = image.toBitmap().rotated(image.imageInfo.rotationDegrees)
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
