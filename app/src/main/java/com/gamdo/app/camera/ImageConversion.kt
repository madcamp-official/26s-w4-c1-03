package com.gamdo.app.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * Converts an analysis [ImageProxy] (YUV_420_888) to an upright RGB [Bitmap],
 * applying the sensor rotation. Downscaling is handled by the ImageAnalysis
 * resolution selector (≈640px long side), so this stays cheap. (§2-1)
 *
 * CameraX's [ImageProxy.toBitmap] does the YUV→RGB conversion; [rotated] bakes in
 * the orientation. ML Kit's InputImage path (which can skip the RGB copy) is
 * added alongside this in §2-2.
 */
fun ImageProxy.toAnalysisBitmap(): Bitmap =
    toBitmap().rotated(imageInfo.rotationDegrees)
