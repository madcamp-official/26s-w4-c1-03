package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §1-5 — which taps are allowed to move focus.
 *
 * The subject under test starts out as **CameraX's own built-in rule**, extracted
 * verbatim (see `TapFocusGeometry`'s header). `CameraController.isTapToFocusEnabled`
 * defaults to true, so that rule is already in the app; it is only unreachable
 * because the pinch `Box` swallows every DOWN before `PreviewView.onTouchEvent`
 * runs. So these tests are not measuring an empty file — they are answering
 * whether re-enabling the built-in would be a fix. Four of them say no.
 *
 * Two things the built-in cannot know, because both are Compose-side and drawn
 * *over* the view it maps against:
 *
 * 1. **The aspect mask.** `CameraPreviewPane` letterboxes to 4:5 / 1:1 with
 *    `windowHeight = min(paneWidth / ratioWtoH, paneHeight)` and
 *    `barHeight = (paneHeight - windowHeight) / 2`. Content under the bars is
 *    invisible *and* is cropped away at save time by `centerCropToRatio`, so
 *    focusing there racks the lens onto something the user will never receive.
 * 2. **Compose gesture plumbing.** A tap can arrive before the node is measured,
 *    and `Offset`/size values are floats that are not guaranteed finite. A NaN
 *    reaching `MeteringPoint` is not a bad focus — it is a poisoned metering
 *    rectangle deep inside CameraX.
 *
 * The bar-boundary case is also the canary for a bug this change is capable of
 * introducing: `ratioWtoH` is captured by value into the `pointerInput` block, so
 * if `aspect` is left out of the `pointerInput` keys the boundary keeps being
 * computed from the *previous* aspect after the 4:5 / 1:1 toggle. See
 * [`바 경계는 마스크 수식과 같고 비율을 바꾸면 함께 움직인다`], which pins one tap
 * that must resolve differently for the two aspects at an identical pane size.
 */
class TapFocusGeometryTest {

    @Test
    fun `창 안쪽 탭은 좌표를 그대로 전달한다`() {
        // PreviewView.meteringPointFactory wants view pixels and owns the
        // FILL_CENTER crop math itself, so an in-window tap must pass through
        // untouched — no normalising, no re-scaling.
        val point = resolveTapFocusPoint(
            tapX = 300f, tapY = 700f,
            paneWidth = PANE_W, paneHeight = PANE_H,
            ratioWtoH = RATIO_4_5,
        )
        assertNotNull("창 한가운데 탭이 거부됐다", point)
        assertEquals(300f, point!!.x, TOL)
        assertEquals(700f, point.y, TOL)
    }

    @Test
    fun `보이지 않는 영역의 탭은 초점을 옮기지 않는다`() {
        // 1080x1400 @ 4:5 -> windowHeight 1350, barHeight 25.
        // Top bar, bottom bar, and (defensively) outside the pane entirely.
        assertNull("위쪽 바 탭", resolveTapFocusPoint(540f, 10f, PANE_W, PANE_H, RATIO_4_5))
        assertNull("아래쪽 바 탭", resolveTapFocusPoint(540f, 1390f, PANE_W, PANE_H, RATIO_4_5))
        assertNull("pane 왼쪽 밖", resolveTapFocusPoint(-1f, 700f, PANE_W, PANE_H, RATIO_4_5))
        assertNull("pane 오른쪽 밖", resolveTapFocusPoint(PANE_W, 700f, PANE_W, PANE_H, RATIO_4_5))
    }

    @Test
    fun `바 경계는 마스크 수식과 같고 비율을 바꾸면 함께 움직인다`() {
        // The discriminating tap: y=100 sits inside the 4:5 window (bar 25) and on
        // the top bar for 1:1 (bar 160) at the *same* pane size. A stale ratioWtoH
        // gets exactly one of these two wrong.
        assertNotNull("4:5 에서 y=100 은 창 안", resolveTapFocusPoint(540f, 100f, PANE_W, PANE_H, RATIO_4_5))
        assertNull("1:1 에서 y=100 은 바 위", resolveTapFocusPoint(540f, 100f, PANE_W, PANE_H, RATIO_1_1))

        // 1:1 divides exactly (1080/1f), so the half-open window [160, 1240) can be
        // pinned to the pixel.
        assertNull("1:1 상단 바 마지막 줄", resolveTapFocusPoint(540f, 159f, PANE_W, PANE_H, RATIO_1_1))
        assertNotNull("1:1 창 첫 줄", resolveTapFocusPoint(540f, 160f, PANE_W, PANE_H, RATIO_1_1))
        assertNotNull("1:1 창 마지막 줄", resolveTapFocusPoint(540f, 1239f, PANE_W, PANE_H, RATIO_1_1))
        assertNull("1:1 하단 바 첫 줄", resolveTapFocusPoint(540f, 1240f, PANE_W, PANE_H, RATIO_1_1))

        // 4:5 lands on 1080f/0.8f = 1349.99998, i.e. barHeight 25.00001. Straddle
        // the boundary rather than sitting on it — float rounding, not the rule,
        // decides y == 25f, and pinning that would pin the rounding.
        assertNull("4:5 상단 바", resolveTapFocusPoint(540f, 24f, PANE_W, PANE_H, RATIO_4_5))
        assertNotNull("4:5 창 안", resolveTapFocusPoint(540f, 26f, PANE_W, PANE_H, RATIO_4_5))

        // Degenerate pane: too short for the requested ratio, so coerceAtMost
        // clamps and barHeight collapses to 0. The mask draws no bars there and
        // neither may we — do not invent side bars the user cannot see.
        assertNotNull("바 없는 pane 최상단", resolveTapFocusPoint(540f, 0f, PANE_W, 800f, RATIO_4_5))
        assertNotNull("바 없는 pane 최하단", resolveTapFocusPoint(540f, 799f, PANE_W, 800f, RATIO_4_5))
        assertNull("바 없는 pane 밖", resolveTapFocusPoint(540f, 800f, PANE_W, 800f, RATIO_4_5))
    }

    @Test
    fun `비유한 입력은 MeteringPoint 로 새지 않는다`() {
        // Reject at the boundary. A NaN that reaches createPoint() becomes a
        // metering rectangle inside CameraX, where it is no longer our bug.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull("tapX=$bad", resolveTapFocusPoint(bad, 700f, PANE_W, PANE_H, RATIO_4_5))
            assertNull("tapY=$bad", resolveTapFocusPoint(540f, bad, PANE_W, PANE_H, RATIO_4_5))
            assertNull("paneWidth=$bad", resolveTapFocusPoint(540f, 700f, bad, PANE_H, RATIO_4_5))
            assertNull("paneHeight=$bad", resolveTapFocusPoint(540f, 700f, PANE_W, bad, RATIO_4_5))
            assertNull("ratio=$bad", resolveTapFocusPoint(540f, 700f, PANE_W, PANE_H, bad))
        }
    }

    @Test
    fun `pane 이 아직 측정되지 않았으면 초점을 옮기지 않는다`() {
        // A zero-size pointer-input node makes barHeight meaningless, so there is
        // no window to be inside of. Guard the divisor too.
        assertNull("폭 0", resolveTapFocusPoint(0f, 0f, 0f, PANE_H, RATIO_4_5))
        assertNull("높이 0", resolveTapFocusPoint(0f, 0f, PANE_W, 0f, RATIO_4_5))
        assertNull("폭 음수", resolveTapFocusPoint(540f, 700f, -PANE_W, PANE_H, RATIO_4_5))
        assertNull("높이 음수", resolveTapFocusPoint(540f, 700f, PANE_W, -PANE_H, RATIO_4_5))
        assertNull("비율 0", resolveTapFocusPoint(540f, 700f, PANE_W, PANE_H, 0f))
        assertNull("비율 음수", resolveTapFocusPoint(540f, 700f, PANE_W, PANE_H, -RATIO_4_5))
    }

    private companion object {
        const val TOL = 1e-4f

        /** A portrait pane in px, sized so 4:5 and 1:1 give clearly different bars. */
        const val PANE_W = 1080f
        const val PANE_H = 1400f

        // Bound to the product enum, not to copied literals: D9-1 fixes the set at
        // exactly these two, and a third would have to show up here first.
        val RATIO_4_5 = CaptureAspect.RATIO_4_5.ratioWtoH
        val RATIO_1_1 = CaptureAspect.RATIO_1_1.ratioWtoH
    }
}
