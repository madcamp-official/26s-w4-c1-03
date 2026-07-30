package com.gamdo.app.camera

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.gamdo.app.BuildConfig

/** Everything this file emits, so one grep reads the whole observation. */
const val THERMAL_TAG = "CameraThermal"

/**
 * Whether this build observes the thermal signal at all.
 *
 * Debug only, matching [MEASURE_PREVIEW_FPS]. This is an instrument for answering a
 * design question (see [ThermalStatusTracker]), not a product behaviour, and a
 * release build has nothing to do with the answer.
 */
val OBSERVE_THERMAL_STATUS: Boolean = BuildConfig.DEBUG

/**
 * Reads `PowerManager`'s thermal status for the length of one camera session and
 * logs the few moments it changes.
 *
 * ## What it is for
 *
 * W3-1's downgrade rule needs to know whether this signal moves on the demo device
 * before anything can be built on it — see [ThermalStatusTracker] for why, and for
 * why an empty log would otherwise be unreadable. Ten minutes of shooting with
 * `adb logcat -s CameraThermal:D` running answers it.
 *
 * **On SM-G970N the answer is already in: it does not move.** 0 of 60 samples above
 * `NONE` at up to 51.9°C, because the vendor HAL publishes no threshold table; the
 * measurement and its consequences are recorded on [ThermalStatusTracker]. This
 * stays wired anyway, for two reasons. It is the evidence trail behind "why is there
 * no automatic thermal downgrade", which is otherwise an absence nobody can
 * interrogate. And it re-answers the question in one run on any other device, which
 * matters because the finding above is a property of this handset and not of the
 * API — the rehearsal machine or a replacement demo phone could behave differently.
 *
 * ## Registration order
 *
 * The baseline is read with `getCurrentThermalStatus()` and handed to the tracker
 * **before** the listener is registered. Some implementations deliver the current
 * status on registration; seeding first means such a callback is a duplicate the
 * tracker drops, rather than a change event with no baseline to compare against.
 *
 * ## Removing the listener
 *
 * A listener registered on `PowerManager` outlives the screen that registered it —
 * it is held by a system service, so it keeps this object, its lambda and whatever
 * that lambda captured alive for the life of the process. Registering once per
 * camera visit and never removing would accumulate one per visit. The same
 * asymmetry [AnalysisPauseGate] documents applies: failing to remove is expensive
 * and removing early costs a few unlogged transitions, so every ambiguity resolves
 * towards removal.
 *
 *  1. [close] is called from `CameraController.unbind`, which the camera screen's
 *     `onDispose` runs unconditionally.
 *  2. [close] is idempotent, so a second teardown path is a no-op rather than a
 *     second summary line claiming an observation that never happened.
 *  3. **Starting a new probe closes the live one first.** `CameraController.bind` is
 *     called from an `AndroidView` factory and can run more than once per `unbind`;
 *     this is what makes accumulation impossible rather than merely unlikely.
 *  4. Events are dropped after [close]. `removeThermalStatusListener` makes no
 *     promise about a callback already in flight on a binder thread, and one landing
 *     late would otherwise reopen a closed observation.
 */
class ThermalStatusProbe private constructor(
    private val powerManager: PowerManager,
    private val onEvent: (ThermalEvent) -> Unit,
) {

    private val tracker = ThermalStatusTracker()
    private val lock = Any()
    private var closed = false

    /**
     * Delivered on a binder thread, unlike [start] and [close] which run on the main
     * thread — hence the lock. Cheap enough to hold it: one comparison, and a log
     * line only when the answer changes.
     */
    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        val event = synchronized(lock) {
            if (closed) null else tracker.onStatus(status, System.nanoTime())
        }
        event?.let(onEvent)
    }

    private fun start() {
        val baseline = synchronized(lock) {
            tracker.onAttached(powerManager.currentThermalStatus, System.nanoTime())
        }
        onEvent(baseline)
        powerManager.addThermalStatusListener({ command -> command.run() }, listener)
    }

    /** Closes the observation and removes the listener. Safe to call repeatedly. */
    fun close() {
        val event = synchronized(lock) {
            if (closed) return
            closed = true
            tracker.onDetached(System.nanoTime())
        }
        runCatching { powerManager.removeThermalStatusListener(listener) }
            .onFailure { Log.w(THERMAL_TAG, "listener removal rejected", it) }
        event?.let(onEvent)
    }

    companion object {

        /**
         * The probe currently registered, if any.
         *
         * Process-scoped rather than owned by the caller because guarantee 3 has to
         * hold across two different `CameraController` instances — the screen can be
         * rebuilt while the old controller's `unbind` has not run yet.
         */
        @Volatile
        private var live: ThermalStatusProbe? = null

        /**
         * Begins an observation, replacing any that is still running.
         *
         * Returns null when the build does not observe, or when the platform has no
         * `PowerManager` to ask — the caller has nothing to do in either case, which
         * is why this is null rather than a throw.
         */
        @Synchronized
        fun start(
            context: Context,
            onEvent: (ThermalEvent) -> Unit = { Log.d(THERMAL_TAG, it.format()) },
        ): ThermalStatusProbe? {
            if (!OBSERVE_THERMAL_STATUS) return null
            live?.close()
            live = null

            val powerManager = context.applicationContext
                .getSystemService(PowerManager::class.java)
                ?: run {
                    Log.w(THERMAL_TAG, "no PowerManager; thermal status not observed")
                    return null
                }

            val probe = ThermalStatusProbe(powerManager, onEvent)
            return runCatching {
                probe.start()
                live = probe
                probe
            }.getOrElse {
                Log.w(THERMAL_TAG, "thermal listener registration failed", it)
                probe.close()
                null
            }
        }

        /** Closes [probe] and forgets it if it is the live one. */
        @Synchronized
        fun stop(probe: ThermalStatusProbe?) {
            probe?.close()
            if (live === probe) live = null
        }
    }
}
