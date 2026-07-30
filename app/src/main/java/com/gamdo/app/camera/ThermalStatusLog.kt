package com.gamdo.app.camera

import java.util.Locale

/**
 * The thermal probe's judgement, with no Android in it (W3-1, observation half).
 *
 * ## Why this is an instrument and not a policy
 *
 * §7-1 asks for an automatic downgrade when the device gets hot, and the plan names
 * 8fps as the target. That rule is measurably inert here: one analysed frame costs
 * ~188ms on SM-G970N (owner HUD, 2026-07-30: `분석 187.8ms · 5fps · drop 0%`), so
 * neither the 83ms of a 12fps ceiling nor the 125ms of an 8fps one is ever reached
 * and `AnalysisCadence` drops nothing at either setting. `drop 0%` is the direct
 * evidence. A `performanceTargetFps: 8` key that looked like this rule already
 * existed was deleted for the same reason in 7a9177c, and re-adding it under a new
 * name would restore the appearance without the function.
 *
 * Before any downgrade rule can be designed there is a prior question, and it is
 * not about frame rates: **does the thermal signal move at all on this device?**
 * `PowerManager.getCurrentThermalStatus()` reports whatever the vendor's thermal HAL
 * publishes, and on a good number of devices nothing is published — the status reads
 * `THERMAL_STATUS_NONE` from boot to shutdown no matter how hot the phone gets. A
 * downgrade built on a signal like that is inert in a second, quieter way than the
 * fps ceiling: it would look correct in review and never once run.
 *
 * So this file answers that question and does nothing else. No policy, no
 * hysteresis, no cadence change.
 *
 * ## The answer, on SM-G970N (owner, 2026-07-30)
 *
 * **The signal is dead here.** Ten minutes of continuous camera, sampled every ten
 * seconds:
 *
 * | t | AP | status |
 * |---|---|---|
 * | 60s | 50.2°C | 0 |
 * | 120s | 51.4°C | 0 |
 * | 240s | 45.8°C | 0 |
 * | 600s | 49.1°C | 0 |
 *
 * **0 of 60 samples read anything but `THERMAL_STATUS_NONE`, peaking at 51.9°C.**
 * `dumpsys thermalservice` says why: `ThermalHAL 2.0 connected: yes`, but both
 * `Temperature static thresholds from HAL` and `Current cooling devices from HAL`
 * are empty. The HAL is attached and publishes no threshold table, so the framework
 * has nothing to map a temperature onto a status with — Samsung throttles outside
 * AOSP's reporting path. This is a property of the device, not of the app, and no
 * amount of listener wiring changes it.
 *
 * `adb shell cmd thermalservice override-status 3` *does* take (verified 0 → 3 →
 * reset). That makes the listener path testable, which is worth having: it proves
 * the code runs. It does not make the trigger real, because it proves *that* the
 * code can run and never *when* it would.
 *
 * **Consequence for W3-1: an automatic thermal downgrade cannot be built on this
 * API for this device.** Any such rule would join `performanceTargetFps: 8` as
 * something parsed, reviewed, and never once executed. If a downgrade is wanted it
 * has to be either always-on or triggered by something the app can actually see
 * (frame cost itself is the obvious candidate — the app measures that directly).
 *
 * Two findings from the same run argue the problem may not need solving at all.
 * Temperature **rises and then stops**: 51.4°C at 120s, down to 45.8°C by 240s, and
 * settled at 48-49°C thereafter. The device is managing itself and merely declining
 * to say so through this API. And `remain_plan.md` already records a ten-minute soak
 * passing §7-1 at +0.4% frame time with AP stable at 49.6°C, which this run
 * reproduces. That question is with the owner.
 *
 * ## The trap this is shaped around
 *
 * If the signal is dead, a ten-minute run produces **zero** change lines. So does a
 * probe that failed to register, and so does a build where the call was dropped in a
 * merge. Three very different findings, one identical empty log.
 *
 * Hence [ThermalEvent.Attached] and [ThermalEvent.Detached] bracket the observation
 * and the closing line carries the window length and the change count. `changes=0
 * observed=600.0s` is then a measurement rather than an absence — the same
 * discipline `PreviewFpsAvailability` applies to the preview rate, and for the same
 * reason: a missing instrument and a reading of zero are opposite findings that must
 * not render alike.
 */

/**
 * Names for `android.os.PowerManager.THERMAL_STATUS_*`.
 *
 * A copy of the platform's table, kept here so the log formatting stays testable on
 * the JVM. Copies drift, so an unrecognised code renders as `UNKNOWN` rather than
 * being mapped to the nearest known name: drift then shows up as a visibly odd line
 * instead of hiding behind a plausible one.
 */
fun thermalLevelName(level: Int): String = when (level) {
    0 -> "NONE"
    1 -> "LIGHT"
    2 -> "MODERATE"
    3 -> "SEVERE"
    4 -> "CRITICAL"
    5 -> "EMERGENCY"
    6 -> "SHUTDOWN"
    else -> "UNKNOWN"
}

/** `NONE(0)` — the name and the raw code, because either alone is insufficient. */
private fun levelLabel(level: Int): String = "${thermalLevelName(level)}($level)"

/**
 * Formats seconds with a fixed decimal separator.
 *
 * [Locale.ROOT] rather than the default: these lines are grepped, and on a
 * comma-decimal locale `%.1f` would emit `65,4s` and quietly break any pattern the
 * owner writes against them.
 */
private fun seconds(millis: Double): String = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)

/** One line of `CameraThermal`. Emitted only when something actually happened. */
sealed interface ThermalEvent {

    /** Rendered line, without the tag. */
    fun format(): String

    /** The baseline, read once when the probe registers. Proves the probe is alive. */
    data class Attached(val level: Int) : ThermalEvent {
        override fun format(): String = "attach status=${levelLabel(level)}"
    }

    /**
     * The signal moved. [heldMs] is how long [from] lasted — measured from the
     * previous change, or from attach for the first one.
     */
    data class Changed(val from: Int, val to: Int, val heldMs: Double) : ThermalEvent {
        override fun format(): String =
            "change ${levelLabel(from)} -> ${levelLabel(to)} after ${seconds(heldMs)}"
    }

    /**
     * The observation closed. **This is the line that makes silence readable.**
     *
     * [maxLevel] is the hottest level reached rather than the last one: a device that
     * spikes to SEVERE and settles back to NONE has answered the question, and a
     * summary reporting only the final level would say it had not.
     */
    data class Detached(
        val level: Int,
        val maxLevel: Int,
        val observedMs: Double,
        val changes: Int,
    ) : ThermalEvent {
        override fun format(): String =
            "detach status=${levelLabel(level)} max=${levelLabel(maxLevel)} " +
                "observed=${seconds(observedMs)} changes=$changes"
    }
}

/**
 * Turns a stream of raw status readings into the few events worth logging.
 *
 * Emits on change only. The platform delivers a callback per thermal event and the
 * probe also seeds a baseline; logging every reading would bury the one transition
 * that matters under a run of identical lines, which is the failure mode that makes
 * a log worthless rather than merely noisy.
 *
 * Not thread-safe on its own — the platform callback arrives on a binder thread
 * while attach and detach run on the main thread. [ThermalStatusProbe] holds the
 * lock; see its `synchronized` block.
 */
class ThermalStatusTracker {

    private var attached = false
    private var level = 0
    private var maxLevel = 0
    private var attachedNs = 0L
    private var lastChangeNs = 0L
    private var changes = 0

    /**
     * Opens an observation window at the current [level].
     *
     * A second attach without an intervening detach keeps the **first** window rather
     * than restarting it. Restarting would silently shorten the interval the owner is
     * measuring, and the probe's single-instance rule means a second attach is a bug
     * worth leaving visible in the numbers rather than papering over.
     */
    fun onAttached(level: Int, nowNs: Long): ThermalEvent.Attached {
        if (attached) return ThermalEvent.Attached(this.level)
        attached = true
        this.level = level
        maxLevel = level
        attachedNs = nowNs
        lastChangeNs = nowNs
        changes = 0
        return ThermalEvent.Attached(level)
    }

    /**
     * One status reading. Null unless it differs from the last one.
     *
     * Readings outside an open window are dropped rather than treated as a baseline.
     * That covers both ends: a platform that delivers the current status on
     * registration (the probe seeds the baseline first, so such a callback is a
     * duplicate), and a callback already in flight when the listener is removed —
     * `removeThermalStatusListener` makes no promise about those, and letting one
     * resurrect the tracker would produce a second window overlapping the first.
     */
    fun onStatus(level: Int, nowNs: Long): ThermalEvent.Changed? {
        if (!attached) return null
        if (level == this.level) return null
        val event = ThermalEvent.Changed(
            from = this.level,
            to = level,
            heldMs = (nowNs - lastChangeNs) / 1_000_000.0,
        )
        this.level = level
        maxLevel = maxOf(maxLevel, level)
        lastChangeNs = nowNs
        changes += 1
        return event
    }

    /**
     * Closes the window and summarises it. Null if none is open.
     *
     * Idempotent, because the screen's `onDispose` and the handle's own `close` can
     * both fire for one attach; a second summary would claim a zero-length
     * observation that never happened.
     */
    fun onDetached(nowNs: Long): ThermalEvent.Detached? {
        if (!attached) return null
        attached = false
        return ThermalEvent.Detached(
            level = level,
            maxLevel = maxLevel,
            observedMs = (nowNs - attachedNs) / 1_000_000.0,
            changes = changes,
        )
    }
}

// A HUD reduction of this stream was written and then removed (2026-07-30): the
// camera screen moved to another vertical mid-task, and a `ThermalReading` with no
// composable left to render it would be exactly the kind of unreachable code that
// reads as a shipped feature. logcat is the whole surface; see [ThermalStatusProbe].
