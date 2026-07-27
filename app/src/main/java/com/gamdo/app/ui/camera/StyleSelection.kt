package com.gamdo.app.ui.camera

/**
 * Outcome of the one-shot `app_settings.style_preset_id` read.
 *
 * The wrapper exists because `String?` alone cannot say whether the read has
 * finished: `null` has to mean "still reading" *and* "read finished, nothing
 * stored", and those two need opposite answers from [resolveStyleIndex]. So the
 * host holds a nullable *wrapper* — `null` = not read yet — and the inner
 * [id] carries the stored value, which may itself be null.
 */
@JvmInline
value class OnboardingStyle(val id: String?)

/**
 * §3-2 상단 바 — the single decision behind "which style is active right now",
 * kept apart from Compose so it can be pinned on the JVM (no `android.*` here).
 *
 * Priority: **[sessionId] > [onboardingId] > first preset.**
 *
 * The two ids stay separate parameters on purpose. `app_settings.style_preset_id`
 * is the D4 personalisation profile written by the onboarding cards; a style the
 * user switches to mid-session is *not* written back to it (TEAM.md §8), because
 * overwriting the profile with a momentary choice would make §6-2's completion
 * criterion — two different card sets producing different style ordering —
 * unverifiable. Relaunching the app therefore returns to the onboarding profile.
 *
 * @param presetIds preset ids **in display order**. The session choice is carried
 *   as an id rather than an index for the same reason: §6-2 will reorder this
 *   list by recommendation rank, and a stored index would silently come to point
 *   at a different style.
 * @param loaded whether the settings read has completed. While it has not, this
 *   returns `-1` — "no target yet" — so the guide target is published once,
 *   with the right preset, instead of publishing preset 0 and swapping it a
 *   frame later. That swap is not free: `CameraViewModel.setStyleTarget` resets
 *   the alignment smoothing window and the display stabilizer, so the bracket
 *   would visibly jump before the user had touched anything.
 * @return index into [presetIds], or `-1` when no style should be published yet.
 */
fun resolveStyleIndex(
    presetIds: List<String>,
    onboardingId: String?,
    sessionId: String?,
    loaded: Boolean,
): Int {
    if (presetIds.isEmpty()) return -1

    // A pick made in this session wins outright, and wins even while the
    // settings read is still in flight — otherwise a late-arriving profile
    // would yank the style out from under a user who just chose one.
    val fromSession = sessionId?.let { presetIds.indexOf(it) } ?: -1
    if (fromSession >= 0) return fromSession

    if (!loaded) return -1

    // A stored id can outlive the preset that owned it (presets.json is the
    // offline fallback for GET /presets). Degrade to a valid style, not to none.
    val fromOnboarding = onboardingId?.let { presetIds.indexOf(it) } ?: -1
    if (fromOnboarding >= 0) return fromOnboarding

    return 0
}
