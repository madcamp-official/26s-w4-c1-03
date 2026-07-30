package com.gamdo.app.guide

import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory

/**
 * CameraX-owned transform seam for P1's PreviewView and analysis adapter.
 *
 * The pure [PreviewGeometry] mapper remains the JVM-testable fallback. When a
 * real PreviewView and ImageProxy are available, this adapter asks CameraX for
 * the exact crop/rotation/mirror transform instead of reconstructing it from
 * aspect ratios.
 */
object CameraXPreviewTransformAdapter {
    fun analysisPointToPreview(
        image: ImageProxy,
        preview: PreviewView,
        point: PointN,
    ): PointN? {
        val source = ImageProxyTransformFactory().getOutputTransform(image) ?: return null
        val target = preview.outputTransform ?: return null
        val values = floatArrayOf(point.x * image.width, point.y * image.height)
        CoordinateTransform(source, target).mapPoints(values)
        if (values[0] !in 0f..preview.width.toFloat() || values[1] !in 0f..preview.height.toFloat()) return null
        return PointN(
            (values[0] / preview.width).coerceIn(0f, 1f),
            (values[1] / preview.height).coerceIn(0f, 1f),
        )
    }

    fun previewPointToAnalysis(
        image: ImageProxy,
        preview: PreviewView,
        point: PointN,
    ): PointN? {
        val source = ImageProxyTransformFactory().getOutputTransform(image) ?: return null
        val target = preview.outputTransform ?: return null
        val values = floatArrayOf(point.x * preview.width, point.y * preview.height)
        CoordinateTransform(target, source).mapPoints(values)
        if (values[0] !in 0f..image.width.toFloat() || values[1] !in 0f..image.height.toFloat()) return null
        return PointN(
            (values[0] / image.width).coerceIn(0f, 1f),
            (values[1] / image.height).coerceIn(0f, 1f),
        )
    }
}
