package com.gamdo.app.data

import com.gamdo.app.guide.LayoutSlot
import com.gamdo.app.guide.LayoutTemplate
import com.gamdo.app.guide.SlotRole
import com.gamdo.app.guide.SlotVisualKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Versioned, fixed-layout snapshot shared by the Android owner and QR web. */
@Serializable
data class ShootPolicyV2(
    val version: Int = 2,
    val layoutId: String,
    val slots: List<ShootSlotV2>,
    val preferredZoom: Float? = null,
    val recommendedPhotos: Int = 3,
) {
    init {
        require(layoutId.isNotBlank())
        require(slots.size in 1..4)
        require(slots.map { it.id }.distinct().size == slots.size)
        require(recommendedPhotos in 1..3)
    }

    fun toJson(json: Json = Json { encodeDefaults = false }): JsonObject =
        json.encodeToJsonElement(this) as JsonObject

    companion object {
        fun fromTemplate(template: LayoutTemplate, preferredZoom: Float? = null): ShootPolicyV2 =
            ShootPolicyV2(
                layoutId = template.id,
                slots = template.slots.map { it.toShootSlot() },
                preferredZoom = preferredZoom?.coerceIn(0.5f, 10f),
            )
    }
}

@Serializable
data class ShootSlotV2(
    val id: String,
    val role: ShootSlotRole,
    val visualKind: ShootVisualKind,
    val bounds: ShootRectN,
    val preferredAspectRatio: Float,
)

@Serializable
enum class ShootSlotRole { PERSON, OBJECT }

@Serializable
enum class ShootVisualKind { PERSON_SILHOUETTE, PERSON_BRACKET, GENERIC_OBJECT, CUP, PLATE }

@Serializable
data class ShootRectN(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun LayoutSlot.toShootSlot(): ShootSlotV2 = ShootSlotV2(
    id = id,
    role = if (role == SlotRole.PERSON) ShootSlotRole.PERSON else ShootSlotRole.OBJECT,
    visualKind = when (visualKind) {
        SlotVisualKind.PERSON_SILHOUETTE -> ShootVisualKind.PERSON_SILHOUETTE
        SlotVisualKind.PERSON_BRACKET -> ShootVisualKind.PERSON_BRACKET
        SlotVisualKind.CUP -> ShootVisualKind.CUP
        SlotVisualKind.PLATE -> ShootVisualKind.PLATE
        SlotVisualKind.GENERIC_OBJECT -> ShootVisualKind.GENERIC_OBJECT
    },
    bounds = ShootRectN(bounds.left, bounds.top, bounds.right, bounds.bottom),
    preferredAspectRatio = preferredAspectRatio.coerceIn(0.4f, 2.5f),
)
