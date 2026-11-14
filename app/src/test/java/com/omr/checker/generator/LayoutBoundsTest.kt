package com.omr.checker.generator

import com.omr.checker.data.database.DefaultTemplates
import com.omr.checker.data.model.ElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutBoundsTest {

    @Test
    fun testDefaultLayout_AllElementsWithinPageMargins() {
        val layout = DefaultTemplates.createDefault60Layout()

        // Page boundary margins: 0.0 to 1.0
        for (element in layout.elements) {
            assertTrue("Element ${element.label} X is out of bounds: ${element.x}", element.x >= 0.04f)
            assertTrue("Element ${element.label} Y is out of bounds: ${element.y}", element.y >= 0.04f)
            assertTrue(
                "Element ${element.label} exceeds right margin: ${element.x + element.width}",
                element.x + element.width <= 0.96f
            )
            assertTrue(
                "Element ${element.label} exceeds bottom margin: ${element.y + element.height}",
                element.y + element.height <= 0.96f
            )
        }
    }

    @Test
    fun testDefaultLayout_HasBubbleGridWith60Questions() {
        val layout = DefaultTemplates.createDefault60Layout()
        val gridElement = layout.elements.firstOrNull { it.type == ElementType.BUBBLE_GRID }

        assertTrue(gridElement != null)
        val config = gridElement!!.gridConfig
        assertTrue(config != null)
        assertEquals(1, config!!.startQuestion)
        assertEquals(60, config.endQuestion)
        assertEquals(4, config.options.size)
        assertEquals(listOf('A', 'B', 'C', 'D'), config.options)
        assertEquals(2, config.columns)
    }
}
