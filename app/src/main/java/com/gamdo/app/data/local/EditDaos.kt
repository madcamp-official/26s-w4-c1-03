package com.gamdo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gamdo.app.data.local.entity.CaptureEditStack
import com.gamdo.app.data.local.entity.EditResultsLocal

/**
 * Edit-domain DAOs — tables `capture_edit_stack` and `edit_results_local`.
 *
 * Created in wave 0 by guide-capture-agent so the DB registration could land in
 * one pass; **owned by local-edit-agent from here on**. Add further queries
 * inside these interfaces — `GamdoDatabase.kt` is frozen and needs no re-edit.
 */

@Dao
interface CaptureEditStackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(step: CaptureEditStack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(steps: List<CaptureEditStack>)

    @Query(
        "SELECT * FROM capture_edit_stack WHERE capture_id = :captureId AND active = 1 " +
            "ORDER BY step_order",
    )
    suspend fun activeStack(captureId: String): List<CaptureEditStack>

    @Query("SELECT * FROM capture_edit_stack WHERE capture_id = :captureId ORDER BY step_order")
    suspend fun fullStack(captureId: String): List<CaptureEditStack>

    @Query("SELECT MAX(step_order) FROM capture_edit_stack WHERE capture_id = :captureId")
    suspend fun maxStepOrder(captureId: String): Int?

    /** Deactivates a step instead of deleting it — undo is `active = 0` (schema §3.9). */
    @Query("UPDATE capture_edit_stack SET active = 0 WHERE capture_id = :captureId AND step_order >= :fromOrder")
    suspend fun deactivateFrom(captureId: String, fromOrder: Int)

    /*
     * The two `captures` updates below live in this DAO on purpose. They belong to
     * the edit flow (§4-2 save, variant selection), but `GamdoDatabase.kt` is frozen
     * and `Daos.kt` is not this agent's file — putting them in an already-registered
     * interface avoids reopening either. Room does not require a DAO's queries to
     * target its "own" table.
     */

    /** §4-2 [저장]: records that the edited photo reached the user's gallery. */
    @Query("UPDATE captures SET saved_to_gallery = :saved WHERE id = :captureId")
    suspend fun setSavedToGallery(captureId: String, saved: Int)

    /** Remembers which result variant the user chose for this capture. */
    @Query("UPDATE captures SET selected_result_id = :resultId WHERE id = :captureId")
    suspend fun setSelectedResult(captureId: String, resultId: String?)
}

@Dao
interface EditResultsLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: EditResultsLocal)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<EditResultsLocal>)

    @Query("SELECT * FROM edit_results_local WHERE capture_id = :captureId ORDER BY rank")
    suspend fun forCapture(captureId: String): List<EditResultsLocal>

    @Query("SELECT * FROM edit_results_local WHERE id = :id")
    suspend fun get(id: String): EditResultsLocal?

    @Query("SELECT * FROM edit_results_local WHERE job_id = :jobId ORDER BY rank")
    suspend fun forJob(jobId: String): List<EditResultsLocal>
}
