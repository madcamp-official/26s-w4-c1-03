package com.gamdo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gamdo.app.data.local.entity.CardSelections
import com.gamdo.app.data.local.entity.Consents
import com.gamdo.app.data.local.entity.Events
import com.gamdo.app.data.local.entity.Feedback
import com.gamdo.app.data.local.entity.StyleProfile

/**
 * Profile / onboarding-domain DAOs — tables `card_selections`, `style_profile`,
 * `feedback`, `consents`, `events`.
 *
 * Created in wave 0 by guide-capture-agent so the DB registration could land in
 * one pass; **owned by onboarding-polish-agent from here on**. Add further
 * queries inside these interfaces — `GamdoDatabase.kt` is frozen and needs no
 * re-edit.
 */

@Dao
interface CardSelectionsDao {
    @Query("DELETE FROM card_selections WHERE round = :round")
    suspend fun deleteRound(round: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(selection: CardSelections)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(selections: List<CardSelections>)

    @Query("SELECT * FROM card_selections WHERE round = :round ORDER BY created_at")
    suspend fun forRound(round: Int): List<CardSelections>

    @Query("SELECT * FROM card_selections ORDER BY created_at")
    suspend fun getAll(): List<CardSelections>

    @Query("SELECT COUNT(*) FROM card_selections")
    suspend fun count(): Int
}

/** Single-row table — `id` is always 1 (DB schema v2.0 §3.3). */
@Dao
interface StyleProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: StyleProfile)

    @Query("SELECT * FROM style_profile WHERE id = 1 LIMIT 1")
    suspend fun get(): StyleProfile?

    @Query("SELECT COUNT(*) FROM style_profile")
    suspend fun count(): Int
}

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: Feedback)

    @Query("SELECT * FROM feedback WHERE capture_id = :captureId ORDER BY created_at DESC")
    suspend fun forCapture(captureId: String): List<Feedback>

    @Query("SELECT * FROM feedback WHERE applied_to_profile = 0 ORDER BY created_at")
    suspend fun pendingProfileUpdates(): List<Feedback>

    @Query("UPDATE feedback SET applied_to_profile = 1 WHERE id = :id")
    suspend fun markAppliedToProfile(id: String)
}

@Dao
interface ConsentsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(consent: Consents)

    @Query("SELECT * FROM consents WHERE consent_type = :type ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(type: String): Consents?

    @Query("SELECT * FROM consents ORDER BY created_at")
    suspend fun getAll(): List<Consents>
}

@Dao
interface EventsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Events)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<Events>)

    @Query("SELECT * FROM events WHERE event_type = :type ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(type: String, limit: Int = 100): List<Events>

    @Query("SELECT COUNT(*) FROM events WHERE event_type = :type")
    suspend fun countOfType(type: String): Int
}
