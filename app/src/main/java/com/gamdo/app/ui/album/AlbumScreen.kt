package com.gamdo.app.ui.album

import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.gamdo.app.core.AppPermissions
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.media.AlbumEntry
import com.gamdo.app.data.media.DEFAULT_ALBUM_PAGE_SIZE
import com.gamdo.app.data.media.PhotoPageRequest
import com.gamdo.app.data.media.mergeAlbumEntries
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.TextLow
import java.io.File

/**
 * How many rows before the end of the loaded list a device-photo page load kicks
 * off, so the grid doesn't visibly stall on scroll (three rows at the current
 * `GridCells.Fixed(3)`).
 */
private const val LOAD_MORE_THRESHOLD_ROWS = 3
private const val LOAD_MORE_THRESHOLD_ITEMS = LOAD_MORE_THRESHOLD_ROWS * 3

/**
 * "All of them" for the app-captures side of the merge. Unlike the device photo
 * library, the `captures` table is demo-scale (this app's own session history, not
 * the whole device), so one bounded load stands in for "everything" rather than
 * needing its own pager — see `AlbumEntry.kt`'s note on why a two-source merge only
 * needs *one* side paged.
 */
private const val APP_CAPTURE_LOAD_LIMIT = 500

/**
 * What tapping a device-photo tile hands the caller.
 *
 * As of W3.5-6 the nav host routes this to the 보정 screen on
 * [mediaStoreId] — `Routes.devicePhoto(...)`, which rebuilds the same content Uri
 * with `ContentUris.withAppendedId`. [uri] and [takenAtMillis] are carried for the
 * caller's own use and are not what the route travels on; see `Routes.DEVICE_PHOTO`
 * for why an id beats a URL-encoded Uri in a path argument.
 */
data class DevicePhotoTap(val uri: Uri, val mediaStoreId: Long, val takenAtMillis: Long)

private fun AlbumEntry.DevicePhoto.contentUri(): Uri =
    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)

/**
 * Album (t2 2e) — one grid mixing app captures (from the `captures` table) and
 * device photos (read live from `MediaStore.Images`), ordered by capture time
 * (O-11). Falls back to the existing empty-state text when both sources are empty.
 *
 * Device photos load a page at a time as the grid scrolls (W3.5-2) — the whole
 * device library is never read at once. App captures are loaded in full up front
 * (see [APP_CAPTURE_LOAD_LIMIT]).
 *
 * Deliberately renders both kinds identically — no badge, no separate section, no
 * different corner treatment. O-11's owner note leaves "should the two kinds look
 * different" undecided; this pass does not answer it, it just doesn't force it by
 * inventing a marker. If a future change needs that answer, ask before adding one.
 */
@Composable
fun AlbumScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPhoto: (captureId: String) -> Unit,
    // W3.5-6: wired to `Routes.DEVICE_PHOTO`, which opens the 보정 screen on the
    // MediaStore Uri **without** creating a `captures` row — importing one would put
    // the same photo in this grid twice, which is what W3.5-2's dedup exists to
    // prevent. What that screen may then do to the photo is O-12, decided in
    // `ui/result/ResultFlowDecisions.kt`. Default no-op so a caller that has no
    // destination for it still compiles.
    onOpenDevicePhoto: (DevicePhotoTap) -> Unit = {},
) {
    val context = LocalContext.current

    val captureEntries by produceState(initialValue = emptyList<AlbumEntry.AppCapture>(), container) {
        value = container.database.capturesDao().getRecent(APP_CAPTURE_LOAD_LIMIT).map { capture ->
            AlbumEntry.AppCapture(captureId = capture.id, filePath = capture.filePath, takenAtMillis = capture.createdAt)
        }
    }

    // PermissionGate (ui/GamdoApp.kt) already guarantees at least one media-read
    // permission is granted before this screen is reachable at all — this is a
    // defensive re-check, not the primary gate, so a query is never attempted on a
    // grant this classifies as NONE (which "should" be unreachable here, but a
    // silent MediaStore SecurityException is a worse failure mode than a skipped
    // query). PARTIAL needs no special-casing beyond this: the OS already scopes
    // the MediaStore query results to the user's selection when only the partial
    // permission is held, so `DevicePhotoRepository`'s query is identical either
    // way. This device is API 31, so the PARTIAL branch (API 34+) is untested here.
    val accessLevel = remember(container) {
        val granted = AppPermissions.mediaReadAlternatives().filterTo(mutableSetOf()) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        AppPermissions.PhotoAccessLevel.of(Build.VERSION.SDK_INT, granted)
    }

    val devicePhotoEntries = remember { mutableStateListOf<AlbumEntry.DevicePhoto>() }
    var nextPageRequest by remember { mutableStateOf(PhotoPageRequest.first(DEFAULT_ALBUM_PAGE_SIZE)) }
    var hasMoreDevicePhotos by remember { mutableStateOf(accessLevel != AppPermissions.PhotoAccessLevel.NONE) }
    var isLoadingDevicePhotos by remember { mutableStateOf(false) }

    suspend fun loadMoreDevicePhotos() {
        if (isLoadingDevicePhotos || !hasMoreDevicePhotos) return
        isLoadingDevicePhotos = true
        val page = container.devicePhotoRepository.loadPage(nextPageRequest)
        devicePhotoEntries.addAll(page.entries)
        hasMoreDevicePhotos = page.hasMore
        nextPageRequest = nextPageRequest.next()
        isLoadingDevicePhotos = false
    }

    // Kicks off the first page. Later pages are triggered by `itemsIndexed` below,
    // which needs a non-empty list to have a "last item" to hang a LaunchedEffect
    // off of.
    LaunchedEffect(accessLevel) { loadMoreDevicePhotos() }

    val merged = mergeAlbumEntries(captureEntries, devicePhotoEntries)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink900),
    ) {
        // 2e header: `‹ 앨범`, gap 12dp, 16dp/20dp. The back glyph gets a 44dp touch
        // target through padding rather than a larger box, so it still sits where
        // the design puts it.
        //
        // 2e has `‹ 앨범` and nothing else on this row. A 가져오기 chip used to sit
        // here as §4-3's rescue entry point; §4-3 was cut (O-1) and the owner
        // decided the album would read the device library directly instead of
        // importing one photo at a time (2026-07-28/29) — see remain_plan W3.5.
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
                Text(text = "‹", color = TextMid, fontSize = 18.sp)
            }
            Text(text = "앨범", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }

        if (merged.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 촬영한 사진이 없어요", color = TextLow, fontSize = 13.sp)
            }
        } else {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(
                    merged,
                    key = { _, entry ->
                        when (entry) {
                            is AlbumEntry.AppCapture -> "cap:${entry.captureId}"
                            is AlbumEntry.DevicePhoto -> "dev:${entry.mediaStoreId}"
                        }
                    },
                ) { index, entry ->
                    // Fires once per distinct entry that becomes the load-more
                    // trigger point — re-fires on the *new* trigger item once a
                    // page lands and the list grows, not on every recomposition.
                    if (index >= merged.size - LOAD_MORE_THRESHOLD_ITEMS) {
                        LaunchedEffect(entry) { loadMoreDevicePhotos() }
                    }
                    // A fixed-size outer Box, not `AsyncImage(Modifier.aspectRatio(1f)...)`
                    // directly: this grid's main (vertical) axis constraint is unbounded
                    // (standard for any lazy layout — items pick their own height), and
                    // Compose does not clip a child's drawing to its measured bounds
                    // unless something in *that node's own chain* clips it. Deriving the
                    // square from a Box and then telling AsyncImage to `matchParentSize()`
                    // removes any dependency on how Coil's own intrinsic-size handling
                    // races against `aspectRatio` for a given model type — App captures
                    // (`File`) and device photos (`content://` `Uri`, mostly very tall
                    // screenshots on the test device) go through different Coil
                    // fetchers/decoders, and only one of those two shapes exposed the gap.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink800)
                            .clickable {
                                when (entry) {
                                    is AlbumEntry.AppCapture -> onOpenPhoto(entry.captureId)
                                    is AlbumEntry.DevicePhoto -> onOpenDevicePhoto(
                                        DevicePhotoTap(
                                            uri = entry.contentUri(),
                                            mediaStoreId = entry.mediaStoreId,
                                            takenAtMillis = entry.takenAtMillis,
                                        ),
                                    )
                                }
                            },
                    ) {
                        AsyncImage(
                            model = when (entry) {
                                is AlbumEntry.AppCapture -> File(entry.filePath)
                                is AlbumEntry.DevicePhoto -> entry.contentUri()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
        }
    }
}
