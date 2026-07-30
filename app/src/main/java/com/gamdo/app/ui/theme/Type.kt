package com.gamdo.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The redesign's five type roles (Claude Design, 2026-07-30 — step ①).
 *
 * ## Why these and not `Typography`
 *
 * Material's scale has thirteen roles and this app writes `fontSize =` inline at
 * nearly every call site, so replacing [GamdoTypography] alone would change almost
 * nothing visible. These five are what the spec actually names, and a screen adopts
 * the redesign by using them instead of a literal.
 *
 * ## The font is not here yet
 *
 * The spec calls for **Pretendard**. It is not on Google Fonts, so it cannot come
 * through `androidx.compose.ui.text.googlefonts` — it has to be bundled as a font
 * resource, which is a dependency and an APK-size decision, not a token edit. Until
 * that lands these roles carry the platform default and only the *metrics* are
 * right. Adding the family later is one `fontFamily =` line here and nothing at any
 * call site, which is the reason to route screens through this file now rather than
 * after.
 *
 * Sizes, weights and the display tracking are the spec's, verbatim.
 */
object GamdoType {

    /** 24 / 800 / −2% — onboarding headlines ("어떤 사진에 마음이"). */
    val Display = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.02).em,
    )

    /** 18 / 800 — screen titles ("앨범"). */
    val Title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)

    /** 15 / 800 — the single primary action ("이 감도로 촬영 시작"). */
    val Cta = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)

    /** 13 / 400 — body copy, drawn in [TextMid]. */
    val Body = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

    /** 10 / 500 — strip labels and other micro text. */
    val Micro = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
}

/**
 * Material's scale, pointed at [GamdoType] where the two overlap.
 *
 * Only the roles Material components actually reach for are overridden; the rest
 * keep their defaults because nothing in this app renders them.
 */
val GamdoTypography = Typography(
    headlineMedium = GamdoType.Display,
    titleLarge = GamdoType.Title,
    labelLarge = GamdoType.Cta,
    bodyMedium = GamdoType.Body,
    labelSmall = GamdoType.Micro,
)
