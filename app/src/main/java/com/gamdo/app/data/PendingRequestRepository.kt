package com.gamdo.app.data

import com.gamdo.app.core.Ulid
import com.gamdo.app.data.local.PendingRequestsDao
import com.gamdo.app.data.local.entity.PendingRequests
import com.gamdo.app.data.network.GamdoApiException

/** Outcome of one [PendingRequestRepository.retrySweepOnce] pass, for logging/tests. */
data class RetrySweepResult(val succeeded: Int, val failed: Int) {
    val attempted: Int get() = succeeded + failed
}

/**
 * §6-1 offline-retry queue: "실패 요청은 `pending_requests`에 저장 후 재연결 시
 * 1회 재시도". This repository owns exactly two moments:
 *
 * 1. [enqueueIfRetryable] — a network caller (`ReferenceRepository` today,
 *    `EditJobRepository` from wave 3) hit a [GamdoApiException]. If the shared
 *    `{code, message, retryable}` envelope says `retryable = true`, the
 *    intended call is recorded here instead of just failing in place. A
 *    `retryable = false` envelope (a validation error, for instance) is *not*
 *    queued — that must surface to the user immediately, not vanish silently.
 * 2. [retrySweepOnce] — call this when connectivity comes back. Every
 *    currently queued row gets **exactly one** replay attempt via [replay] in
 *    this pass — this function itself never loops a row until it succeeds.
 *    "1회 재시도" is enforced *per reconnect event*: a row that fails again
 *    keeps its place in the queue (with `retry_count` incremented via
 *    [PendingRequestsDao.markRetried]) for whenever the *next* reconnect event
 *    calls [retrySweepOnce] again — there is no "give up forever" flag in the
 *    frozen schema (R2-1), so this deliberately does not invent one. Calling
 *    this function is the caller's job (e.g. from a connectivity callback);
 *    this class has no opinion on *when* that happens.
 *
 * `pending_requests` columns (`method`, `endpoint`, `body_json`, `file_path`)
 * are frozen (R2-1) — [replay] is handed the raw row and decides how to turn
 * those fields back into a real call; this class has no HTTP knowledge at all.
 */
@Deprecated(
    message = "이 큐가 담을 실패 요청이 없어졌다. remain_plan O-1이 §5-1·§5-3을 컷해서 앱에서 " +
        "나가는 호출은 GET /presets 하나뿐이고, 그건 assets/presets.json 폴백이 이미 처리한다 " +
        "— 재연결 후 재생할 것이 없다. §5가 부활하면 이 폐기를 걷어내면 된다. remain_plan §1 참조.",
    level = DeprecationLevel.ERROR,
)
class PendingRequestRepository(
    private val pendingRequestsDao: PendingRequestsDao,
) {

    /**
     * Queues [method]/[endpoint]/[bodyJson] (and optionally [filePath], for a
     * request that carried an upload) for a later retry — but only when
     * [exception]'s envelope says the failure is retryable. Returns the new
     * row's id, or `null` if nothing was queued.
     */
    suspend fun enqueueIfRetryable(
        exception: GamdoApiException,
        method: String,
        endpoint: String,
        bodyJson: String,
        filePath: String? = null,
    ): String? {
        if (!exception.envelope.retryable) return null
        val id = "pnd_" + Ulid.generate()
        pendingRequestsDao.insert(
            PendingRequests(
                id = id,
                method = method,
                endpoint = endpoint,
                bodyJson = bodyJson,
                filePath = filePath,
                retryCount = 0,
                lastError = exception.envelope.message,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    /**
     * One retry pass over every queued request, oldest first (capped at
     * [SWEEP_LIMIT] so one reconnect event can't spawn an unbounded burst).
     * [replay] performs the real HTTP call for a row and reports the outcome;
     * a row that succeeds is deleted, a row that fails (whether [replay]
     * returns a failed [Result] or throws) is kept and marked via
     * [PendingRequestsDao.markRetried]. [replay] is invoked at most once per
     * row in this call, which is what makes this "1회 재시도" rather than a
     * retry-until-success loop.
     */
    suspend fun retrySweepOnce(replay: suspend (PendingRequests) -> Result<Unit>): RetrySweepResult {
        val queued = pendingRequestsDao.oldest(limit = SWEEP_LIMIT)
        var succeeded = 0
        var failed = 0
        for (request in queued) {
            val outcome = runCatching { replay(request) }.getOrElse { Result.failure(it) }
            if (outcome.isSuccess) {
                pendingRequestsDao.delete(request.id)
                succeeded++
            } else {
                val message = outcome.exceptionOrNull()?.message ?: "retry failed"
                pendingRequestsDao.markRetried(request.id, message)
                failed++
            }
        }
        return RetrySweepResult(succeeded = succeeded, failed = failed)
    }

    suspend fun pendingCount(): Int = pendingRequestsDao.count()

    private companion object {
        const val SWEEP_LIMIT = 50
    }
}
