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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.CardEntry
import com.gamdo.app.data.ProfileEngine
import com.gamdo.app.data.toPresetProfile
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.theme.GamdoType
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.OnAmber
import com.gamdo.app.ui.theme.Amber
import kotlinx.coroutines.launch

// 5, not 3: P1_Plan_1.md §6-2 says "5장 이상", and ProfileEngine derives per-dimension
// confidence from the variance of the picks — three samples cannot make §6-2's criterion
// ("두 카드 세트가 스타일 스트립 상위 순서를 다르게 만듦") hold reliably. Lead ruling,
// .claude/TEAM.md §8. Every UI string interpolates this constant, so nothing says "3장".
private const val MIN_PICKS = 5

/** The design's horizontal margin. 18dp is reserved for strips, which this screen has none of. */
private val SCREEN_MARGIN = 20.dp

/**
 * t2 onboarding: pick preferences → save on-device profile → enter camera.
 * No photo leaves the device while this profile is created.
 *
 * Two steps, 시안 01 and 시안 02, and no third one (D11-3).
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
    // back on step 1 — 내 감도 — with no profile behind it: no palette, no summary, no
    // recommendations, and `onStart` would then write "clean_social" as if that had
    // been the answer. The personalisation evaporated without anything failing.
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

    // The swatches come from the measured colour of the photographs themselves, not
    // from the profile: `colorTemperature` is a point on the orange-to-blue Planckian
    // locus, so a selection of green photographs could only ever average to grey.
    // Reported by the owner on 2026-07-30 and reproduced; see [ProfilePalette].
    //
    // Derived from the saved ids for the same reason `profile` is — a configuration
    // change must not empty the palette on the 내 감도 screen.
    val palette = remember(selectedIds, cards) {
        cards.asSequence()
            .filter { it.feature.id in selectedIds }
            .mapNotNull { entry ->
                val a = entry.colorA ?: return@mapNotNull null
                val b = entry.colorB ?: return@mapNotNull null
                CardTone(brightness = entry.feature.brightness, colorA = a, colorB = b)
            }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let { tones -> ProfilePalette.swatches(tones).map { Color(it) } }
            .orEmpty()
    }

    // Used by 건너뛰기 and by the catalogue-failure step alike: enter the camera on a
    // default style with no measured profile behind it. Deliberately the *same* path in
    // both cases — "the user declined to choose" and "there was nothing to choose from"
    // leave the app in the identical state, so they should not be two pieces of code that
    // can drift apart.
    val startWithoutProfile: () -> Unit = {
        scope.launch {
            container.settingsRepository.saveStylePreference(
                cardIds = emptySet(),
                recommendedPresetId = presets.firstOrNull()?.id ?: "clean_social",
            )
            onFinished()
        }
    }

    when {
        // A parse failure used to leave an empty grid whose button never enables —
        // and onboarding gates the whole app, so that is a permanent dead end on a
        // silent `runCatching`. Say so, and leave a way through.
        cards.isEmpty() || presetProfiles.isEmpty() -> CatalogUnavailableStep(onSkip = startWithoutProfile)

        step == 0 -> PickStep(
            cards = cards,
            // Reopened from 취향 다시 고르기 with the previous picks still ticked — the
            // user came back to *adjust* a selection, and clearing it would make them
            // rebuild from nothing to change one card.
            initiallySelected = selectedIds,
            onSkip = startWithoutProfile,
            onNext = { ids ->
                selectedCsv = ids.joinToString(",")
                step = 1
            },
        )

        else -> SavedStep(
            // 시안 02 shows one sentence, not a bullet list. The words are still the
            // engine's measurement of the user's own picks — [ProfileSentence] only joins
            // them and agrees the particles.
            sentence = ProfileSentence.from(profile?.summary),
            pickCount = selectedIds.size,
            // §6-2: the palette has to come from what the picks actually contained.
            // Constants under 내 감도 are a claim the screen cannot back.
            palette = palette,
            recommendations = profile?.recommendedPresetIds.orEmpty()
                .map { id -> RecommendedPreset(id = id, name = presetNames[id] ?: id) },
            onRepick = { step = 0 },
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

/** One row of 잘 맞는 무드 — the preset's id (for its bundled thumbnail) and its name. */
private data class RecommendedPreset(val id: String, val name: String)

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
        modifier = Modifier.fillMaxSize().background(Ink900),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(modifier = Modifier.padding(horizontal = SCREEN_MARGIN)) {
            Text(
                text = "취향 카드를\n불러오지 못했어요",
                color = TextHi,
                style = GamdoType.Display,
            )
            Text(
                text = "기본 감도로 시작할 수 있어요. 나중에 다시 고를 수 있습니다.",
                color = TextMid,
                style = GamdoType.Body,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Column(modifier = Modifier.padding(horizontal = SCREEN_MARGIN).padding(top = 26.dp)) {
            PrimaryPillButton(text = "기본 감도로 시작하기", onClick = onSkip)
        }
    }
}

/**
 * 시안 01 — 취향 고르기.
 *
 * Selection is a ring and a check badge and nothing else: the design's caption is
 * `선택은 링+체크만, 이미지를 덮지 않음`, so no dimming overlay and no tint. The photo is
 * the thing being judged, and a scrim over it changes the very thing the user is being
 * asked about.
 */
@Composable
private fun PickStep(
    cards: List<CardEntry>,
    initiallySelected: Set<String>,
    onSkip: () -> Unit,
    onNext: (Set<String>) -> Unit,
) {
    // Saved for the same reason the parent's picks are: rotating the phone
    // half-way through choosing photos used to clear every tick silently.
    var selectedCsv by rememberSaveable(initiallySelected) {
        mutableStateOf(initiallySelected.joinToString(","))
    }
    val selected = remember(selectedCsv) { selectedCsv.split(',').filter { it.isNotBlank() }.toSet() }
    val count = selected.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink900),
    ) {
        // 건너뛰기, top-aligned and trailing. A text button with no fill — 시안 01 spends
        // this screen's one amber fill on the CTA at the bottom.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "건너뛰기", color = TextLow, style = GamdoType.Body)
            }
        }

        Column(modifier = Modifier.padding(horizontal = SCREEN_MARGIN)) {
            Text(
                text = "어떤 사진에\n마음이 가나요?",
                color = TextHi,
                style = GamdoType.Display,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "좋아하는 느낌을 ${MIN_PICKS}장 이상 골라주세요",
                    color = TextMid,
                    style = GamdoType.Body,
                )
                // The counter reads against the same constant the CTA counts down from, so
                // the two can never disagree about how many is enough.
                Text(
                    text = "$count / $MIN_PICKS",
                    color = if (count >= MIN_PICKS) Amber else TextLow,
                    style = GamdoType.Body,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = SCREEN_MARGIN, vertical = 16.dp),
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

        Column(
            modifier = Modifier
                .padding(horizontal = SCREEN_MARGIN)
                .padding(top = 8.dp, bottom = 18.dp),
        ) {
            PrimaryPillButton(
                text = if (count >= MIN_PICKS) "다음" else "${MIN_PICKS - count}장 더 골라주세요",
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
            // The ring sits outside a 2dp gap so it reads as a ring rather than as a
            // recolouring of the photograph's own edge. The unselected card carries the
            // same padding, so the image never resizes on selection.
            .then(
                if (selected) {
                    Modifier.border(2.dp, Amber, RoundedCornerShape(14.dp)).padding(2.dp)
                } else {
                    Modifier.padding(2.dp)
                },
            )
            .clip(RoundedCornerShape(14.dp))
            .background(Ink800)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = "file:///android_asset/${card.thumbnail}",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Amber),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✓", color = OnAmber, style = GamdoType.Micro, fontWeight = FontWeight.Black)
            }
        }
    }
}

/**
 * 시안 02 — 내 감도.
 *
 * @param sentence null only when the profile failed to build, in which case the screen
 *   says that instead of describing a preference nobody measured.
 */
@Composable
private fun SavedStep(
    sentence: String?,
    pickCount: Int,
    palette: List<Color>,
    recommendations: List<RecommendedPreset>,
    onRepick: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink900),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = SCREEN_MARGIN),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "내 감도", color = TextHi, style = GamdoType.Title)

            // Five swatches (시안 02), measured from the picks. Empty only when the
            // profile failed to build, in which case the line below already says so —
            // better a missing palette than an invented one.
            Row(
                modifier = Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                palette.forEach { SavedSwatch(it) }
            }

            Text(
                text = sentence ?: "선택한 취향을 정리하지 못했어요.",
                color = TextHi,
                style = GamdoType.Display,
                modifier = Modifier.padding(top = 22.dp),
            )
            Text(
                // The count is the user's actual selection, not [MIN_PICKS] — someone who
                // picked eight should not be told the profile came from five.
                text = "고른 사진 ${pickCount}장으로 만든 감도예요.\n촬영하면서 계속 다듬어집니다.",
                color = TextMid,
                style = GamdoType.Body,
                modifier = Modifier.padding(top = 14.dp),
            )

            if (recommendations.isNotEmpty()) {
                Text(
                    text = "잘 맞는 무드",
                    color = TextHi,
                    style = GamdoType.Cta,
                    modifier = Modifier.padding(top = 26.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    recommendations.forEach { RecommendationThumb(it) }
                }
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = SCREEN_MARGIN)
                .padding(bottom = 14.dp),
        ) {
            PrimaryPillButton(text = "이 감도로 촬영 시작", onClick = onStart)
            // Secondary, and a text button rather than an outlined pill: the screen is
            // allowed one filled amber surface and the CTA above has it, so a second pill
            // of any kind would compete with the action this screen exists to offer.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onRepick)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "취향 다시 고르기", color = TextLow, style = GamdoType.Body)
            }
        }
    }
}

/**
 * One recommended preset: its bundled thumbnail and its name.
 *
 * The image is the preset's own `assets/presets/{id}.jpg` — the same file the camera and
 * 보정 strips show for that style, so the tile here and the tile there are the same
 * picture. No selection state: this is a statement about the profile, not a control.
 */
@Composable
private fun RecommendationThumb(preset: RecommendedPreset) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink800),
        ) {
            AsyncImage(
                model = "file:///android_asset/presets/${preset.id}.jpg",
                contentDescription = preset.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(text = preset.name, color = TextMid, style = GamdoType.Micro)
    }
}

@Composable
private fun SavedSwatch(color: Color) {
    Box(
        modifier = Modifier
            // 44dp circles fit three across with room to spare; five need to be smaller
            // than that at a 20dp margin, and 8dp of gap between them.
            .size(38.dp)
            .clip(CircleShape)
            .background(color),
    )
}
