package com.omr.checker.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.model.BubbleCoordinateHelper
import com.omr.checker.data.model.BubbleGridConfig
import com.omr.checker.data.model.BubbleLocation
import com.omr.checker.data.model.ElementType
import com.omr.checker.data.model.SheetLayoutConfig
import com.omr.checker.domain.ScoringEngine
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

data class CornerDetectionResult(
    val corners: List<Point>?,
    val isDetected: Boolean
)

data class OmrScanOutput(
    val rawAnswers: Map<Int, Char?>,
    val ambiguousQuestions: List<Int>,
    val rectifiedBitmap: Bitmap,
    val detectedCorners: List<Point>?,
    val confidence: Float
)

object OmrEngine {

    private const val WARPED_WIDTH = 1240.0
    private const val WARPED_HEIGHT = 1754.0

    private const val FILL_THRESHOLD = 0.32f
    private const val BLANK_THRESHOLD = 0.22f
    private const val AMBIGUITY_DELTA = 0.10f

    /**
     * Processes an image bitmap of a sheet, detects the document boundary,
     * warps perspective, and extracts bubble responses.
     */
    fun processAnswerSheet(
        bitmap: Bitmap,
        layoutConfig: SheetLayoutConfig
    ): OmrScanOutput {
        val srcRgba = Mat()
        Utils.bitmapToMat(bitmap, srcRgba)

        val srcGray = Mat()
        Imgproc.cvtColor(srcRgba, srcGray, Imgproc.COLOR_RGBA2GRAY)

        // 1. Detect 4 Corners of Sheet
        val corners = detectSheetCorners(srcGray)

        val warpedGray = Mat()
        if (corners != null && corners.size == 4) {
            val srcCorners = MatOfPoint2f(*corners.toTypedArray())
            val dstCorners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(WARPED_WIDTH, 0.0),
                Point(WARPED_WIDTH, WARPED_HEIGHT),
                Point(0.0, WARPED_HEIGHT)
            )

            val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
            Imgproc.warpPerspective(srcGray, warpedGray, transform, Size(WARPED_WIDTH, WARPED_HEIGHT))
            transform.release()
            srcCorners.release()
            dstCorners.release()
        } else {
            // Direct resize fallback if camera is already closely framing the sheet
            Imgproc.resize(srcGray, warpedGray, Size(WARPED_WIDTH, WARPED_HEIGHT))
        }

        // 2. Binary Thresholding for Optical Mark Recognition
        val binary = Mat()
        Imgproc.GaussianBlur(warpedGray, binary, Size(3.0, 3.0), 0.0)
        Imgproc.threshold(binary, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        // 3. Extract Bubble Fill Ratios
        val bubbleLocations = BubbleCoordinateHelper.calculateBubbleLocations(layoutConfig)
        val groupedByQuestion = bubbleLocations.groupBy { it.question }

        val rawAnswers = mutableMapOf<Int, Char?>()
        val ambiguousQuestions = mutableListOf<Int>()
        var validAnswerCount = 0

        for ((question, bubbles) in groupedByQuestion) {
            val bubbleFills = bubbles.map { bubble ->
                val fillRatio = calculateBubbleFillRatio(binary, bubble, WARPED_WIDTH.toFloat(), WARPED_HEIGHT.toFloat())
                Pair(bubble.option, fillRatio)
            }.sortedByDescending { it.second }

            val topOption = bubbleFills.firstOrNull()
            val secondOption = bubbleFills.getOrNull(1)

            val topFill = topOption?.second ?: 0f
            val secondFill = secondOption?.second ?: 0f

            when {
                topFill >= FILL_THRESHOLD && (topFill - secondFill) >= AMBIGUITY_DELTA -> {
                    rawAnswers[question] = topOption?.first
                    validAnswerCount++
                }
                topFill >= FILL_THRESHOLD && secondFill >= (FILL_THRESHOLD - 0.05f) -> {
                    // Double mark detected
                    rawAnswers[question] = null
                    ambiguousQuestions.add(question)
                }
                topFill < BLANK_THRESHOLD -> {
                    // Blank / unshaded
                    rawAnswers[question] = null
                }
                else -> {
                    // Weak / partial shade
                    if (topFill >= 0.28f && (topFill - secondFill) >= 0.08f) {
                        rawAnswers[question] = topOption?.first
                        validAnswerCount++
                    } else {
                        rawAnswers[question] = null
                    }
                }
            }
        }

        // 4. Create Rectified Bitmap for Visual Audit
        val rectifiedBitmap = Bitmap.createBitmap(WARPED_WIDTH.toInt(), WARPED_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        val rectifiedColor = Mat()
        Imgproc.cvtColor(warpedGray, rectifiedColor, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rectifiedColor, rectifiedBitmap)

        val confidence = if (groupedByQuestion.isNotEmpty()) {
            validAnswerCount.toFloat() / groupedByQuestion.size
        } else 0f

        // Cleanup native OpenCV matrices
        srcRgba.release()
        srcGray.release()
        warpedGray.release()
        binary.release()
        rectifiedColor.release()

        return OmrScanOutput(
            rawAnswers = rawAnswers,
            ambiguousQuestions = ambiguousQuestions,
            rectifiedBitmap = rectifiedBitmap,
            detectedCorners = corners,
            confidence = confidence
        )
    }

    /**
     * Calculates the fill ratio of dark pixels inside the circular ROI of a bubble.
     */
    private fun calculateBubbleFillRatio(
        binaryMat: Mat,
        bubble: BubbleLocation,
        pageWidth: Float,
        pageHeight: Float
    ): Float {
        val cx = (bubble.normCenterX * pageWidth).toInt()
        val cy = (bubble.normCenterY * pageHeight).toInt()
        // Sample 70% of radius to strictly sample inner circle without the printed border outline
        val radius = (bubble.normRadius * pageWidth * 0.70f).toInt().coerceAtLeast(3)

        var totalPixels = 0
        var filledPixels = 0

        val r2 = radius * radius
        val minX = (cx - radius).coerceAtLeast(0)
        val maxX = (cx + radius).coerceAtMost(binaryMat.cols() - 1)
        val minY = (cy - radius).coerceAtLeast(0)
        val maxY = (cy + radius).coerceAtMost(binaryMat.rows() - 1)

        val buffer = ByteArray(1)

        for (y in minY..maxY) {
            val dy = y - cy
            val dy2 = dy * dy
            for (x in minX..maxX) {
                val dx = x - cx
                if (dx * dx + dy2 <= r2) {
                    totalPixels++
                    binaryMat.get(y, x, buffer)
                    if (buffer[0].toInt() and 0xFF > 128) {
                        filledPixels++
                    }
                }
            }
        }

        return if (totalPixels > 0) filledPixels.toFloat() / totalPixels else 0f
    }

    /**
     * Fast corner detection for live camera frames.
     */
    fun detectLiveCorners(grayMat: Mat): CornerDetectionResult {
        val corners = detectSheetCorners(grayMat)
        return CornerDetectionResult(
            corners = corners,
            isDetected = corners != null && corners.size == 4
        )
    }

    /**
     * Detects 4 document corner points using contour approximation or fiducial anchor detection.
     */
    fun detectSheetCorners(grayMat: Mat): List<Point>? {
        val blurred = Mat()
        Imgproc.GaussianBlur(grayMat, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 40.0, 120.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var maxArea = 0.0
        var bestApprox: MatOfPoint2f? = null

        val minExpectedArea = (grayMat.rows() * grayMat.cols()) * 0.12 // At least 12% of frame

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.025 * peri, true)

            val area = Imgproc.contourArea(contour)
            if (approx.total() == 4L && area > minExpectedArea && area > maxArea) {
                val approxPoints = MatOfPoint(*approx.toArray())
                if (Imgproc.isContourConvex(approxPoints)) {
                    maxArea = area
                    bestApprox?.release()
                    bestApprox = approx
                } else {
                    approx.release()
                }
                approxPoints.release()
            } else {
                approx.release()
            }
            contour2f.release()
            contour.release()
        }

        blurred.release()
        edges.release()
        hierarchy.release()

        val approx = bestApprox
        if (approx != null) {
            val points = approx.toArray().toList()
            approx.release()
            return orderCornerPoints(points)
        }

        // Secondary fallback: search for the 4 dark corner anchor squares
        return detectAnchorSquares(grayMat)
    }

    private fun detectAnchorSquares(grayMat: Mat): List<Point>? {
        val binary = Mat()
        Imgproc.threshold(grayMat, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val totalArea = grayMat.rows() * grayMat.cols()
        val minAnchorArea = totalArea * 0.0003
        val maxAnchorArea = totalArea * 0.03

        val candidateCenters = mutableListOf<Point>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minAnchorArea..maxAnchorArea) {
                val rect = Imgproc.boundingRect(contour)
                val aspect = rect.width.toDouble() / rect.height.toDouble()
                if (aspect in 0.65..1.55) {
                    val m = Imgproc.moments(contour)
                    if (m.m00 != 0.0) {
                        candidateCenters.add(Point(m.m10 / m.m00, m.m01 / m.m00))
                    }
                }
            }
            contour.release()
        }
        binary.release()
        hierarchy.release()

        if (candidateCenters.size < 4) return null

        val midX = grayMat.cols() / 2.0
        val midY = grayMat.rows() / 2.0

        val tl = candidateCenters.filter { it.x < midX && it.y < midY }.minByOrNull { hypot(it.x, it.y) }
        val tr = candidateCenters.filter { it.x >= midX && it.y < midY }.minByOrNull { hypot(grayMat.cols() - it.x, it.y) }
        val bl = candidateCenters.filter { it.x < midX && it.y >= midY }.minByOrNull { hypot(it.x, grayMat.rows() - it.y) }
        val br = candidateCenters.filter { it.x >= midX && it.y >= midY }.minByOrNull { hypot(grayMat.cols() - it.x, grayMat.rows() - it.y) }

        if (tl != null && tr != null && bl != null && br != null) {
            return listOf(tl, tr, br, bl)
        }
        return null
    }

    /**
     * Orders 4 points clockwise: [top-left, top-right, bottom-right, bottom-left].
     */
    private fun orderCornerPoints(pts: List<Point>): List<Point> {
        if (pts.size != 4) return pts

        // Sort by sum (x + y): Top-Left has smallest sum, Bottom-Right has largest sum
        val sumSorted = pts.sortedBy { it.x + it.y }
        val topLeft = sumSorted.first()
        val bottomRight = sumSorted.last()

        // Sort remaining 2 points by difference (y - x): Top-Right has smallest (x > y), Bottom-Left has largest (y > x)
        val remaining = pts.filter { it != topLeft && it != bottomRight }
        val diffSorted = remaining.sortedBy { it.y - it.x }
        val topRight = diffSorted.first()
        val bottomLeft = diffSorted.last()

        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * Draws score, checkmarks ("✔") and crosses ("❌") on top of each item number,
     * and highlights detected student answers and correct keys with colors.
     */
    fun annotateGradedSheet(
        rectifiedBitmap: Bitmap,
        layoutConfig: SheetLayoutConfig,
        exam: ExamEntity,
        rawAnswers: Map<Int, Char?>,
        scoreSummary: ScoringEngine.ScoreSummary
    ): Bitmap {
        val annotated = rectifiedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val width = annotated.width.toFloat()
        val height = annotated.height.toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Draw Graded Score Banner at Top Header
        val isPass = scoreSummary.percentage >= 60f
        val themeColor = if (isPass) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        val themeBg = if (isPass) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")

        val badgeY = height * 0.012f
        val badgeH = height * 0.038f
        val badgeRect = RectF(width * 0.06f, badgeY, width * 0.94f, badgeY + badgeH)

        paint.style = Paint.Style.FILL
        paint.color = themeBg
        canvas.drawRoundRect(badgeRect, 10f, 10f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = themeColor
        canvas.drawRoundRect(badgeRect, 10f, 10f, paint)

        paint.style = Paint.Style.FILL
        paint.color = themeColor
        paint.textSize = badgeH * 0.50f
        paint.isFakeBoldText = true

        val scoreText = "SCORE: ${scoreSummary.earnedScore.toInt()} / ${scoreSummary.totalPoints.toInt()} (${scoreSummary.percentage.toInt()}%)   •   ✔ ${scoreSummary.correctCount} Correct   ❌ ${scoreSummary.incorrectCount + scoreSummary.blankCount} Wrong"
        val textWidth = paint.measureText(scoreText)
        canvas.drawText(scoreText, (width - textWidth) / 2f, badgeY + badgeH * 0.68f, paint)

        // 2. Locate Questions & Bubbles
        val gridElement = layoutConfig.elements.firstOrNull { it.type == ElementType.BUBBLE_GRID }
            ?: return annotated
        val config = gridElement.gridConfig ?: BubbleGridConfig()

        val totalQuestions = config.endQuestion - config.startQuestion + 1
        val numCols = config.columns.coerceAtLeast(1)
        val questionsPerCol = (totalQuestions + numCols - 1) / numCols

        val elX = gridElement.x * width
        val elY = gridElement.y * height
        val elW = gridElement.width * width
        val elH = gridElement.height * height

        val colWidth = elW / numCols
        val headerOffset = 0.024f * height
        val availableH = (elH - headerOffset).coerceAtLeast(10f)
        val rowHeight = availableH / questionsPerCol.coerceAtLeast(1)

        val labelWidth = 28f * (width / 595f)

        for (q in config.startQuestion..config.endQuestion) {
            val qIndex = q - config.startQuestion
            val colIndex = qIndex / questionsPerCol
            val rowIndex = qIndex % questionsPerCol

            val colStartX = elX + colIndex * colWidth
            val rowY = elY + headerOffset + rowIndex * rowHeight

            val isCorrect = scoreSummary.itemResults[q]
            val studentAns = rawAnswers[q]
            val correctAns = exam.answerKey[q]

            // Mark position right over / beside the item number
            val itemNumCenterX = colStartX + labelWidth * 0.38f
            val itemNumCenterY = rowY + rowHeight * 0.5f

            // A. Draw Checkmark ("✔") or Cross ("❌") on top of Item Number
            val markSize = (rowHeight * 0.75f).coerceIn(16f, 28f)
            paint.isFakeBoldText = true
            paint.textSize = markSize

            if (isCorrect == true) {
                // Soft green background highlight
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#352E7D32")
                canvas.drawCircle(itemNumCenterX, itemNumCenterY, markSize * 0.55f, paint)

                // Bold Green "✔"
                paint.color = Color.parseColor("#1B5E20")
                val mark = "✔"
                val mW = paint.measureText(mark)
                canvas.drawText(mark, itemNumCenterX - mW / 2f, itemNumCenterY + markSize * 0.35f, paint)
            } else {
                // Soft red background highlight
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#35C62828")
                canvas.drawCircle(itemNumCenterX, itemNumCenterY, markSize * 0.55f, paint)

                // Bold Red "❌"
                paint.color = Color.parseColor("#B71C1C")
                val mark = "❌"
                val mW = paint.measureText(mark)
                canvas.drawText(mark, itemNumCenterX - mW / 2f, itemNumCenterY + markSize * 0.35f, paint)
            }

            // B. Draw Colored Highlights on Option Bubbles
            val optionsStartX = colStartX + labelWidth
            val availableBubbleWidth = (colWidth - labelWidth - 6f).coerceAtLeast(10f)
            val bubbleSpacing = availableBubbleWidth / config.options.size
            val bubbleRadius = (bubbleSpacing * 0.32f).coerceAtMost(rowHeight * 0.38f).coerceAtLeast(8f)

            for (optIndex in config.options.indices) {
                val option = config.options[optIndex]
                val bx = optionsStartX + optIndex * bubbleSpacing + bubbleSpacing * 0.5f
                val by = rowY + rowHeight * 0.5f

                val isStudentChoice = studentAns != null && studentAns.equals(option, ignoreCase = true)
                val isCorrectKey = correctAns != null && correctAns.equals(option, ignoreCase = true)

                if (isStudentChoice && isCorrectKey) {
                    // Correct Choice: Solid green ring + translucent green fill
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#454CAF50")
                    canvas.drawCircle(bx, by, bubbleRadius + 2.5f, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3.5f
                    paint.color = Color.parseColor("#2E7D32")
                    canvas.drawCircle(bx, by, bubbleRadius + 2.5f, paint)
                } else if (isStudentChoice && !isCorrectKey) {
                    // Wrong Choice: Red ring + translucent red fill + red X cross
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#45F44336")
                    canvas.drawCircle(bx, by, bubbleRadius + 2.5f, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3.5f
                    paint.color = Color.parseColor("#C62828")
                    canvas.drawCircle(bx, by, bubbleRadius + 2.5f, paint)

                    // Draw red diagonal cross over wrong bubble
                    val arm = bubbleRadius * 0.65f
                    paint.strokeWidth = 2.5f
                    canvas.drawLine(bx - arm, by - arm, bx + arm, by + arm, paint)
                    canvas.drawLine(bx - arm, by + arm, bx + arm, by - arm, paint)
                } else if (!isStudentChoice && isCorrectKey) {
                    // Correct key that was missed: Highlighted Emerald Green outline
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#2500E676")
                    canvas.drawCircle(bx, by, bubbleRadius + 2.5f, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3.0f
                    paint.color = Color.parseColor("#00C853")
                    canvas.drawCircle(bx, by, bubbleRadius + 2.5f, paint)
                }
            }
        }

        return annotated
    }
}
