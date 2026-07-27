package com.gamdo.app.ui.camera

import kotlin.math.abs

/**
 * Horizon indicator geometry (§2-5 / §3-2) — pure Kotlin, no `android.*`.
 *
 * Lives outside the overlay's `Canvas {}` lambda so the angle decision is
 * reachable from a JVM test. See `HorizonGeometryTest`.
 */

/** Degrees within which the horizon counts as reached — draws dead straight and sage. */
const val LEVEL_BAND_DEG = 1.5f

/**
 * Whether the roll is inside the level band, i.e. whether the indicator reads as
 * "reached" and turns sage.
 *
 * Symmetric in [rollDeg] by design — but that also makes it **blind to sign**, so
 * the colour cannot be used to verify [horizonIndicatorRotationDeg] on a device.
 * A non-finite reading is not level (it stays red rather than falsely reporting
 * success).
 */
fun isHorizonLevel(rollDeg: Float, levelBandDeg: Float = LEVEL_BAND_DEG): Boolean =
    rollDeg.isFinite() && abs(rollDeg) <= levelBandDeg

/**
 * Rotation for the horizon indicator, in Compose `rotate()` degrees
 * (positive = clockwise).
 *
 * The indicator lies **along** the true horizon as it appears in the frame, which
 * makes it the exact negative of `edit.levelingRotationDeg`, whose job is to
 * **cancel** that same angle. `camera/TiltSensor.kt`'s `TiltReading` KDoc is the
 * single source of truth for the gravity convention and derives `A == rollDeg`.
 *
 * Inside the level band the line snaps to true horizontal, so "reached" reads as a
 * straight line and not as a 1.4° tilt.
 */
fun horizonIndicatorRotationDeg(rollDeg: Float, levelBandDeg: Float = LEVEL_BAND_DEG): Float {
    if (!rollDeg.isFinite()) return 0f
    if (isHorizonLevel(rollDeg, levelBandDeg)) return 0f
    // NOT -rollDeg. Following the horizon and cancelling it are opposite
    // operations; `edit.levelingRotationDeg` owns the other sign. Making these two
    // agree is what breaks the correct one.
    return rollDeg
}
