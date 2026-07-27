package com.gamdo.app.ui.album

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import java.io.File
import kotlinx.coroutines.launch

private const val TAG = "AlbumScreen"

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
    var refreshToken by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            importing = true
            importError = null
            scope.launch {
                // `onSuccess` alone left every failure completely silent: an
                // unreadable URI, a revoked grant or a full disk all produced a
                // picker that closed and an album that did not change, with nothing
                // to tell the user whether the photo had been imported.
                runCatching { container.captureRepository.importGalleryPhoto(uri) }
                    .onSuccess { refreshToken++ }
                    .onFailure {
                        Log.w(TAG, "gallery import failed", it)
                        importError = "사진을 가져오지 못했어요"
                    }
                importing = false
            }
        }
    }
    val captures by produceState(initialValue = emptyList<Captures>(), container, refreshToken) {
        value = container.database.capturesDao().getRecent(60)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal900),
    ) {
        // Touch targets, not glyphs. `clickable` on the Text alone gave "‹" and
        // "가져오기" hit areas the size of their own type — well under the 48dp
        // minimum — so both were easy to miss and neither showed a press. The
        // spacer also pushes 가져오기 to the far edge: at spacedBy(12.dp) it sat
        // against the title and read as a subtitle rather than an action.
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "‹", color = OnDarkMedium, fontSize = 22.sp)
            }
            Text(
                text = "앨범",
                color = OnDarkHigh,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(enabled = !importing) {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (importing) "가져오는 중…" else "가져오기",
                    color = if (importing) OnDarkMuted else OnDarkMedium,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        importError?.let { message ->
            Text(
                text = message,
                color = OnDarkMedium,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
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
