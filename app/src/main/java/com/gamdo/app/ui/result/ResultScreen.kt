package com.gamdo.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.theme.Charcoal600
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.OutlineDim
import com.gamdo.app.ui.theme.Sage

/**
 * Edit / result (t2 2f skeleton) — top bar, image, filter strip, sliders (static),
 * save. Real local-edit pipeline + before/after slider land in Day 4.
 */
@Composable
fun ResultScreen(
    captureId: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.gamdo.app.ui.theme.Charcoal900),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("‹", color = OnDarkMedium, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onBack))
            Text("보정", color = OnDarkHigh, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("완료", color = Sage, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack))
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(moodBrush(0)),
        ) {
            Box(
                modifier = Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Sage)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("내 감도", color = OnSage, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Filter strip
        Row(
            modifier = Modifier
                .padding(start = 20.dp, top = 14.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterThumb("원본", index = 0, selected = false)
            FilterThumb("내 감도", index = 0, selected = true)
            FilterThumb("따뜻한 카페", index = 1, selected = false)
            FilterThumb("밝은 리뷰", index = 2, selected = false)
            FilterThumb("소프트 필름", index = 3, selected = false)
            AddFilterThumb()
            Box(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SliderRow("밝기", "+12", fraction = 0.62f, active = true)
            SliderRow("따뜻함", "+6", fraction = 0.56f, active = true)
            SliderRow("대비", "0", fraction = 0.5f, active = false)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 18.dp)) {
            PrimaryPillButton(text = "저장", onClick = onBack)
        }
    }
}

@Composable
private fun FilterThumb(label: String, index: Int, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(moodBrush(index))
                .then(if (selected) Modifier.border(2.dp, Sage, RoundedCornerShape(11.dp)) else Modifier),
        )
        Text(
            text = label,
            color = if (selected) Sage else OnDarkMedium,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AddFilterThumb() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(1.5.dp, OutlineDim, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = OnDarkMuted, fontSize = 20.sp)
        }
        Text("필터 저장", color = OnDarkMuted, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SliderRow(label: String, value: String, fraction: Float, active: Boolean) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = OnDarkMedium, fontSize = 12.5.sp)
            Text(value, color = if (active) Sage else OnDarkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Charcoal600),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (active) Sage else OutlineDim),
            )
        }
    }
}
