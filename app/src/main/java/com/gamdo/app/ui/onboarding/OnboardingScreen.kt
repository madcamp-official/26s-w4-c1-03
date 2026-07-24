package com.gamdo.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.components.moodCount
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage

private const val MIN_PICKS = 3

/**
 * Onboarding (t2): pick preferences (2a) → "내 감도 저장" summary (2b) → done.
 * Card grid is a placeholder — real cards + on-device profiling land in §6-2.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    when (step) {
        0 -> PickStep(onNext = { step = 1 })
        else -> SavedStep(onStart = onFinished)
    }
}

@Composable
private fun PickStep(onNext: () -> Unit) {
    val selected = remember { mutableStateOf(setOf(0, 2, 4)) }
    val count = selected.value.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal900),
    ) {
        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 26.dp)) {
            Text(
                text = "마음이 가는 사진을\n골라 주세요",
                color = OnDarkHigh,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 31.sp,
            )
            Text(
                text = "${MIN_PICKS}장이면 충분해요.",
                color = OnDarkMedium,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items((0 until moodCount).toList()) { index ->
                val isSelected = index in selected.value
                PickCard(
                    index = index,
                    selected = isSelected,
                    onToggle = {
                        selected.value = selected.value.toMutableSet().apply {
                            if (isSelected) remove(index) else add(index)
                        }
                    },
                )
            }
        }

        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 18.dp)) {
            PrimaryPillButton(
                text = if (count >= MIN_PICKS) "다음" else "${MIN_PICKS - count}장 더 골라 주세요",
                enabled = count >= MIN_PICKS,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun PickCard(index: Int, selected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(moodBrush(index))
            .then(
                if (selected) {
                    Modifier.border(2.5.dp, Sage, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onToggle),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Sage),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✓", color = OnSage, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun SavedStep(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal900),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 26.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SavedSwatch(Color(0xFF7D8F6A))
                SavedSwatch(Color(0xFFC9C4A6))
                SavedSwatch(Color(0xFFE8E2D2))
            }
            Text(
                text = "당신의 감도를\n저장했어요",
                color = OnDarkHigh,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 35.sp,
                modifier = Modifier.padding(top = 22.dp),
            )
            Column(
                modifier = Modifier.padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SavedBullet("부드럽고 자연스러운 색감")
                SavedBullet("밝은 자연광의 감성")
                SavedBullet("여백을 살린 구도")
            }
            Text(
                text = "앞으로 촬영 가이드와 보정에 이 느낌을 자동으로 반영해요.",
                color = OnDarkMuted,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 22.dp),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 26.dp).padding(bottom = 22.dp)) {
            PrimaryPillButton(text = "촬영 시작하기", onClick = onStart)
        }
    }
}

@Composable
private fun SavedSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun SavedBullet(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Sage),
        )
        Text(text = text, color = Color(0xFFC8CCC1), fontSize = 14.5.sp)
    }
}
