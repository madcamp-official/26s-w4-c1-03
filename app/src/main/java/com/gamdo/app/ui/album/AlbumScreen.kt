package com.gamdo.app.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium

/** Album (t2 2e skeleton) — 3-column grid; real captures list lands later. */
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    onOpenPhoto: (captureId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal900),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "‹",
                color = OnDarkMedium,
                fontSize = 22.sp,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Text(text = "앨범", color = OnDarkHigh, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items((0 until 12).toList()) { index ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(moodBrush(index))
                        .clickable { onOpenPhoto("demo_$index") },
                )
            }
        }
    }
}
