package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * §3-2 상단 바 — "지금 어떤 스타일이 활성인가"를 정하는 유일한 결정.
 *
 * The subject under test starts out as the **current CameraScreen behaviour**,
 * extracted verbatim from the two `LaunchedEffect`s that decide `presetIndex`
 * today (see [resolveStyleIndex]'s header). So these tests are not measuring an
 * empty file — they are answering whether the wiring that already exists can
 * carry a session-level style change. Two of them say no:
 *
 * 1. There is no session choice at all. The onboarding value is the only input,
 *    so a user pick would be overwritten the moment the settings read lands.
 * 2. There is no "still loading" state. `presetIndex` starts at 0, so the first
 *    composition publishes preset 0 as the guide target and the onboarding
 *    preset arrives a frame later — a visible bracket jump before the user has
 *    touched anything, and an `AlignmentEngine`/stabilizer reset with it
 *    (`setStyleTarget` clears the smoothing window on every switch).
 *
 * Priority under test: **session pick > onboarding profile > first preset**.
 * The session pick is deliberately *not* persisted (TEAM.md §8) —
 * `app_settings.style_preset_id` is the D4 personalisation profile produced by
 * the onboarding cards, and overwriting it with a momentary in-session choice
 * would make §6-2's completion criterion unverifiable. That is exactly why the
 * two ids arrive here as separate parameters instead of one.
 */
class StyleSelectionTest {

    private val presets = listOf(
        "clean_social",
        "candid_feed",
        "bright_review",
        "soft_film",
        "casual_portrait",
        "night_street",
    )

    @Test
    fun `온보딩에서 고른 스타일이 초기 선택이 된다`() {
        // §6-2 → §3-2: the card picks resolve to a preset id, and that is what
        // the camera opens with when the user has not chosen anything yet.
        assertEquals(
            3,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = "soft_film",
                sessionId = null,
                loaded = true,
            ),
        )
    }

    @Test
    fun `사용자가 고른 스타일이 온보딩 추천을 이긴다`() {
        // The whole point of the top bar: a pick made in this session outranks
        // the stored profile. Without this, the change button is decorative —
        // the onboarding effect wins whenever it re-runs.
        assertEquals(
            5,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = "soft_film",
                sessionId = "night_street",
                loaded = true,
            ),
        )
    }

    @Test
    fun `온보딩 설정을 읽는 중에는 -1을 돌려 첫 setStyleTarget을 미룬다`() {
        // `loaded = false` is not the same as "no stored value": the settings
        // read is a suspend call that lands a frame or more after first
        // composition. Answering 0 here publishes the wrong guide target and
        // then swaps it, which resets the alignment smoothing window for free.
        // -1 means "no target yet" and is the only answer that costs nothing.
        assertEquals(
            -1,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = null,
                sessionId = null,
                loaded = false,
            ),
        )
    }

    @Test
    fun `저장된 스타일이 목록에 없으면 첫 스타일로 내려간다`() {
        // presets.json is replaceable (assets fallback now, GET /presets later),
        // so a stored id can outlive the preset that owned it. Degrade to a
        // valid style rather than to "no guide at all".
        assertEquals(
            0,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = "retired_preset",
                sessionId = null,
                loaded = true,
            ),
        )
    }

    // ── Below: branches that only exist once the priority above does. Added
    // after the five above went green, to pin the corners the new ordering
    // opened up rather than to drive it.

    @Test
    fun `읽는 중이라도 사용자가 고른 스타일은 즉시 적용된다`() {
        // The deferral is about not guessing, not about ignoring the user. If a
        // pick outranked the still-loading profile only *after* the read landed,
        // the late profile would yank the style back out from under them.
        assertEquals(
            5,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = null,
                sessionId = "night_street",
                loaded = false,
            ),
        )
    }

    @Test
    fun `읽기가 끝났고 저장된 값이 없으면 첫 스타일이다`() {
        // Onboarding skipped, or the settings read failed and the host degraded
        // it to "nothing stored". Distinct from `loaded = false`: the wait is
        // over, so publishing a target is now correct.
        assertEquals(
            0,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = null,
                sessionId = null,
                loaded = true,
            ),
        )
    }

    @Test
    fun `세션 선택이 목록에서 사라지면 온보딩 값으로 내려간다`() {
        // Degradation runs down the priority order, not straight to zero — the
        // profile is still a better answer than the first preset.
        assertEquals(
            3,
            resolveStyleIndex(
                presetIds = presets,
                onboardingId = "soft_film",
                sessionId = "retired_preset",
                loaded = true,
            ),
        )
    }

    @Test
    fun `저장된 값이 없는 것과 아직 읽지 않은 것은 다른 상태다`() {
        // The host says `loaded = onboardingStyle != null`, which only works if a
        // wrapper *holding* null survives as non-null. Value classes box exactly
        // when they have to, but this is the one axis where they are sharp: if
        // `OnboardingStyle(null)` ever collapsed to `null`, a user who skipped
        // onboarding would read as "still loading" forever and the guide target
        // would never be published at all. Silent, and device-only otherwise.
        val notReadYet: OnboardingStyle? = null
        val readNothingStored: OnboardingStyle? = OnboardingStyle(null)
        assertNull(notReadYet)
        assertNotNull(readNothingStored)

        assertEquals(
            -1,
            resolveStyleIndex(presets, notReadYet?.id, null, notReadYet != null),
        )
        assertEquals(
            0,
            resolveStyleIndex(presets, readNothingStored?.id, null, readNothingStored != null),
        )
    }

    @Test
    fun `프리셋 목록이 비면 -1이다`() {
        // assets/presets.json failed to parse. There is no style to name in the
        // bar and no target to publish; the camera still has to work.
        assertEquals(
            -1,
            resolveStyleIndex(
                presetIds = emptyList(),
                onboardingId = "soft_film",
                sessionId = "night_street",
                loaded = true,
            ),
        )
    }

    // ---- §6-2 스타일 스트립 재정렬 (orderByRank) ----

    private fun order(ranked: List<String>) = orderByRank(presets, ranked) { it }

    @Test
    fun `추천 순위가 스트립 앞으로 온다`() {
        assertEquals(
            listOf("soft_film", "night_street", "clean_social", "candid_feed", "bright_review", "casual_portrait"),
            order(listOf("soft_film", "night_street")),
        )
    }

    @Test
    fun `추천이 비면 순서를 건드리지 않는다`() {
        // The profile read has not landed, or the user skipped onboarding. The
        // catalogue order is the answer, and it must be the *same list* so the
        // strip does not recompose for nothing.
        assertEquals(presets, order(emptyList()))
        assertSame(presets, order(emptyList()))
    }

    @Test
    fun `모르는 id는 무시한다`() {
        // presets.json is the offline fallback for GET /presets, so a ranking can
        // outlive the catalogue it was computed against — the same way a stored
        // style_preset_id can. Skip the dead ids, honour the live ones.
        assertEquals(
            listOf("night_street", "clean_social", "candid_feed", "bright_review", "soft_film", "casual_portrait"),
            order(listOf("retired_preset", "night_street", "also_gone")),
        )
        // Entirely stale: degrade to no reordering, not to an empty strip.
        assertEquals(presets, order(listOf("retired_preset", "also_gone")))
    }

    @Test
    fun `순위에 없는 프리셋은 원래 상대 순서를 유지한다`() {
        // A ranking is a preference, not a filter. Hoisting the two ranked styles
        // must not disturb how the remaining four sit relative to each other.
        val result = order(listOf("casual_portrait", "bright_review"))
        assertEquals(listOf("casual_portrait", "bright_review"), result.take(2))
        assertEquals(listOf("clean_social", "candid_feed", "soft_film", "night_street"), result.drop(2))
    }

    @Test
    fun `중복된 추천 id가 있어도 프리셋은 정확히 한 번씩 나온다`() {
        // ProfileEngine.recommend cannot emit duplicates today, but the ranking is
        // about to arrive from persistence and eventually from the server. A list
        // that loses or clones a style is a far worse failure than a mis-ordered
        // one, so it is closed off here rather than trusted upstream.
        val result = order(listOf("soft_film", "soft_film", "night_street"))
        assertEquals(presets.size, result.size)
        assertEquals(presets.toSet(), result.toSet())
        assertEquals(listOf("soft_film", "night_street"), result.take(2))
    }

    @Test
    fun `재정렬해도 선택된 스타일은 그대로 선택되어 있다`() {
        // THE trap. An index-based selection silently jumps to a different style
        // the moment the list moves: the user is on `bright_review` at index 2,
        // the profile read lands, and index 2 is now `candid_feed` — a style they
        // never chose, driving a guide target they never asked for.
        //
        // The contract that prevents it: derive the ids from the *reordered* list
        // and let resolveStyleIndex recompute the index from the id. This test is
        // the wiring in CameraScreen, spelled out.
        val chosen = "bright_review"
        val before = resolveStyleIndex(presets, onboardingId = null, sessionId = chosen, loaded = true)
        assertEquals(2, before)

        val reordered = order(listOf("soft_film", "night_street"))
        val after = resolveStyleIndex(reordered, onboardingId = null, sessionId = chosen, loaded = true)

        // The index moved...
        assertNotEquals(before, after)
        // ...and the style under it did not, which is the only thing the user sees.
        assertEquals(chosen, presets[before])
        assertEquals(chosen, reordered[after])
    }

    @Test
    fun `추천 1순위는 스트립 첫 칸이자 초기 선택이 된다`() {
        // What the camera screen actually opens with once the profile is honoured:
        // the top recommendation leads the strip, and the id-based resolve lands
        // on it without anyone passing an index around.
        val ranked = listOf("night_street", "soft_film")
        val reordered = order(ranked)
        val index = resolveStyleIndex(reordered, onboardingId = "night_street", sessionId = null, loaded = true)

        assertEquals(0, index)
        assertEquals("night_street", reordered[index])
    }
}
