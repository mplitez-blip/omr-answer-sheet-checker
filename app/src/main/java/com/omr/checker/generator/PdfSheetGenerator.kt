package com.omr.checker.generator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.omr.checker.data.model.ElementType
import com.omr.checker.data.model.SheetElement
import com.omr.checker.data.model.SheetLayoutConfig
import java.io.File
import java.io.FileOutputStream

object PdfSheetGenerator {

    // Standard A4 dimensions in PostScript points (72 points per inch)
    const val PAGE_WIDTH_PT = 595
    const val PAGE_HEIGHT_PT = 842

    /**
     * Generates an A4 PDF document for the given layout config.
     * @param context Application context
     * @param layoutConfig The template element coordinates and dimensions
     * @param sheetTitle Title printed on sheet
     * @param sheetCode Optional code printed on sheet (e.g. "EXAM-60-A")
     * @param outputFile Target file where PDF should be saved
     */
    fun generatePdf(
        context: Context,
        layoutConfig: SheetLayoutConfig,
        sheetTitle: String = "OMR ANSWER SHEET",
        sheetCode: String = "DEF-60",
        outputFile: File
    ): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        drawSheet(canvas, PAGE_WIDTH_PT.toFloat(), PAGE_HEIGHT_PT.toFloat(), layoutConfig, sheetTitle, sheetCode)

        document.finishPage(page)

        FileOutputStream(outputFile).use { out ->
            document.writeTo(out)
        }
        document.close()

        return outputFile
    }

    /**
     * Core drawing method used for both vector PDF canvas and bitmap preview rendering.
     */
    fun drawSheet(
        canvas: Canvas,
        width: Float,
        height: Float,
        layoutConfig: SheetLayoutConfig,
        sheetTitle: String,
        sheetCode: String
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Fill background with crisp white
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width, height, paint)

        // 2. Draw 4 Corner Fiducial Registration Anchors (Solid Black Squares)
        // These are essential for the OpenCV perspective transform to deskew the sheet.
        val anchorSize = width * layoutConfig.cornerAnchorSizeFraction.coerceAtLeast(0.035f)
        val margin = width * 0.04f

        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL

        // Top-Left Anchor
        canvas.drawRect(margin, margin, margin + anchorSize, margin + anchorSize, paint)
        // Top-Right Anchor
        canvas.drawRect(width - margin - anchorSize, margin, width - margin, margin + anchorSize, paint)
        // Bottom-Left Anchor
        canvas.drawRect(margin, height - margin - anchorSize, margin + anchorSize, height - margin, paint)
        // Bottom-Right Anchor
        canvas.drawRect(width - margin - anchorSize, height - margin - anchorSize, width - margin, height - margin, paint)

        // 3. Draw Outer Alignment Boundary (Thin solid black framing line)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.0f
        canvas.drawRect(margin + anchorSize * 0.5f, margin + anchorSize * 0.5f,
            width - (margin + anchorSize * 0.5f), height - (margin + anchorSize * 0.5f), paint)

        // 4. Instructions / OMR Filling Guide Banner
        drawInstructionBanner(canvas, width, margin + anchorSize + 6f)

        // 5. Draw Layout Elements
        for (element in layoutConfig.elements) {
            val elX = element.x * width
            val elY = element.y * height
            val elW = element.width * width
            val elH = element.height * height

            when (element.type) {
                ElementType.TEST_NAME -> {
                    drawFieldBox(canvas, elX, elY, elW, elH, element.label, sheetTitle)
                }
                ElementType.ANSWER_SHEET_CODE -> {
                    drawFieldBox(canvas, elX, elY, elW, elH, element.label, sheetCode, isBold = true)
                }
                ElementType.STUDENT_NAME -> {
                    drawFieldBox(canvas, elX, elY, elW, elH, element.label, "")
                }
                ElementType.DATE -> {
                    drawFieldBox(canvas, elX, elY, elW, elH, element.label, "")
                }
                ElementType.SECTION -> {
                    drawFieldBox(canvas, elX, elY, elW, elH, element.label, "")
                }
                ElementType.BUBBLE_GRID -> {
                    val config = element.gridConfig ?: com.omr.checker.data.model.BubbleGridConfig()
                    drawBubbleGrid(canvas, elX, elY, elW, elH, config)
                }
            }
        }
    }

    private fun drawInstructionBanner(canvas: Canvas, pageWidth: Float, topY: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 7.5f
        paint.color = Color.DKGRAY

        val text = "INSTRUCTIONS: Use dark pencil or black pen. Fill bubbles completely. Do not fold or smudge."
        val textWidth = paint.measureText(text)
        canvas.drawText(text, (pageWidth - textWidth) / 2f, topY, paint)
    }

    private fun drawFieldBox(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        label: String,
        value: String,
        isBold: Boolean = false
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Label above / inside
        paint.color = Color.BLACK
        paint.textSize = 8.5f
        paint.isFakeBoldText = true
        canvas.drawText(label, x + 3f, y + 10f, paint)

        // Value or text line box
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        paint.color = Color.GRAY
        val boxRect = RectF(x, y + 13f, x + w, y + h)
        canvas.drawRoundRect(boxRect, 2f, 2f, paint)

        if (value.isNotBlank()) {
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.textSize = if (isBold) 11f else 9.5f
            paint.isFakeBoldText = isBold
            canvas.drawText(value, x + 6f, y + 13f + (h - 13f) * 0.65f, paint)
        }
    }

    private fun drawBubbleGrid(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        config: com.omr.checker.data.model.BubbleGridConfig
    ) {
        val totalQuestions = config.endQuestion - config.startQuestion + 1
        val numCols = config.columns.coerceAtLeast(1)
        val questionsPerCol = (totalQuestions + numCols - 1) / numCols

        val colWidth = w / numCols
        val rowHeight = (h - 20f) / questionsPerCol.coerceAtLeast(1)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Column Titles
        paint.textSize = 9f
        paint.isFakeBoldText = true
        paint.color = Color.BLACK

        for (colIndex in 0 until numCols) {
            val colStartX = x + colIndex * colWidth
            val startQ = config.startQuestion + colIndex * questionsPerCol
            val endQ = (startQ + questionsPerCol - 1).coerceAtMost(config.endQuestion)

            if (startQ <= config.endQuestion) {
                // Column separator box / header
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.5f
                paint.color = Color.LTGRAY
                canvas.drawRect(colStartX + 2f, y, colStartX + colWidth - 2f, y + h, paint)

                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                canvas.drawText("Questions $startQ - $endQ", colStartX + 8f, y + 12f, paint)
            }
        }

        // Draw each question row and its bubbles
        var currentQ = config.startQuestion
        for (colIndex in 0 until numCols) {
            val colStartX = x + colIndex * colWidth

            for (rowIndex in 0 until questionsPerCol) {
                if (currentQ > config.endQuestion) break

                val rowY = y + 20f + rowIndex * rowHeight

                // Question Number
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                paint.textSize = 8.5f
                paint.isFakeBoldText = true
                val qStr = String.format("%2d.", currentQ)
                canvas.drawText(qStr, colStartX + 6f, rowY + rowHeight * 0.65f, paint)

                // Option Bubbles (A, B, C, D)
                val optionsStartX = colStartX + 28f
                val availableBubbleWidth = colWidth - 34f
                val bubbleSpacing = availableBubbleWidth / config.options.size
                val bubbleRadius = (bubbleSpacing * 0.32f).coerceAtMost(rowHeight * 0.38f).coerceAtLeast(5.5f)

                for (optIndex in config.options.indices) {
                    val option = config.options[optIndex]
                    val bubbleCenterX = optionsStartX + optIndex * bubbleSpacing + bubbleSpacing * 0.5f
                    val bubbleCenterY = rowY + rowHeight * 0.5f

                    // Draw outer bubble circle
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.0f
                    paint.color = Color.BLACK
                    canvas.drawCircle(bubbleCenterX, bubbleCenterY, bubbleRadius, paint)

                    // Draw option letter inside bubble (e.g. A, B, C, D)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.DKGRAY
                    paint.textSize = bubbleRadius * 1.25f
                    paint.isFakeBoldText = false
                    val letterWidth = paint.measureText(option.toString())
                    canvas.drawText(
                        option.toString(),
                        bubbleCenterX - letterWidth / 2f,
                        bubbleCenterY + bubbleRadius * 0.45f,
                        paint
                    )
                }

                currentQ++
            }
        }
    }
}
