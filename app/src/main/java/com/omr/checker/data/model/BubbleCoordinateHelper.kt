package com.omr.checker.data.model

data class BubbleLocation(
    val question: Int,
    val option: Char,
    val normCenterX: Float,
    val normCenterY: Float,
    val normRadius: Float
)

object BubbleCoordinateHelper {

    /**
     * Calculates normalized center coordinates and radius for all bubbles in a layout.
     * Normalized coordinates are in range [0.0f .. 1.0f] relative to page width and height.
     */
    fun calculateBubbleLocations(
        layoutConfig: SheetLayoutConfig
    ): List<BubbleLocation> {
        val bubbleLocations = mutableListOf<BubbleLocation>()
        val gridElement = layoutConfig.elements.firstOrNull { it.type == ElementType.BUBBLE_GRID } ?: return emptyList()
        val config = gridElement.gridConfig ?: BubbleGridConfig()

        val elX = gridElement.x
        val elY = gridElement.y
        val elW = gridElement.width
        val elH = gridElement.height

        val totalQuestions = config.endQuestion - config.startQuestion + 1
        val numCols = config.columns.coerceAtLeast(1)
        val questionsPerCol = (totalQuestions + numCols - 1) / numCols

        val colWidth = elW / numCols
        // 20pt header offset normalized to page height (approx 20 / 842 = 0.024f)
        val headerOffsetNorm = 0.024f
        val availableGridHeight = (elH - headerOffsetNorm).coerceAtLeast(0.1f)
        val rowHeight = availableGridHeight / questionsPerCol.coerceAtLeast(1)

        var currentQ = config.startQuestion
        for (colIndex in 0 until numCols) {
            val colStartX = elX + colIndex * colWidth

            for (rowIndex in 0 until questionsPerCol) {
                if (currentQ > config.endQuestion) break

                val rowY = elY + headerOffsetNorm + rowIndex * rowHeight

                // Question number label takes roughly 28pt on A4 (approx 28 / 595 = 0.047f)
                val labelWidthNorm = 0.047f
                val optionsStartX = colStartX + labelWidthNorm
                val availableBubbleWidth = (colWidth - labelWidthNorm - 0.01f).coerceAtLeast(0.05f)
                val bubbleSpacing = availableBubbleWidth / config.options.size
                val bubbleRadius = (bubbleSpacing * 0.32f).coerceAtMost(rowHeight * 0.38f).coerceAtLeast(0.007f)

                for (optIndex in config.options.indices) {
                    val option = config.options[optIndex]
                    val centerX = optionsStartX + optIndex * bubbleSpacing + bubbleSpacing * 0.5f
                    val centerY = rowY + rowHeight * 0.5f

                    bubbleLocations.add(
                        BubbleLocation(
                            question = currentQ,
                            option = option,
                            normCenterX = centerX,
                            normCenterY = centerY,
                            normRadius = bubbleRadius
                        )
                    )
                }

                currentQ++
            }
        }

        return bubbleLocations
    }
}
