// 대상이 remain_plan O-1로 컷돼 @Deprecated(ERROR)가 붙었다. 테스트는 **의도적으로 남긴다** —
// 5분 예산과 경계 동작(elapsed == BUDGET이면 이미 타임아웃)은 컷과 무관하게 확정된 값이다.
// 폐기를 걷어내고 경로를 되살리는 사람에게 이 테스트가 안전망이 된다. 이 suppress를 지우려면
// 테스트도 함께 지워야 하고, 그건 컷을 되돌리는 것이 아니라 되돌릴 수단을 버리는 것이다.
@file:Suppress("DEPRECATION_ERROR")

package com.gamdo.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTimeoutPolicyTest {

    @Test
    fun `budget is exactly 5 minutes`() {
        assertEquals(300_000L, JobTimeoutPolicy.BUDGET.inWholeMilliseconds)
    }

    @Test
    fun `not timed out just under the budget`() {
        assertFalse(JobTimeoutPolicy.hasTimedOut(startedAtMillis = 0L, nowMillis = 299_999L))
    }

    @Test
    fun `timed out exactly at the budget boundary`() {
        assertTrue(JobTimeoutPolicy.hasTimedOut(startedAtMillis = 0L, nowMillis = 300_000L))
    }

    @Test
    fun `timed out well past the budget`() {
        assertTrue(JobTimeoutPolicy.hasTimedOut(startedAtMillis = 1_000L, nowMillis = 10_000_000L))
    }

    @Test
    fun `not timed out immediately after starting`() {
        assertFalse(JobTimeoutPolicy.hasTimedOut(startedAtMillis = 500_000L, nowMillis = 500_100L))
    }
}
