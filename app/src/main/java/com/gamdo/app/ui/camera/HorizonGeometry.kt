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
 * Above this absolute pitch the roll carries no information about the scene, so
 * neither the indicator nor the recorded tilt should claim it does.
 *
 * Roll is `atan2(gx, gy)`. As the phone approaches face-up or face-down, gravity
 * moves onto the z axis, gx and gy both go to noise, and the result swings freely
 * — a phone lying flat on a desk reports roll ≈ 93°, which is not a statement
 * about the horizon at all.
 *
 * `CameraOverlay`'s `HorizonGate` already hides the indicator on this rule (with
 * hysteresis around the boundary, which a display needs and a one-shot record does
 * not). §3-3 has to apply the same rule when it writes `conditions_json`: measured
 * on SM-G970N, a photo taken with the phone flat recorded `tiltDeg: 93.4`, and
 * §4-3 duly showed "기울기를 확인해보세요" for a photo that was not tilted.
 */
const val MAX_MEANINGFUL_PITCH_DEG = 65f

/** Whether [pitchDeg] is a shooting posture, i.e. whether roll means anything. */
fun isRollMeaningful(pitchDeg: Float, maxPitchDeg: Float = MAX_MEANINGFUL_PITCH_DEG): Boolean =
    pitchDeg.isFinite() && abs(pitchDeg) < maxPitchDeg

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
