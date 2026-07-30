package com.gamdo.app.ui.reference

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.SecondaryPillButton
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.Ink700
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.Scrim
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid

/**
 * 내 감도 상세 — the management surface for the active 감도, and the home of
 * 취향 더 정교하게 만들기 (요구사항 2026-07-30, 6bef31b §프로필 정교화).
 *
 * ## Why this screen exists at all
 *
 * P2's requirement puts the refinement entry point on "활성 내 감도 상세/관리 화면"
 * rather than in the camera, because adding photos to a preference is not a thing
 * anyone does in the half-second before a shutter press. There was no such surface —
 * the active 감도 was a 58dp thumbnail and nothing else — so this is it.
 *
 * ## It also takes the delete away from the thumbnail
 *
 * 삭제 used to be a 15dp `×` badge inside that thumbnail, and it was the cause of
 * 결함 3 (see [MyReferenceThumb]): Compose hit-tests innermost-first, so a corner
 * mis-tap on the tile the user meant to *select* deleted their 감도 from Room with no
 * confirmation and no undo. Moving it here removes the destructive target from the
 * strip entirely rather than making it harder to hit, which is strictly better than
 * the long-press it briefly became — a mis-tap now opens a sheet, and a sheet is
 * dismissible.
 *
 * @param onRefine hands the picked photos to
 *   `ProfileRefinementRepository.refineFromPhotos`. The picker itself belongs to the
 *   host (it needs an Activity result registry), so this only asks for it.
 */
@Composable
fun ReferenceDetailSheet(
    opened: Boolean,
    imageUri: Uri?,
    refineState: ProfileRefineState,
    onRefine: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!opened) return

    Box(modifier = modifier.fillMaxSize()) {
        // Tap-outside-to-dismiss, same construction as `ReferenceCreateSheet` — see
        // that file for why the sheet body below has to claim its own gestures rather
        // than relying on being drawn on top.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ink900)
                // Claims every gesture that starts on the sheet, so padding and
                // headings do not fall through to the scrim and dismiss it mid-flow.
                .pointerInput(Unit) {}
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The photo the 감도 came from. Absent after a relaunch — the session
                // holds the picked Uri, not the file — and a missing photo draws the
                // plain slot rather than a stand-in (AGENTS §7-6).
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(11.dp)).background(Ink700),
                ) {
                    if (imageUri != null) {
                        AsyncImage(
                            model = imageUri,
                            contentDescription = ReferenceLabels.ACTIVE,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    text = ReferenceLabels.ACTIVE,
                    color = TextHi,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            when (refineSectionFor(refineState)) {
                ProfileRefineSection.ACTION -> {
                    Text(
                        text = "좋아하는 사진을 더 넣으면 취향이 더 정확해져요. 최대 ${MAX_REFINE_PHOTOS}장.",
                        color = TextMid,
                        fontSize = 12.5.sp,
                    )
                    PrimaryPillButton(text = "취향 더 정교하게 만들기", onClick = onRefine)
                }

                ProfileRefineSection.PROGRESS -> {
                    val count = (refineState as ProfileRefineState.Analyzing).count
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Amber, strokeWidth = 2.dp)
                        Text(text = "사진 ${count}장을 살펴보는 중이에요", color = TextMid, fontSize = 12.5.sp)
                    }
                }

                ProfileRefineSection.DONE -> {
                    Text(
                        text = refineDoneMessage((refineState as ProfileRefineState.Done).count),
                        color = TextHi,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SecondaryPillButton(text = "사진 더 넣기", onClick = onRefine)
                }

                ProfileRefineSection.FAILED -> {
                    val retryable = (refineState as ProfileRefineState.Failed).retryable
                    Text(text = refineFailedMessage(retryable), color = Amber, fontSize = 12.5.sp)
                    // 재시도 only where trying again could work. A missing onboarding
                    // profile is not fixed by a second tap, and offering one would be
                    // inviting the user to fail twice.
                    if (retryable) PrimaryPillButton(text = "다시 시도", onClick = onRefine)
                }
            }

            Spacer()
            // Destructive, so it is the quietest control here and it is the only place
            // deletion lives now.
            SecondaryPillButton(text = "이 감도 삭제", onClick = onDelete)
            SecondaryPillButton(text = "닫기", onClick = onDismiss)
        }
    }
}

/** A hair of air above the destructive pair, without importing a spacer for one use. */
@Composable
private fun Spacer() {
    Box(modifier = Modifier.height(2.dp))
}
