package com.gamdo.app.edit

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Renders every filter over a real photograph and writes the results out.
 *
 * A filter is the one thing in this app that unit assertions cannot finish judging.
 * [FilterEngineTest] can prove a look is not the identity, that it differs from its
 * neighbours, and that the curve does not fold — but "does this look like the
 * photograph the author published" is a question for eyes. This harness is how eyes
 * get to see it without a device.
 *
 * ## Why a raw pixel format instead of reading the JPEG here
 *
 * Android unit tests run on a real JVM but compile against `android.jar`, whose
 * `java.*` subset has neither `java.awt` nor `javax.imageio`. So the codec lives
 * outside: the caller hands over a `.rgba` file (two big-endian ints, then packed
 * ARGB) and gets one back. That keeps the *only* thing worth testing — the actual
 * shipping [FilterEngine] — inside the module, instead of reimplementing it in the
 * host script and testing the reimplementation.
 *
 * Skipped unless `-Dgamdo.filterPreview.in` is set, so it never runs in a normal
 * build.
 */
class FilterPreviewHarness {

    @Test
    fun render_every_filter_over_a_real_photo() {
        val inPath = System.getProperty("gamdo.filterPreview.in")
        assumeTrue("set -Dgamdo.filterPreview.in to run", inPath != null)
        val source = File(inPath!!)
        assumeTrue("input not found: $inPath", source.isFile)
        val outDir = File(System.getProperty("gamdo.filterPreview.out") ?: source.parent)
        outDir.mkdirs()

        val (width, height, original) = readRgba(source)
        println("harness: ${source.name} ${width}x$height (${original.size} px)")
        val measure = FilterEngine.measure(original)
        println("harness: meanLuma=%.3f p99=%.3f".format(measure.meanLuma, measure.p99))
        println("harness: %-15s %8s %9s %14s".format("filter", "ms", "Δ levels", "exposure EV"))

        // Warm the JIT before timing anything. Without this the first filter in the
        // list absorbs compilation and reads 5-10x slower than the identical work
        // does later — enough to send someone optimising the wrong branch.
        repeat(3) {
            val warm = original.copyOf()
            FilterEngine.apply(warm, width, height, PhotoFilters.SOFT_FILM)
        }

        for (filter in PhotoFilters.ALL) {
            val pixels = original.copyOf()
            val started = System.nanoTime()
            FilterEngine.apply(pixels, width, height, filter)
            val ms = (System.nanoTime() - started) / 1_000_000
            val ev = FilterEngine.effectiveExposureEv(filter.tone.exposureEv, measure)
            println(
                "harness: %-15s %6d   %8.1f   %5.2f -> %.2f".format(
                    filter.id, ms, meanAbsDiff(original, pixels), filter.tone.exposureEv, ev,
                ),
            )
            writeRgba(File(outDir, "filter_${filter.id}.rgba"), width, height, pixels)
        }
        println("harness: wrote ${PhotoFilters.ALL.size} files to $outDir")
    }

    // Pixel bytes are BGRA, which is what System.Drawing's Format32bppArgb LockBits
    // buffer holds — so the host side is one Marshal.Copy instead of a per-byte
    // reorder loop, which in PowerShell is the difference between instant and
    // minutes for a 1MP frame.
    private fun readRgba(file: File): Triple<Int, Int, IntArray> =
        DataInputStream(file.inputStream().buffered(1 shl 20)).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val bytes = ByteArray(width * height * 4)
            input.readFully(bytes)
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                val o = i * 4
                pixels[i] = ((bytes[o + 3].toInt() and 0xff) shl 24) or
                    ((bytes[o + 2].toInt() and 0xff) shl 16) or
                    ((bytes[o + 1].toInt() and 0xff) shl 8) or
                    (bytes[o].toInt() and 0xff)
            }
            Triple(width, height, pixels)
        }

    private fun writeRgba(file: File, width: Int, height: Int, pixels: IntArray) {
        val bytes = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val o = i * 4
            bytes[o] = (p and 0xff).toByte()
            bytes[o + 1] = ((p shr 8) and 0xff).toByte()
            bytes[o + 2] = ((p shr 16) and 0xff).toByte()
            bytes[o + 3] = ((p ushr 24) and 0xff).toByte()
        }
        DataOutputStream(file.outputStream().buffered(1 shl 20)).use { out ->
            out.writeInt(width)
            out.writeInt(height)
            out.write(bytes)
        }
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) {
            sum += abs(((a[i] shr 16) and 0xff) - ((b[i] shr 16) and 0xff)).toLong()
            sum += abs(((a[i] shr 8) and 0xff) - ((b[i] shr 8) and 0xff)).toLong()
            sum += abs((a[i] and 0xff) - (b[i] and 0xff)).toLong()
        }
        return sum.toDouble() / (a.size * 3)
    }
}
