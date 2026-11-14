package com.omr.checker.vision

import android.graphics.Bitmap
import com.omr.checker.data.model.BubbleCoordinateHelper
import com.omr.checker.data.model.BubbleLocation
import com.omr.checker.data.model.SheetLayoutConfig
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

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
     * Detects 4 document corner points using contour approximation.
     */
    fun detectSheetCorners(grayMat: Mat): List<Point>? {
        val blurred = Mat()
        Imgproc.GaussianBlur(grayMat, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var maxArea = 0.0
        var bestApprox: MatOfPoint2f? = null

        val minExpectedArea = (grayMat.rows() * grayMat.cols()) * 0.20 // At least 20% of frame

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            val area = Imgproc.contourArea(contour)
            if (approx.total() == 4L && area > minExpectedArea && area > maxArea) {
                maxArea = area
                bestApprox?.release()
                bestApprox = approx
            } else {
                approx.release()
            }
            contour2f.release()
            contour.release()
        }

        blurred.release()
        edges.release()
        hierarchy.release()

        val approx = bestApprox ?: return null
        val points = approx.toArray().toList()
        approx.release()

        return orderCornerPoints(points)
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
}
