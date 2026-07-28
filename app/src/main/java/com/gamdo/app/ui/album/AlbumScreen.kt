package com.gamdo.app.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import java.io.File

/**
 * Album (t2 2e) — loads real captures from the DB (§1-5). Tapping a photo opens
 * the edit/result screen. Falls back to placeholders when empty.
 */
@Composable
fun AlbumScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPhoto: (captureId: String) -> Unit,
) {
    val refreshToken by remember { mutableIntStateOf(0) }
    val captures by produceState(initialValue = emptyList<Captures>(), container, refreshToken) {
        value = container.database.capturesDao().getRecent(60)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal900),
    ) {
        // 2e header: `‹ 앨범`, gap 12dp, 16dp/20dp. The back glyph gets a 44dp touch
        // target through padding rather than a larger box, so it still sits where
        // the design puts it.
        //
        // 2e has `‹ 앨범` and nothing else on this row. A 가져오기 chip used to sit
        // here as §4-3's rescue entry point; §4-3 was cut (O-1) and the owner has
        // decided the album will read the device library directly instead of
        // importing one photo at a time (2026-07-28) — see remain_plan W4-1.
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "‹", color = OnDarkMedium, fontSize = 18.sp)
            }
            Text(text = "앨범", color = OnDarkHigh, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }

        if (captures.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 촬영한 사진이 없어요", color = OnDarkMuted, fontSize = 13.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(captures, key = { it.id }) { capture ->
                    AsyncImage(
                        model = File(capture.filePath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Charcoal700)
                            .clickable { onOpenPhoto(capture.id) },
                    )
                }
            }
        }
    }
}
