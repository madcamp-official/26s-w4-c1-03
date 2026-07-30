package com.gamdo.app.detect

/**
 * Exact one-to-one assignment for the small live-scene problem.
 *
 * The detector is capped at 12 candidates, so a bit-mask dynamic program is
 * both simpler and safer than a floating-point Hungarian implementation. It
 * maximizes the number of valid matches first, then minimizes total cost.
 * Invalid pairs are represented by [infinity] and are never selected.
 */
object MinimumCostMatcher {
    data class Pair(val left: Int, val right: Int)

    fun match(costs: Array<FloatArray>, infinity: Float = 10f): List<Pair> {
        if (costs.isEmpty() || costs[0].isEmpty()) return emptyList()
        require(costs.all { it.size == costs[0].size })
        require(costs.size <= 12 && costs[0].size <= 12)

        val leftCount = costs.size
        val rightCount = costs[0].size
        val memo = HashMap<Long, Result>()

        fun solve(left: Int, used: Int): Result {
            if (left == leftCount) return Result(0, 0f, emptyList())
            val key = (left.toLong() shl 32) or (used.toLong() and 0xffffffffL)
            memo[key]?.let { return it }

            var best = solve(left + 1, used)
            for (right in 0 until rightCount) {
                if ((used and (1 shl right)) != 0) continue
                val cost = costs[left][right]
                if (cost >= infinity) continue
                val next = solve(left + 1, used or (1 shl right))
                val candidate = Result(
                    matches = next.matches + 1,
                    cost = next.cost + cost,
                    pairs = listOf(Pair(left, right)) + next.pairs,
                )
                if (candidate.betterThan(best)) best = candidate
            }
            memo[key] = best
            return best
        }

        return solve(0, 0).pairs
    }

    private data class Result(
        val matches: Int,
        val cost: Float,
        val pairs: List<Pair>,
    ) {
        fun betterThan(other: Result): Boolean =
            matches > other.matches || (matches == other.matches && cost < other.cost)
    }
}
