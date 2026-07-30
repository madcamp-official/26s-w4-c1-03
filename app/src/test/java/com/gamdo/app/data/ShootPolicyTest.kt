package com.gamdo.app.data

import com.gamdo.app.guide.LayoutTemplateCatalog
import com.gamdo.app.guide.SlotRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootPolicyTest {
    @Test
    fun `template snapshot serializes the fixed slots without pose data`() {
        val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.OBJECT_TRIO_TRIANGLE)!!
        val policy = ShootPolicyV2.fromTemplate(template, preferredZoom = 2f)
        val json = policy.toJson().toString()

        assertEquals(3, policy.slots.size)
        assertEquals(2f, policy.preferredZoom)
        assertTrue(policy.slots.all { it.role == ShootSlotRole.OBJECT })
        assertTrue(!json.contains("pose"))
        assertTrue(!json.contains("confidence"))
    }

    @Test
    fun `person slot keeps person role and bracket visual kind`() {
        val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PERSON_UPPER)!!
        val slot = ShootPolicyV2.fromTemplate(template).slots.single()

        assertEquals(ShootSlotRole.PERSON, slot.role)
        assertEquals(ShootVisualKind.PERSON_BRACKET, slot.visualKind)
    }
}
