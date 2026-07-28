package com.gamdo.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.CardEntry
import com.gamdo.app.data.ProfileEngine
import com.gamdo.app.data.StyleProfileResult
import com.gamdo.app.data.toPresetProfile
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage
import kotlinx.coroutines.launch

// 5, not 3: P1_Plan_1.md §6-2 says "5장 이상", and ProfileEngine derives per-dimension
// confidence from the variance of the picks — three samples cannot make §6-2's criterion
// ("두 카드 세트가 스타일 스트립 상위 순서를 다르게 만듦") hold reliably. Lead ruling,
// .claude/TEAM.md §8. Every UI string interpolates this constant, so nothing says "3장".
private const val MIN_PICKS = 5

/**
 * t2 onboarding: pick preferences → save on-device profile → enter camera.
 * No photo leaves the device while this profile is created.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Single reader of assets/cards.json — CardRepository, not a second inline parser
    // (was a duplicate CardCatalog/OnboardingCard model here that could drift from
    // CardRepositoryTest's contract without anything noticing).
    val cards = remember {
        runCatching { container.cardRepository.loadBundledCardEntries() }.getOrDefault(emptyList())
    }
    val presets = remember {
        runCatching { container.presetRepository.loadBundledPresets() }.getOrDefault(emptyList())
    }
    val presetNames = remember(presets) { presets.associate { it.id to it.displayName } }
    val presetProfiles = remember(presets) { presets.map { it.toPresetProfile() } }
    var step by rememberSaveable { mutableStateOf(0) }

    // Saved, and stored the way `app_settings.selected_card_ids` already stores it.
    //
    // `step` was already rememberSaveable while the picks and the profile were plain
    // `remember`. After a configuration change or a process death the screen came
    // back on step 1 — "당신의 감도를 저장했어요" — with no profile behind it: no
    // palette, no bullets, no recommendations, and `onStart` would then write
    // "clean_social" as if that had been the answer. The personalisation evaporated
    // without anything failing.
    var selectedCsv by rememberSaveable { mutableStateOf("") }
    val selectedIds = remember(selectedCsv) {
        selectedCsv.split(',').filter { it.isNotBlank() }.toSet()
    }

    // Derived rather than held. Rebuilding it from the saved ids is what makes the
    // restore correct; keeping it in a `remember` is what made it disappear.
    val profile = remember(selectedIds, cards, presetProfiles) {
        if (selectedIds.isEmpty() || cards.isEmpty() || presetProfiles.isEmpty()) {
            null
        } else {
            runCatching {
                ProfileEngine.build(
                    cards.filter { it.feature.id in selectedIds }.map { it.feature },
                    presetProfiles,
                )
            }.getOrNull()
        }
    }

    when {
        // A parse failure used to leave an empty grid whose button never enables —
        // and onboarding gates the whole app, so that is a permanent dead end on a
        // silent `runCatching`. Say so, and leave a way through.
        cards.isEmpty() || presetProfiles.isEmpty() -> CatalogUnavailableStep(
            onSkip = {
                scope.launch {
                    container.settingsRepository.saveStylePreference(
                        cardIds = emptySet(),
                        recommendedPresetId = presets.firstOrNull()?.id ?: "clean_social",
                    )
                    onFinished()
                }
            },
        )

        step == 0 -> PickStep(cards = cards, onNext = { ids ->
            selectedCsv = ids.joinToString(",")
            step = 1
        })

        else -> SavedStep(
            summary = profile?.summary,
            // §6-2: the palette has to come from the profile the picks produced.
            // Three constants under "당신의 감도" is a claim the screen cannot back.
            palette = profile?.color?.let { c ->
                ProfilePalette.swatches(
                    brightness = c["brightness"]?.mean ?: 0.5f,
                    colorTemperatureK = c["colorTemperature"]?.mean ?: 5500f,
                    saturation = c["saturation"]?.mean ?: 0.4f,
                ).map { Color(it) }
            }.orEmpty(),
            recommendations = profile?.recommendedPresetIds.orEmpty().map { presetNames[it] ?: it },
            onStart = {
                scope.launch {
                    profile?.let { container.profileRepository.saveInitialProfile(selectedIds, it) }
                    container.settingsRepository.saveStylePreference(
                        cardIds = selectedIds,
                        recommendedPresetId = profile?.recommendedPresetIds?.firstOrNull()
                            ?: "clean_social",
                        recommendedPresetIds = profile?.recommendedPresetIds.orEmpty(),
                    )
                    onFinished()
                }
            },
        )
    }
}

/**
 * Shown when `cards.json` or `presets.json` could not be read.
 *
 * Onboarding is the only route to the camera, so a failure here has to leave a way
 * out rather than an empty grid. The photo picking is skipped and a default style
 * is written — stated plainly, because the app really will behave differently.
 */
@Composable
private fun CatalogUnavailableStep(onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Charcoal900),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(modifier = Modifier.padding(horizontal = 26.dp)) {
            Text(
                text = "취향 카드를\n불러오지 못했어요",
                color = OnDarkHigh,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 31.sp,
            )
            Text(
                text = "기본 스타일로 시작할 수 있어요. 나중에 다시 설정할 수 있습니다.",
                color = OnDarkMuted,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 26.dp).padding(top = 26.dp)) {
            PrimaryPillButton(text = "기본 스타일로 시작하기", onClick = onSkip)
        }
    }
}

@Composable
private fun PickStep(cards: List<CardEntry>, onNext: (Set<String>) -> Unit) {
    // Saved for the same reason the parent's picks are: rotating the phone
    // half-way through choosing photos used to clear every tick silently.
    var selectedCsv by rememberSaveable { mutableStateOf("") }
    val selected = remember(selectedCsv) { selectedCsv.split(',').filter { it.isNotBlank() }.toSet() }
    val count = selected.size

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
            items(cards, key = { it.feature.id }) { card ->
                val isSelected = card.feature.id in selected
                PickCard(
                    card = card,
                    selected = isSelected,
                    onToggle = {
                        selectedCsv = selected.toMutableSet()
                            .apply { if (isSelected) remove(card.feature.id) else add(card.feature.id) }
                            .joinToString(",")
                    },
                )
            }
        }

        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 18.dp)) {
            PrimaryPillButton(
                text = if (count >= MIN_PICKS) "다음" else "${MIN_PICKS - count}장 더 골라 주세요",
                enabled = count >= MIN_PICKS,
                onClick = { onNext(selected) },
            )
        }
    }
}

@Composable
private fun PickCard(card: CardEntry, selected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(Charcoal900)
            .then(if (selected) Modifier.border(2.5.dp, Sage, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = "file:///android_asset/${card.thumbnail}",
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
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
private fun SavedStep(
    summary: String?,
    palette: List<Color>,
    recommendations: List<String>,
    onStart: () -> Unit,
) {
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
                // Empty only when the profile failed to build, in which case the
                // bullets below already say so — better a missing palette than an
                // invented one.
                palette.forEach { SavedSwatch(it) }
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
                (summary?.split(", ") ?: listOf("선택한 취향을 정리하고 있어요")).forEach { bullet ->
                    SavedBullet(bullet)
                }
                if (recommendations.isNotEmpty()) {
                    SavedBullet("추천: ${recommendations.joinToString(" · ")}")
                }
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
