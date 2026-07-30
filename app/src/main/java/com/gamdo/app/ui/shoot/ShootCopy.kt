package com.gamdo.app.ui.shoot

/**
 * Every user-visible string on the `나 찍어줘` screen, in one place.
 *
 * Collected here rather than inlined at each call site so the owner can change the
 * whole screen's voice in one file — the strings were approved as a set, and a set is
 * easier to re-approve than eleven scattered literals. (This is not a localisation
 * layer; the app ships Korean only and has no `strings.xml` habit.)
 *
 * ## Two rules the strings themselves enforce
 *
 *  1. **No server diagnostics.** [FAILED_TITLE] is a fixed sentence with no
 *     interpolation, so there is no slot an HTTP code, a `fail_reason`, or an
 *     exception message could be dropped into. It reuses the wording already on
 *     screen in `ReferenceCreateSheet` for the same situation.
 *  2. **No hardcoded server policy.** The link's lifetime and photo cap belong to
 *     `routes/shoot_sessions.py` (`SESSION_TTL_MS`, `MAX_PHOTOS`) and the server may
 *     change either. [terms] takes both as arguments — the minutes from
 *     [shootRemainingMinutes] over the session's own `expiresAt`, the cap from the
 *     session response's `maxPhotos`. Writing "1시간 · 최대 5장" here would be a second
 *     home for a value the server owns, which is how a screen goes quietly stale.
 */
object ShootCopy {

    const val TITLE = "나 찍어줘"

    /** No sendable policy: the camera has not locked a 구도 to hand over. */
    const val NO_LAYOUT_TITLE = "지금은 넘길 구도가 없어요"
    const val NO_LAYOUT_BODY = "구도가 잡히면 친구에게 넘길 수 있어요"

    /**
     * Reserved for the manual frame picker another agent is building.
     *
     * P2 asks for "기본 프레임 선택 또는 취소 중 하나" and the picker does not exist yet,
     * so the screen currently offers only 취소 — see [DelegatedShootScreen]'s
     * `onPickFrame` parameter, which is null until that lands.
     */
    const val PICK_FRAME = "프레임 고르기"

    const val CREATE = "링크 만들기"
    const val CREATING = "링크를 만들고 있어요"

    const val WAITING_TITLE = "친구가 이 코드를 열면 촬영이 시작돼요"
    const val WAITING_EMPTY = "아직 도착한 사진이 없어요"

    const val RECEIVE = "사진 받기"
    const val RECEIVING = "사진을 가져오고 있어요"

    const val EXPIRED_TITLE = "링크가 만료됐어요"
    const val EXPIRED_ACTION = "다시 만들기"

    /** Deliberately identical to the sentence `ReferenceCreateSheet` already shows. */
    const val FAILED_TITLE = "서버에 연결하지 못했어요"
    const val RETRY = "다시 시도"

    const val CLOSE = "닫기"
    const val CANCEL = "취소"

    /** e.g. `사진 2장이 도착했어요`. */
    fun arrived(photoCount: Int): String = "사진 ${photoCount}장이 도착했어요"

    /**
     * e.g. `42분 남았어요 · 최대 5장`.
     *
     * Both numbers come from the session the server issued; neither is a literal.
     * Falls back to the cap alone when the remaining time is unknown or already gone,
     * because a wrong duration is worse than no duration.
     */
    fun terms(remainingMinutes: Int, maxPhotos: Int): String = when {
        maxPhotos <= 0 && remainingMinutes <= 0 -> ""
        maxPhotos <= 0 -> "${remainingMinutes}분 남았어요"
        remainingMinutes <= 0 -> "최대 ${maxPhotos}장"
        else -> "${remainingMinutes}분 남았어요 · 최대 ${maxPhotos}장"
    }
}
