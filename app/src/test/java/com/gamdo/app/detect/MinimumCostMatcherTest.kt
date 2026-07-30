package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class MinimumCostMatcherTest {
    @Test
    fun `maximizes valid one to one matches before cost`() {
        val pairs = MinimumCostMatcher.match(
            arrayOf(
                floatArrayOf(.10f, .20f),
                floatArrayOf(.11f, 10f),
            ),
        )

        assertEquals(
            listOf(MinimumCostMatcher.Pair(0, 1), MinimumCostMatcher.Pair(1, 0)),
            pairs,
        )
    }

    @Test
    fun `invalid candidate is never assigned`() {
        val pairs = MinimumCostMatcher.match(arrayOf(floatArrayOf(10f, .25f)))
        assertEquals(listOf(MinimumCostMatcher.Pair(0, 1)), pairs)
    }
}
