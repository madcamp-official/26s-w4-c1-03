package com.gamdo.app.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Gradient placeholders standing in for real photo assets (design note: "사진은
 * 전부 무드를 암시하는 그라데이션 플레이스홀더"). Swapped for real images later.
 */
private val moodGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFF3E5A46) to Color(0xFF7D8F6A), // 자연스러운 산책
    Color(0xFF6E5236) to Color(0xFFC9A46A), // 따뜻한 카페
    Color(0xFF5A6B78) to Color(0xFFD8DCE0), // 밝은 리뷰
    Color(0xFF7A5560) to Color(0xFFD9C2B4), // 소프트 필름
    Color(0xFF2B3A4A) to Color(0xFF5A6A78), // 야간
    Color(0xFF4A5240) to Color(0xFF9AA582), // 인물
)

/** A deterministic mood gradient for a given index (wraps around). */
fun moodBrush(index: Int): Brush {
    val (start, end) = moodGradients[((index % moodGradients.size) + moodGradients.size) % moodGradients.size]
    return Brush.linearGradient(listOf(start, end))
}

val moodCount: Int get() = moodGradients.size
