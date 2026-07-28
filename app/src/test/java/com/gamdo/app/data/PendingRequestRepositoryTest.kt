// 대상이 remain_plan O-1로 컷돼 @Deprecated(ERROR)가 붙었다. 테스트는 **의도적으로 남긴다** —
// "1회 재시도" 의미론은 재구현하기 쉽게 틀리는 종류라 기록으로 남길 값이 있다.
// 폐기를 걷어내고 경로를 되살리는 사람에게 이 테스트가 안전망이 된다. 이 suppress를 지우려면
// 테스트도 함께 지워야 하고, 그건 컷을 되돌리는 것이 아니라 되돌릴 수단을 버리는 것이다.
@file:Suppress("DEPRECATION_ERROR")

package com.gamdo.app.data

import com.gamdo.app.data.local.PendingRequestsDao
import com.gamdo.app.data.local.entity.PendingRequests
import com.gamdo.app.data.network.ApiErrorEnvelope
import com.gamdo.app.data.network.GamdoApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory [PendingRequestsDao] fake that preserves insertion order like `ORDER BY created_at`. */
private class FakePendingRequestsDao : PendingRequestsDao {
    val rows = mutableMapOf<String, PendingRequests>()
    private val insertOrder = mutableListOf<String>()

    override suspend fun insert(request: PendingRequests) {
        if (request.id !in rows) insertOrder.add(request.id)
        rows[request.id] = request
    }

    override suspend fun oldest(limit: Int): List<PendingRequests> =
        insertOrder.mapNotNull { rows[it] }.take(limit)

    override suspend fun get(id: String): PendingRequests? = rows[id]

    override suspend fun count(): Int = rows.size

    override suspend fun markRetried(id: String, error: String?) {
        rows[id]?.let { rows[id] = it.copy(retryCount = it.retryCount + 1, lastError = error) }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        insertOrder.remove(id)
    }
}

private fun envelope(retryable: Boolean, message: String = "문제가 발생했어요") =
    GamdoApiException(
        envelope = ApiErrorEnvelope(code = "server_error", message = message, retryable = retryable),
        httpCode = 503,
        cause = RuntimeException("boom"),
    )

/**
 * Covers §6-1: "실패 요청은 pending_requests에 저장 후 재연결 시 1회 재시도".
 * [PendingRequestRepository] is Context-free (only needs [PendingRequestsDao]),
 * so it is constructed directly here with an in-memory fake.
 */
class PendingRequestRepositoryTest {

    private lateinit var dao: FakePendingRequestsDao
    private lateinit var repository: PendingRequestRepository

    @Before
    fun setUp() {
        dao = FakePendingRequestsDao()
        repository = PendingRequestRepository(dao)
    }

    @Test
    fun `a retryable failure is queued with the request fields intact`() = runBlocking {
        val id = repository.enqueueIfRetryable(
            exception = envelope(retryable = true, message = "잠시 후 다시 시도해주세요"),
            method = "POST",
            endpoint = "references/analyze",
            bodyJson = """{"contentHash":"abc123"}""",
            filePath = "/data/cache/abc123.jpg",
        )

        assertNotNull(id)
        assertEquals(1, dao.count())
        val row = dao.rows.getValue(id!!)
        assertEquals("POST", row.method)
        assertEquals("references/analyze", row.endpoint)
        assertEquals("""{"contentHash":"abc123"}""", row.bodyJson)
        assertEquals("/data/cache/abc123.jpg", row.filePath)
        assertEquals(0, row.retryCount)
        assertEquals("잠시 후 다시 시도해주세요", row.lastError)
    }

    @Test
    fun `a non-retryable failure is never queued`() = runBlocking {
        val id = repository.enqueueIfRetryable(
            exception = envelope(retryable = false),
            method = "POST",
            endpoint = "references/analyze",
            bodyJson = "{}",
        )

        assertNull(id)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a successful replay deletes the row`() = runBlocking {
        repository.enqueueIfRetryable(envelope(true), "POST", "edit-jobs", "{}")

        val result = repository.retrySweepOnce { Result.success(Unit) }

        assertEquals(RetrySweepResult(succeeded = 1, failed = 0), result)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a failed replay keeps the row and increments retry_count`() = runBlocking {
        repository.enqueueIfRetryable(envelope(true), "POST", "edit-jobs", "{}")

        val result = repository.retrySweepOnce { Result.failure(RuntimeException("여전히 실패")) }

        assertEquals(RetrySweepResult(succeeded = 0, failed = 1), result)
        assertEquals(1, dao.count())
        val row = dao.rows.values.single()
        assertEquals(1, row.retryCount)
        assertEquals("여전히 실패", row.lastError)
    }

    @Test
    fun `a replay that throws instead of returning a failed Result is still handled as a failure`() = runBlocking {
        repository.enqueueIfRetryable(envelope(true), "POST", "edit-jobs", "{}")

        val result = repository.retrySweepOnce { error("boom") }

        assertEquals(RetrySweepResult(succeeded = 0, failed = 1), result)
        assertEquals(1, dao.count())
    }

    @Test
    fun `each queued row is replayed at most once per sweep`() = runBlocking {
        repository.enqueueIfRetryable(envelope(true), "POST", "a", "{}")
        repository.enqueueIfRetryable(envelope(true), "POST", "b", "{}")
        var replayCalls = 0

        repository.retrySweepOnce {
            replayCalls++
            Result.failure(RuntimeException("nope"))
        }

        assertEquals("exactly one replay per queued row, not a retry loop", 2, replayCalls)
    }

    @Test
    fun `mixed outcomes in one sweep - one succeeds, one keeps failing`() = runBlocking {
        val goodId = repository.enqueueIfRetryable(envelope(true), "POST", "good", "{}")
        val badId = repository.enqueueIfRetryable(envelope(true), "POST", "bad", "{}")

        val result = repository.retrySweepOnce { request ->
            if (request.id == goodId) Result.success(Unit) else Result.failure(RuntimeException("still down"))
        }

        assertEquals(RetrySweepResult(succeeded = 1, failed = 1), result)
        assertEquals(1, dao.count())
        assertTrue(badId != null && dao.rows.containsKey(badId))
    }

    @Test
    fun `a row that fails one sweep is retried again on the next reconnect sweep`() = runBlocking {
        // "1회 재시도" is per reconnect event, not a lifetime cap — the frozen
        // pending_requests schema has no "give up forever" column, so a still-
        // queued row must be eligible again the next time connectivity returns.
        repository.enqueueIfRetryable(envelope(true), "POST", "edit-jobs", "{}")
        repository.retrySweepOnce { Result.failure(RuntimeException("still down")) }

        var secondSweepCalls = 0
        val secondResult = repository.retrySweepOnce {
            secondSweepCalls++
            Result.success(Unit)
        }

        assertEquals(1, secondSweepCalls)
        assertEquals(RetrySweepResult(succeeded = 1, failed = 0), secondResult)
        assertEquals(0, dao.count())
    }

    @Test
    fun `pendingCount reflects the dao`() = runBlocking {
        assertEquals(0, repository.pendingCount())
        repository.enqueueIfRetryable(envelope(true), "POST", "a", "{}")
        assertEquals(1, repository.pendingCount())
    }
}
