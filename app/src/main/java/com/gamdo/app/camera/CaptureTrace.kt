package com.gamdo.app.camera

/**
 * One stage of the shutter→saved path, in the order the path runs them.
 *
 * The split points were chosen so that a slow capture names its own culprit
 * without a second measurement round. In particular [CAMERA_X] and [DECODE] are
 * separate because logcat cannot separate them: `TakePictureManager: No new
 * request` fires when CameraX is done with the *request*, and everything after it
 * — the full-resolution JPEG decode, the viewport crop, the rotation, the front
 * mirror — is this app's own work happening inside CameraX's callback, where it
 * looks like part of CameraX.
 */
enum class CapturePhase(val label: String) {
    /** Shutter tap → `onCaptureSuccess`. CameraX's pipeline, and nothing of ours. */
    CAMERA_X("cameraX"),

    /** JPEG → an upright, mirrored `Bitmap`. Our decode + up to three full-size copies. */
    DECODE("decode"),

    /** Aspect crop (D9's 4:5 / 1:1) and the 256px bottom-bar thumbnail. */
    CROP("crop"),

    /** Bitmap → JPEG bytes, at [com.gamdo.app.data.CaptureRepository.JPEG_QUALITY]. */
    ENCODE("encode"),

    /** Bytes → the app's private `captures/` file. This is "the photo is safe". */
    APP_FILE("appFile"),

    /** The `captures` row. After this the photo exists to the album and the editor. */
    ROW("row"),

    /**
     * MediaStore export.
     *
     * Present in a trace only if the export ran inline. It normally does not — see
     * `CaptureRepository.saveCameraCapture` — and its absence from the line is the
     * point: it is logged separately, with its own duration, because it is no
     * longer something the user waits for.
     */
    GALLERY("gallery"),
}

/**
 * Per-stage timing for one shutter press (`CaptureLatency` in logcat).
 *
 * Deliberately Android-free so the arithmetic — which is the part that can be
 * wrong — is testable. `System.nanoTime()` is the default clock but every `mark`
 * accepts one, which is what lets `CaptureTraceTest` drive it.
 *
 * **Each printed number is one stage, not the elapsed total.** A cumulative
 * breakdown makes every stage after a slow one look slow too, and the fix lands
 * in the wrong place; this project has already paid for that mistake twice
 * (cold-start blamed on CameraX init, and an album grid misread).
 *
 * Marks are recorded from three threads in sequence — the shutter coroutine on
 * main, CameraX's callback on `Dispatchers.Default`, the repository on
 * `Dispatchers.IO` — each strictly after the last, but through suspension points
 * rather than a lock. Guarding the list is cheaper than reasoning about that
 * every time a stage moves.
 */
class CaptureTrace(private val startNs: Long = System.nanoTime()) {

    private val lock = Any()
    private val marks = ArrayList<Pair<CapturePhase, Long>>(CapturePhase.entries.size)

    /**
     * Records that [phase] finished at [atNs].
     *
     * A phase already marked is **ignored**, not overwritten: a duplicated
     * callback or a copy-pasted call site would otherwise move the boundary
     * between two stages and make both numbers wrong, silently.
     */
    fun mark(phase: CapturePhase, atNs: Long = System.nanoTime()) {
        synchronized(lock) {
            if (marks.any { it.first == phase }) return
            marks.add(phase to atNs)
        }
    }

    /** Shutter to the last recorded mark, or null when nothing was marked. */
    fun totalMs(): Double? = synchronized(lock) {
        val last = marks.lastOrNull() ?: return null
        (last.second - startNs) / 1_000_000.0
    }

    /**
     * A real line, SM-G970N, 2026-07-29:
     *
     * `cameraX=1075 decode=534 crop=31 encode=165 appFile=2 row=4 total=1814ms`
     *
     * Unrun stages are omitted rather than printed as 0 — a `gallery=0` would read
     * as "the export was free" when what actually happened is that it moved off
     * this path. Out-of-order marks print negative rather than clamping to 0, for
     * the same reason: a wrong number that looks plausible is worse than one that
     * does not.
     */
    fun format(): String = synchronized(lock) {
        if (marks.isEmpty()) return "no phases recorded"
        val out = StringBuilder()
        var previousNs = startNs
        for ((phase, atNs) in marks) {
            out.append(phase.label).append('=').append((atNs - previousNs) / 1_000_000L).append(' ')
            previousNs = atNs
        }
        out.append("total=").append((previousNs - startNs) / 1_000_000L).append("ms")
        return out.toString()
    }
}
