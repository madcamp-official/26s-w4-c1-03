package com.gamdo.app.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
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
