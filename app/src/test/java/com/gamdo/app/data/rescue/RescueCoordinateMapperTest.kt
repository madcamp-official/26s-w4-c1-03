package com.gamdo.app.data.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RescueCoordinateMapperTest {
    @Test
    fun ignores_letterbox_and_maps_points_in_fit_rect() {
        val rect = RescueImageRect(left = 100f, top = 50f, width = 800f, height = 400f)
        assertNull(RescueCoordinateMapper.viewToImage(RescuePoint(20f, 100f), rect))
        assertEquals(RescuePoint(0.5f, 0.5f), RescueCoordinateMapper.viewToImage(RescuePoint(500f, 250f), rect))
    }

    @Test
    fun fit_rect_centers_image_without_distorting_aspect_ratio() {
        val rect = RescueCoordinateMapper.fitRect(1000f, 1000f, 1600, 800)
        assertEquals(1000f, rect.width)
        assertEquals(500f, rect.height)
        assertEquals(0f, rect.left)
        assertEquals(250f, rect.top)
    }
}
