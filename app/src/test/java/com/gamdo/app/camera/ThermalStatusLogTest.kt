package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The thermal observation probe's judgement, tested without a device.
 *
 * W3-1's downgrade rule cannot be designed until one question is answered: **does
 * `PowerManager.getCurrentThermalStatus()` move at all on SM-G970N?** Many devices
 * never wire the HAL's throttling events and return `THERMAL_STATUS_NONE` for the
 * life of the process, which would make any downgrade rule built on it as inert as
 * the `performanceTargetFps: 8` key deleted in 7a9177c.
 *
 * So this class is an instrument, not a policy, and what these tests protect is the
 * instrument's honesty: a ten-minute run that logs nothing must be distinguishable
 * from a probe that never attached.
 */
class ThermalStatusLogTest {

    /** Seconds to nanos, so the measured figures below read at their natural scale. */
    private fun s(value: Double): Long = (value * 1_000_000_000.0).toLong()

    // ---------------------------------------------------------------- naming

    @Test
    fun `each platform status has a name so the log is readable without a lookup table`() {
        assertEquals("NONE", thermalLevelName(0))
        assertEquals("LIGHT", thermalLevelName(1))
        assertEquals("MODERATE", thermalLevelName(2))
        assertEquals("SEVERE", thermalLevelName(3))
        assertEquals("CRITICAL", thermalLevelName(4))
        assertEquals("EMERGENCY", thermalLevelName(5))
        assertEquals("SHUTDOWN", thermalLevelName(6))
    }

    @Test
    fun `an unrecognised status is named as unknown rather than silently mapped`() {
        // The name table is a copy of `PowerManager.THERMAL_STATUS_*`, and a copy can
        // drift from its original. Rendering an unknown code as UNKNOWN makes drift
        // show up as a visibly odd log line; picking the nearest known name would
        // hide it behind a plausible one.
        assertEquals("UNKNOWN", thermalLevelName(7))
        assertEquals("UNKNOWN", thermalLevelName(-1))
    }

    // ------------------------------------------------------------- attaching

    @Test
    fun `attaching emits the baseline so the log proves the probe is alive`() {
        val tracker = ThermalStatusTracker()

        val event = tracker.onAttached(level = 0, nowNs = s(0.0))

        assertEquals(ThermalEvent.Attached(level = 0), event)
    }

    @Test
    fun `a status arriving before attach is ignored instead of crashing`() {
        // Ordering insurance. The probe seeds the baseline before registering the
        // listener precisely so this cannot happen, but a platform that delivers the
        // current status on registration would otherwise produce a change event with
        // nothing to compare against.
        val tracker = ThermalStatusTracker()

        assertNull(tracker.onStatus(level = 3, nowNs = s(1.0)))
    }

    @Test
    fun `attaching twice does not restart the observation window`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))

        tracker.onAttached(level = 2, nowNs = s(100.0))
        val detached = tracker.onDetached(nowNs = s(600.0))

        assertEquals(
            "a second attach must not shorten the window the first one opened",
            600.0,
            (detached as ThermalEvent.Detached).observedMs / 1000.0,
            0.001,
        )
    }

    // -------------------------------------------------------------- changing

    @Test
    fun `the same status repeated emits nothing`() {
        // The coordinator's constraint: a per-frame or per-callback line would bury
        // the one transition that matters under hundreds of identical ones.
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))

        repeat(500) { i -> assertNull(tracker.onStatus(level = 0, nowNs = s(i.toDouble()))) }
    }

    @Test
    fun `a real change emits once and reports how long the old level held`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))

        val event = tracker.onStatus(level = 2, nowNs = s(65.4))

        assertEquals(ThermalEvent.Changed(from = 0, to = 2, heldMs = 65_400.0), event)
        // And the level is now the new baseline, not the old one.
        assertNull(tracker.onStatus(level = 2, nowNs = s(70.0)))
    }

    @Test
    fun `the hold time is measured from the last change, not from attach`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))
        tracker.onStatus(level = 1, nowNs = s(100.0))

        val second = tracker.onStatus(level = 2, nowNs = s(130.0))

        assertEquals(
            "LIGHT held for 30s, not for the 130s since the probe attached",
            30_000.0,
            (second as ThermalEvent.Changed).heldMs,
            0.001,
        )
    }

    @Test
    fun `returning to a previous level is another change, not a cancellation`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))
        tracker.onStatus(level = 2, nowNs = s(100.0))

        val back = tracker.onStatus(level = 0, nowNs = s(200.0))

        assertEquals(ThermalEvent.Changed(from = 2, to = 0, heldMs = 100_000.0), back)
        assertEquals(2, (tracker.onDetached(s(300.0)) as ThermalEvent.Detached).changes)
    }

    // ------------------------------------------------------------- detaching

    /**
     * **The test the whole class exists for.**
     *
     * A ten-minute run on a device whose thermal HAL is not wired produces zero
     * change lines. So does a probe that failed to attach, and so does a build where
     * someone deleted the call. The detach line is what tells those three apart: it
     * states the window that was observed and the number of changes seen in it, so
     * `changes=0 observed=600.0s` is a measurement and not an absence.
     */
    @Test
    fun `detaching reports the observed window and the change count`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))
        repeat(600) { i -> tracker.onStatus(level = 0, nowNs = s(i.toDouble())) }

        val event = tracker.onDetached(nowNs = s(600.0))

        assertEquals(
            ThermalEvent.Detached(level = 0, maxLevel = 0, observedMs = 600_000.0, changes = 0),
            event,
        )
    }

    @Test
    fun `detaching records the hottest level reached and not merely the last one`() {
        // A device that spikes to SEVERE and settles back to NONE has answered the
        // question; a summary that reported only the final level would say it had not.
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))
        tracker.onStatus(level = 3, nowNs = s(100.0))
        tracker.onStatus(level = 0, nowNs = s(200.0))

        val event = tracker.onDetached(nowNs = s(300.0)) as ThermalEvent.Detached

        assertEquals(0, event.level)
        assertEquals(3, event.maxLevel)
        assertEquals(2, event.changes)
    }

    @Test
    fun `the baseline level counts towards the hottest level seen`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 4, nowNs = s(0.0))

        val event = tracker.onDetached(nowNs = s(10.0)) as ThermalEvent.Detached

        assertEquals("attaching while already CRITICAL is the observation", 4, event.maxLevel)
    }

    @Test
    fun `detaching a probe that never attached reports nothing`() {
        assertNull(ThermalStatusTracker().onDetached(nowNs = s(1.0)))
    }

    @Test
    fun `detaching twice reports once`() {
        // The screen's onDispose and any future teardown path can both fire for one
        // attach; the second must be a no-op rather than a second summary line
        // claiming a zero-length observation.
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))

        assertEquals(
            ThermalEvent.Detached(level = 0, maxLevel = 0, observedMs = 5_000.0, changes = 0),
            tracker.onDetached(nowNs = s(5.0)),
        )
        assertNull(tracker.onDetached(nowNs = s(6.0)))
    }

    @Test
    fun `a status arriving after detach is ignored`() {
        // `removeThermalStatusListener` does not promise that a callback already in
        // flight will not land. Without this the tracker would resurrect itself and
        // the next detach would report a window that overlaps the previous one.
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))
        tracker.onDetached(nowNs = s(5.0))

        assertNull(tracker.onStatus(level = 3, nowNs = s(6.0)))
        assertNull(tracker.onDetached(nowNs = s(7.0)))
    }

    @Test
    fun `a fresh attach after detach starts a clean window`() {
        val tracker = ThermalStatusTracker()
        tracker.onAttached(level = 0, nowNs = s(0.0))
        tracker.onStatus(level = 2, nowNs = s(50.0))
        tracker.onDetached(nowNs = s(100.0))

        tracker.onAttached(level = 0, nowNs = s(1000.0))
        val second = tracker.onDetached(nowNs = s(1060.0)) as ThermalEvent.Detached

        assertEquals("the second visit is its own observation", 0, second.changes)
        assertEquals(60_000.0, second.observedMs, 0.001)
        assertEquals("and does not inherit the first visit's peak", 0, second.maxLevel)
    }

    // ------------------------------------------------------------ formatting

    /**
     * The log lines are pinned as strings because they are the deliverable: the
     * owner reads them off `adb logcat -s CameraThermal:D` and nothing else in the
     * app reports this.
     */
    @Test
    fun `the attach line names the baseline`() {
        assertEquals("attach status=NONE(0)", ThermalEvent.Attached(level = 0).format())
    }

    @Test
    fun `the change line reads as a sentence with both levels and the hold time`() {
        assertEquals(
            "change NONE(0) -> MODERATE(2) after 65.4s",
            ThermalEvent.Changed(from = 0, to = 2, heldMs = 65_400.0).format(),
        )
    }

    @Test
    fun `the detach line states the window so zero changes is a finding`() {
        assertEquals(
            "detach status=NONE(0) max=NONE(0) observed=600.0s changes=0",
            ThermalEvent.Detached(
                level = 0,
                maxLevel = 0,
                observedMs = 600_000.0,
                changes = 0,
            ).format(),
        )
    }

    @Test
    fun `an unknown code keeps its number in the line`() {
        // The number is what makes an unrecognised status actionable; a bare
        // "UNKNOWN" would say something moved without saying to what.
        assertEquals(
            "change NONE(0) -> UNKNOWN(9) after 1.0s",
            ThermalEvent.Changed(from = 0, to = 9, heldMs = 1_000.0).format(),
        )
    }

}
