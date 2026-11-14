package com.omr.checker.data.model

import java.util.UUID

enum class ElementType {
    TEST_NAME,
    STUDENT_NAME,
    SECTION,
    DATE,
    ANSWER_SHEET_CODE,
    BUBBLE_GRID
}

data class BubbleGridConfig(
    val startQuestion: Int = 1,
    val endQuestion: Int = 60,
    val options: List<Char> = listOf('A', 'B', 'C', 'D'),
    val columns: Int = 3
)

data class SheetElement(
    val id: String = UUID.randomUUID().toString(),
    val type: ElementType,
    val label: String,
    // Normalized coordinates relative to full page (0.0f .. 1.0f)
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val gridConfig: BubbleGridConfig? = null
)

data class SheetLayoutConfig(
    val pageWidth: Int = 2480,   // Standard A4 width at 300 DPI
    val pageHeight: Int = 3508,  // Standard A4 height at 300 DPI
    val cornerAnchorSizeFraction: Float = 0.04f, // 4% of width
    val elements: List<SheetElement> = emptyList()
)
