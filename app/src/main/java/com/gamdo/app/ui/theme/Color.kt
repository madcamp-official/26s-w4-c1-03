package com.gamdo.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Owner's final UI redesign (Claude Design, 2026-07-30) — "조용한 디렉터".
 *
 * **This file is the design's step ①, and the design supersedes the earlier design
 * documents and contracts** (owner: "다른 문서들이나 계약사항보다 이 디자인이 최종").
 * Where D10/D11 or `감도_GAMDO_디자인_참고서` say something else, this wins.
 *
 * Two things changed and one did not.
 *
 *  - **The accent is amber, not sage.** The old accent was a pale green, and green
 *    is a colour photographs contain. A cue meaning "지금이야" has to stay legible on
 *    the photo it sits on without competing with it, and a warm amber does that
 *    where a green can land on foliage and disappear.
 *  - **The neutrals lost their green tint.** They were warm/green-tinted charcoal
 *    (`0xFF0C0D0B` and friends); they are true neutral ink now, so nothing but the
 *    accent carries hue.
 *  - Dark-first is unchanged. There is still no light scheme (D10-1).
 *
 * ## The amber rule
 *
 * From the spec, and the part most easily lost: amber is for **selection rings,
 * composition match, the single CTA, and dials holding a value** — nothing else.
 * **At most one filled amber surface per screen.** Controls sitting on a photograph
 * are ghost (no background) or [Scrim] with a blur; they never take an amber fill,
 * because the photograph is the subject and a fill would outrank it.
 */

// ---- ink surfaces ------------------------------------------------------------

/** Camera and result backgrounds — the deepest surface. Was `Charcoal950`. */
val Ink950 = Color(0xFF0A0A0B)

/** Ordinary screen background. Was `Charcoal900`. */
val Ink900 = Color(0xFF111113)

/**
 * Cards and disabled CTAs. Was `Charcoal700` — and `Charcoal800`, which the new
 * four-step ramp folds into this one.
 */
val Ink800 = Color(0xFF1A1A1D)

/** Selected chips and pressed states. Was `Charcoal600`. */
val Ink700 = Color(0xFF232327)

/** Hairlines — White at 8% over [Ink900]. Was `OutlineDim`. */
val Outline = Color(0xFF2A2A2F)

// ---- the one accent ----------------------------------------------------------

/**
 * The only accent colour in the app. Was `Sage`, `SageButton` and `SageDim` — three
 * greens for filled, outlined and dimmed states, now one amber used at different
 * opacities. Three tokens for one job is how a "single accent" stops being single.
 */
val Amber = Color(0xFFE8C38B)

/** Text and glyphs on an [Amber] fill. Was `OnSage`. */
val OnAmber = Color(0xFF211708)

// ---- text --------------------------------------------------------------------

/** Primary text, and the shutter ring. Was `OnDarkHigh`. */
val TextHi = Color(0xFFF4F1EA)

/** Secondary text and unselected strip labels. Was `OnDarkMedium`. */
val TextMid = Color(0xFFA09E97)

/** Disabled text and micro labels. Was `OnDarkMuted`. */
val TextLow = Color(0xFF6E6C66)

// ---- overlay -----------------------------------------------------------------

/**
 * Bottom sheets and modal scrims: [Ink950] at 55%.
 *
 * Not a second accent and not a new hue — it is the deepest surface made
 * translucent, so it reads as "further back" rather than as a colour. The spec puts
 * controls-over-photo in the 45–62% band; 55% is the middle of it, and the device
 * pass settles the exact figure (spec step ④).
 */
val Scrim = Color(0x8C0A0A0B)
