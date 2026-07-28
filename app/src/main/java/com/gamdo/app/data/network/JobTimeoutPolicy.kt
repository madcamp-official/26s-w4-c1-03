package com.gamdo.app.data.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * §6-1: "생성 job 5분 타임아웃 → 폴백". Pure and stateless on purpose —
 * `EditJobRepository`'s polling loop (§5-3, wave 3) is the intended caller, but
 * that repository doesn't exist yet, so this only answers the one question a
 * poller needs ("has this job been running too long?"). Kept Context-free and
 * dependency-free so the 5-minute budget itself is locked down and tested well
 * before the polling loop that will consume it.
 */
@Deprecated(
    message = "생성 job(§5-3)은 remain_plan O-1로 컷됐고, 남은 서버 호출은 GET /presets " +
        "하나뿐이라 폴링 루프가 생길 일이 없다. 5분 예산 자체는 맞으니 §5-3이 부활하면 " +
        "이 폐기만 걷어내면 된다. remain_plan §1 참조.",
    level = DeprecationLevel.ERROR,
)
object JobTimeoutPolicy {

    /** The fixed budget from `P1_Plan_1.md` §6-1 — not configurable per job. */
    val BUDGET: Duration = 5.minutes

    /**
     * True once [nowMillis] is at or past [BUDGET] after [startedAtMillis]
     * (job creation / first `queued` response). At the boundary itself
     * (elapsed == BUDGET) this already reports timed out — a poller should stop
     * and fall back rather than issue one more `GET /edit-jobs/{jobId}`.
     */
    fun hasTimedOut(startedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - startedAtMillis >= BUDGET.inWholeMilliseconds
}
