package com.gamdo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gamdo.app.data.local.entity.CachedReferences
import com.gamdo.app.data.local.entity.PendingRequests

/**
 * Network-domain DAOs — tables `cached_references` (reference-analysis cache;
 * the server is stateless) and `pending_requests` (offline retry queue).
 *
 * Created in wave 0 by guide-capture-agent so the DB registration could land in
 * one pass; **owned by reference-net-agent from here on**. Add further queries
 * inside these interfaces — `GamdoDatabase.kt` is frozen and needs no re-edit.
 */

/**
 * Note on [com.gamdo.app.data.local.entity.CachedReferences.paletteJson]: the
 * column name is frozen (R2-1) but the payload it actually stores is the
 * **full `colorTarget` response** from `POST /references/analyze`
 * (palette + colorTemperature + exposureBias, M7-04 기능명세서 §10.2) — not just
 * a palette array. See [ReferenceRepository] (`data/ReferenceRepository.kt`),
 * which is the only writer. This KDoc belongs on the entity property itself,
 * but the `data/local/entity` package (all of it) is outside reference-net-agent's
 * edit scope (편집 금지); it lives here, next to the DAO, until the lead applies
 * it there.
 */
@Dao
interface CachedReferencesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reference: CachedReferences)

    @Query("SELECT * FROM cached_references WHERE content_hash = :contentHash LIMIT 1")
    suspend fun get(contentHash: String): CachedReferences?

    @Query("SELECT * FROM cached_references ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<CachedReferences>

    @Query("SELECT COUNT(*) FROM cached_references")
    suspend fun count(): Int
}

@Dao
interface PendingRequestsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(request: PendingRequests)

    @Query("SELECT * FROM pending_requests ORDER BY created_at LIMIT :limit")
    suspend fun oldest(limit: Int = 20): List<PendingRequests>

    @Query("SELECT * FROM pending_requests WHERE id = :id")
    suspend fun get(id: String): PendingRequests?

    @Query("SELECT COUNT(*) FROM pending_requests")
    suspend fun count(): Int

    @Query(
        "UPDATE pending_requests SET retry_count = retry_count + 1, last_error = :error " +
            "WHERE id = :id",
    )
    suspend fun markRetried(id: String, error: String?)

    @Query("DELETE FROM pending_requests WHERE id = :id")
    suspend fun delete(id: String)
}
