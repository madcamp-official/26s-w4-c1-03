package com.gamdo.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.theme.Charcoal800
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.Sage

private val GuideLime = Color(0xFFCDD69A)
private val GridLine = Color(0x47FFFFFF)

/**
 * Camera = home (t2 skeleton). Static preview + guide-overlay stand-in; real
 * CameraX preview/analysis/overlay land in §1-5 and Day 2–3.
 */
@Composable
fun CameraScreen(
    onOpenAlbum: () -> Unit,
    onCaptured: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal950),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6242822))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Sage))
                Text("내 감도 적용 중", color = OnDarkHigh, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF20261E), Color(0xFF0F120E)))),
        ) {
            RuleOfThirds()
            // Target-frame bracket (sage corners) — the only guide style allowed (D2)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 150.dp, height = 200.dp),
            ) {
                CornerMark(Alignment.TopStart)
                CornerMark(Alignment.TopEnd)
                CornerMark(Alignment.BottomStart)
                CornerMark(Alignment.BottomEnd)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(GuideLime),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoomChip(".5", active = false)
                ZoomChip("1x", active = true)
                ZoomChip("2x", active = false)
            }
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Charcoal800)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(moodBrush(0)))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Sage))
                    Text("AI가 잡은 최적 구도 · 원 포인트", color = OnDarkHigh, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text("장면을 분석해 최적의 구도를 표시했어요", color = OnDarkMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(moodBrush(2))
                        .border(1.5.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenAlbum),
                )
                Text("앨범", color = OnDarkMedium, fontSize = 10.sp)
            }

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color(0xE6FFFFFF), CircleShape)
                    .clickable(onClick = onCaptured),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(OnDarkHigh))
            }

            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF242822)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⟲", color = OnDarkMedium, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun RuleOfThirds() {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.height(1.dp).fillMaxWidth().background(GridLine))
        Box(Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.height(1.dp).fillMaxWidth().background(GridLine))
        Box(Modifier.weight(1f).fillMaxWidth())
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(GridLine))
        Box(Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(GridLine))
        Box(Modifier.weight(1f).fillMaxHeight())
    }
}

/** An L-shaped bracket corner made of two arms — reliable across renderers. */
@Composable
private fun BoxScope.CornerMark(alignment: Alignment) {
    val isTop = alignment == Alignment.TopStart || alignment == Alignment.TopEnd
    val isLeft = alignment == Alignment.TopStart || alignment == Alignment.BottomStart
    Box(modifier = Modifier.align(alignment).size(16.dp)) {
        Box(
            modifier = Modifier
                .align(if (isTop) Alignment.TopStart else Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(GuideLime),
        )
        Box(
            modifier = Modifier
                .align(if (isLeft) Alignment.TopStart else Alignment.TopEnd)
                .fillMaxHeight()
                .width(2.dp)
                .background(GuideLime),
        )
    }
}

@Composable
private fun ZoomChip(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .size(if (active) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(Color(0x99141614))
            .then(if (active) Modifier.border(1.8.dp, GuideLime, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) GuideLime else Color(0xBFFFFFFF),
            fontSize = if (active) 11.sp else 10.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
