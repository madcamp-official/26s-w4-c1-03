package com.gamdo.app.data

import android.util.Log
import com.gamdo.app.core.Ulid
import com.gamdo.app.data.local.SessionGuidesDao
import com.gamdo.app.data.local.SessionKpiDao
import com.gamdo.app.data.local.SessionsDao
import com.gamdo.app.data.local.entity.SessionGuides
import com.gamdo.app.data.local.entity.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §3-3 KPI write path: `sessions` and `session_guides`.
 *
 * `CaptureRepository`'s KDoc has referred to "their `GuideKpiRepository`" since
 * Day 1, but the class did not exist — both tables read 0 rows on the device while
 * the DAOs sat unused. This is that class.
 *
 * **Nothing here is allowed to fail a capture.** KPI rows are for 담당 B's metrics
 * script; losing one is a gap in a chart, whereas letting the exception out would
 * lose the user's photo. Every method swallows and logs. That is the opposite of
 * the rule for the capture itself, and the asymmetry is the point.
 *
 * A session spans one visit to the camera screen. `captures.session_id` links each
 * photo to it and `final_match_score` holds the score of the most recent shutter
 * in that session — the schema stores one number per session, so the last press is
 * what "final" means.
 */
class GuideKpiRepository(
    private val sessionsDao: SessionsDao,
    private val sessionKpiDao: SessionKpiDao,
    private val sessionGuidesDao: SessionGuidesDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Opens a session and returns its id, or null if the insert failed.
     *
     * A null return is a working camera with no KPI, which is the correct trade:
     * callers pass it straight into `CaptureSnapshot.sessionId`, which is nullable
     * because gallery imports have no session either.
     */
    suspend fun startSession(
        mode: String = MODE_STYLE,
        stylePresetId: String?,
        resolvedStyleJson: String = "{}",
    ): String? = withContext(Dispatchers.IO) {
        val id = "ses_" + Ulid.generate()
        runCatching {
            sessionsDao.insert(
                Sessions(
                    id = id,
                    mode = mode,
                    stylePresetId = stylePresetId,
                    resolvedStyleJson = resolvedStyleJson,
                    startedAt = now(),
                ),
            )
            id
        }.onFailure { Log.w(TAG, "session insert failed; capture continues without KPI", it) }
            .getOrNull()
    }

    /** §3-3: the weighted match score at the shutter. Later presses overwrite. */
    suspend fun recordFinalScore(sessionId: String, score: Float) = withContext(Dispatchers.IO) {
        runCatching { sessionKpiDao.recordFinalScore(sessionId, score.toDouble()) }
            .onFailure { Log.w(TAG, "final score update failed", it) }
        Unit
    }

    suspend fun endSession(sessionId: String) = withContext(Dispatchers.IO) {
        runCatching { sessionKpiDao.markEnded(sessionId, now()) }
            .onFailure { Log.w(TAG, "session end failed", it) }
        Unit
    }

    /**
     * §3-3: records that an overlay target became visible.
     *
     * Called on **transitions**, never per frame — at 12fps a per-frame row would
     * write ~700 rows a minute and answer no question the transitions do not.
     *
     * [message] is what 담당 B's script reads; it is a log field and never reaches
     * the UI, so it carries the internal target name rather than user-facing copy.
     * D2 forbids instruction text on screen, not in a KPI table.
     */
    suspend fun recordGuideShown(
        sessionId: String,
        guideType: String,
        message: String,
        deltaJson: String = "{}",
    ) = withContext(Dispatchers.IO) {
        runCatching {
            sessionGuidesDao.insert(
                SessionGuides(
                    id = "gid_" + Ulid.generate(),
                    sessionId = sessionId,
                    guideType = guideType,
                    message = message,
                    issuedAt = now(),
                    // NULL, not 0. DB 스키마 v2.0 §session_guides: "1=오차 해소,
                    // 0=미해소, NULL=측정불가". Nothing measures whether showing
                    // this guide actually resolved the framing error, so writing 0
                    // claims a measurement that was never taken — and it made the
                    // guide-effectiveness KPI structurally 0.0 for every row.
                    resolved = null,
                    deltaJson = deltaJson,
                ),
            )
        }.onFailure { Log.w(TAG, "guide event insert failed", it) }
        Unit
    }

    companion object {
        private const val TAG = "GuideKpiRepository"

        /** `sessions.mode` vocabulary from DB schema v2.0 §3.6. */
        const val MODE_STYLE = "style"
        const val MODE_REFERENCE = "reference"
        const val MODE_FREE = "free"

        /** `session_guides.guide_type` values this vertical emits. */
        const val GUIDE_TARGET_FRAME = "target_frame"
        const val GUIDE_HIDDEN = "target_hidden"
    }
}
