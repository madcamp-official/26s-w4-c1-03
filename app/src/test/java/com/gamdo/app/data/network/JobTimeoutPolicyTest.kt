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
