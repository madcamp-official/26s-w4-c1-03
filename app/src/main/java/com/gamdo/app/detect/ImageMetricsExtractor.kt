package com.gamdo.app.detect

import android.graphics.Bitmap
import kotlin.math.min

/**
 * Small on-device adapter from a Bitmap to the platform-free diagnostics
 * contract. It intentionally uses a bounded grayscale sample so entering the
 * result screen never scans a full-resolution gallery image on the UI thread.
 */
object ImageMetricsExtractor {
    fun extract(source: Bitmap, maxSide: Int = 160): ImageMetrics {
        val scale = min(1f, maxSide.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val sample = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }

        var brightnessSum = 0f
        var shadowClipped = 0
        var highlightClipped = 0
        val gray = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = sample.getPixel(x, y)
                val value = (
                    0.2126f * ((color shr 16) and 0xff) +
                        0.7152f * ((color shr 8) and 0xff) +
                        0.0722f * (color and 0xff)
                    ) / 255f
                val index = y * width + x
                gray[index] = value
                brightnessSum += value
                if (value <= 0.05f) shadowClipped++
                if (value >= 0.95f) highlightClipped++
            }
        }

        var laplacianSum = 0f
        var laplacianSquaredSum = 0f
        var laplacianCount = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = gray[y * width + x]
                val laplacian = 4f * center -
                    gray[(y - 1) * width + x] -
                    gray[(y + 1) * width + x] -
                    gray[y * width + x - 1] -
                    gray[y * width + x + 1]
                laplacianSum += laplacian
                laplacianSquaredSum += laplacian * laplacian
                laplacianCount++
            }
        }
        val meanLaplacian = if (laplacianCount == 0) 0f else laplacianSum / laplacianCount
        val variance = if (laplacianCount == 0) {
            0f
        } else {
            (laplacianSquaredSum / laplacianCount - meanLaplacian * meanLaplacian).coerceAtLeast(0f)
        }

        if (sample !== source) sample.recycle()
        return ImageMetrics(
            tiltDeg = 0f,
            brightnessMean = brightnessSum / gray.size.coerceAtLeast(1),
            shadowClipRatio = shadowClipped.toFloat() / gray.size.coerceAtLeast(1),
            highlightClipRatio = highlightClipped.toFloat() / gray.size.coerceAtLeast(1),
            laplacianVariance = variance * 255f * 255f,
            leftMargin = 0f,
            rightMargin = 0f,
        )
    }
}
