package com.gamdo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gamdo.app.data.local.entity.SessionGuides

/**
 * Guide / capture-KPI DAOs — tables `sessions` (KPI write path) and
 * `session_guides` (P1 §3-3). Owner: guide-capture-agent.
 *
 * `SessionsDao` in [Daos.kt] already owns the sessions insert/get pair, so this
 * file deliberately adds only the KPI update path under a separate DAO; the two
 * never overlap and neither file needs to be re-edited when the other grows.
 */

@Dao
interface SessionGuidesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(guide: SessionGuides)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(guides: List<SessionGuides>)

    @Query("SELECT * FROM session_guides WHERE session_id = :sessionId ORDER BY issued_at")
    suspend fun forSession(sessionId: String): List<SessionGuides>

    @Query("SELECT COUNT(*) FROM session_guides WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int
}

/**
 * KPI-only view of `sessions`: records the shutter-moment match score (§3-3).
 * matchScore is log-only and must never reach the product UI.
 */
@Dao
interface SessionKpiDao {
    @Query("UPDATE sessions SET final_match_score = :score WHERE id = :sessionId")
    suspend fun recordFinalScore(sessionId: String, score: Double)

    @Query("UPDATE sessions SET ended_at = :endedAt WHERE id = :sessionId")
    suspend fun markEnded(sessionId: String, endedAt: Long)

    @Query("SELECT final_match_score FROM sessions WHERE id = :sessionId")
    suspend fun finalScore(sessionId: String): Double?

    @Query("SELECT COUNT(*) FROM sessions WHERE final_match_score IS NOT NULL")
    suspend fun scoredSessionCount(): Int
}
