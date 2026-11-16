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
        assertEquals(3, config.columns)
    }

    @Test
    fun testDefaultLayout_FiducialAnchorOffsets_DoNotOverlapElements() {
        val layout = DefaultTemplates.createDefault60Layout()
        val anchorFraction = layout.cornerAnchorSizeFraction.coerceAtLeast(0.035f)
        val marginX = 0.04f
        val anchorSizeX = anchorFraction

        // On A4 paper (aspect ratio ~ 595 / 842 = 0.7067), Y margins and sizes in page points scale with width
        val a4Aspect = 595f / 842f
        val marginY = marginX * a4Aspect
        val anchorSizeY = anchorSizeX * a4Aspect

        // Each anchor is located within [margin .. margin + anchorSize] in normalized coordinates
        val anchorBoxTopLeft = Rect(marginX, marginY, marginX + anchorSizeX, marginY + anchorSizeY)
        val anchorBoxTopRight = Rect(1f - marginX - anchorSizeX, marginY, 1f - marginX, marginY + anchorSizeY)
        val anchorBoxBottomLeft = Rect(marginX, 1f - marginY - anchorSizeY, marginX + anchorSizeX, 1f - marginY)
        val anchorBoxBottomRight = Rect(1f - marginX - anchorSizeX, 1f - marginY - anchorSizeY, 1f - marginX, 1f - marginY)

        val anchorBoxes = listOf(anchorBoxTopLeft, anchorBoxTopRight, anchorBoxBottomLeft, anchorBoxBottomRight)

        for (element in layout.elements) {
            val elRect = Rect(element.x, element.y, element.x + element.width, element.y + element.height)
            for (anchor in anchorBoxes) {
                val overlaps = !(elRect.right <= anchor.left || elRect.left >= anchor.right ||
                        elRect.bottom <= anchor.top || elRect.top >= anchor.bottom)
                assertTrue("Element ${element.label} overlaps with anchor box", !overlaps)
            }
        }
    }

    private data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
