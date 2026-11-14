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
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

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
     * Internal representation of a candidate fiducial anchor square.
     */
    private data class AnchorCandidate(
        val center: Point,
        val rect: Rect,
        val area: Double,
        val meanGray: Double
    )

    /**
     * Fast corner detection for live camera frames.
     * Strictly requires the 4 printed solid black fiducial corner marks on bright white paper.
     * Rejects dark objects, laptop screens, tables, mousepads, shadows, and non-OMR rectangles.
     */
    fun detectLiveCorners(grayMat: Mat): CornerDetectionResult {
        val anchors = detectFiducialCornerMarks(grayMat)
        return CornerDetectionResult(
            corners = anchors,
            isDetected = anchors != null && anchors.size == 4
        )
    }

    /**
     * Detects 4 document corner points for warping perspective.
     * Prioritizes fiducial corner marks, and uses paper extrapolation.
     */
    fun detectSheetCorners(grayMat: Mat): List<Point>? {
        // 1. Primary detector: 4 fiducial anchor squares
        val anchors = detectFiducialCornerMarks(grayMat)
        if (anchors != null && anchors.size == 4) {
            return extrapolatePaperCorners(anchors)
        }

        // 2. Secondary fallback for high-res photo captures: verified bright document contour
        return detectValidatedDocumentContour(grayMat)
    }

    /**
     * Detects the 4 printed solid black fiducial anchor squares on the OMR sheet.
     * Validates square geometry, size uniformity, A4 aspect ratio, and white paper interior.
     */
    fun detectFiducialCornerMarks(grayMat: Mat): List<Point>? {
        val cols = grayMat.cols().toDouble()
        val rows = grayMat.rows().toDouble()
        val totalArea = cols * rows

        // Threshold to extract dark marks
        val binary = Mat()
        Imgproc.threshold(grayMat, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        binary.release()
        hierarchy.release()

        val minAnchorArea = totalArea * 0.0003 // ~90 px on 480x640 frame
        val maxAnchorArea = totalArea * 0.020  // ~6000 px on 480x640 frame

        val candidates = mutableListOf<AnchorCandidate>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minAnchorArea..maxAnchorArea) {
                val rect = Imgproc.boundingRect(contour)
                val aspect = rect.width.toDouble() / rect.height.toDouble()

                // Anchor is square: aspect ratio between 0.68 and 1.45
                if (aspect in 0.68..1.45) {
                    val boxArea = rect.width.toDouble() * rect.height.toDouble()
                    val solidity = if (boxArea > 0) area / boxArea else 0.0

                    // Solid filled square: solidity >= 0.65
                    if (solidity >= 0.65) {
                        // Check anchor darkness (black ink)
                        val safeRect = Rect(
                            rect.x.coerceIn(0, grayMat.cols() - 1),
                            rect.y.coerceIn(0, grayMat.rows() - 1),
                            rect.width.coerceAtMost(grayMat.cols() - rect.x),
                            rect.height.coerceAtMost(grayMat.rows() - rect.y)
                        )
                        if (safeRect.width > 2 && safeRect.height > 2) {
                            val subMat = Mat(grayMat, safeRect)
                            val meanGray = Core.mean(subMat).`val`[0]
                            subMat.release()

                            if (meanGray < 125.0) {
                                val m = Imgproc.moments(contour)
                                val center = if (m.m00 != 0.0) {
                                    Point(m.m10 / m.m00, m.m01 / m.m00)
                                } else {
                                    Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
                                }
                                candidates.add(AnchorCandidate(center, safeRect, area, meanGray))
                            }
                        }
                    }
                }
            }
            contour.release()
        }

        if (candidates.size < 4) return null

        val midX = cols / 2.0
        val midY = rows / 2.0

        // Partition candidates by quadrant
        val tlCandidates = candidates.filter { it.center.x < midX * 1.10 && it.center.y < midY * 1.10 }
        val trCandidates = candidates.filter { it.center.x > midX * 0.90 && it.center.y < midY * 1.10 }
        val blCandidates = candidates.filter { it.center.x < midX * 1.10 && it.center.y > midY * 0.90 }
        val brCandidates = candidates.filter { it.center.x > midX * 0.90 && it.center.y > midY * 0.90 }

        if (tlCandidates.isEmpty() || trCandidates.isEmpty() || blCandidates.isEmpty() || brCandidates.isEmpty()) {
            return null
        }

        // Pick outermost candidate in each quadrant
        val tl = tlCandidates.minByOrNull { hypot(it.center.x, it.center.y) }!!
        val tr = trCandidates.minByOrNull { hypot(cols - it.center.x, it.center.y) }!!
        val bl = blCandidates.minByOrNull { hypot(it.center.x, rows - it.center.y) }!!
        val br = brCandidates.minByOrNull { hypot(cols - it.center.x, rows - it.center.y) }!!

        // 1. Size Uniformity Check: all 4 anchors are printed at the exact same size
        val areas = listOf(tl.area, tr.area, bl.area, br.area)
        val minA = areas.minOrNull()!!
        val maxA = areas.maxOrNull()!!
        if (maxA > minA * 3.8) return null

        // 2. Sheet Geometry & Dimension Check
        val topW = hypot(tr.center.x - tl.center.x, tr.center.y - tl.center.y)
        val botW = hypot(br.center.x - bl.center.x, br.center.y - bl.center.y)
        val leftH = hypot(bl.center.x - tl.center.x, bl.center.y - tl.center.y)
        val rightH = hypot(br.center.x - tr.center.x, br.center.y - tr.center.y)

        val avgW = (topW + botW) / 2.0
        val avgH = (leftH + rightH) / 2.0

        // Sheet must span at least 28% of frame in both dimensions
        if (avgW < cols * 0.28 || avgH < rows * 0.28) return null

        // Trapezoid distortion limits
        if (abs(topW - botW) / avgW > 0.32) return null
        if (abs(leftH - rightH) / avgH > 0.32) return null

        // Portrait aspect ratio check (A4 is 1.414, allow perspective angle variance)
        val aspect = avgH / avgW
        if (aspect !in 1.10..1.80) return null

        // Tilt/Angle check: sheet must be roughly upright
        if (abs(tr.center.y - tl.center.y) / topW > 0.30) return null
        if (abs(br.center.y - bl.center.y) / botW > 0.30) return null
        if (abs(bl.center.x - tl.center.x) / leftH > 0.30) return null
        if (abs(br.center.x - tr.center.x) / rightH > 0.30) return null

        // 3. Bright White Paper Interior Check (Rejects dark objects, laptops, tables, monitors)
        val centerX = (tl.center.x + tr.center.x + bl.center.x + br.center.x) / 4.0
        val centerY = (tl.center.y + tr.center.y + bl.center.y + br.center.y) / 4.0
        val sampleW = (avgW * 0.35).toInt().coerceAtLeast(10)
        val sampleH = (avgH * 0.35).toInt().coerceAtLeast(10)
        val sampleX = (centerX - sampleW / 2.0).toInt().coerceIn(0, (cols - sampleW).toInt())
        val sampleY = (centerY - sampleH / 2.0).toInt().coerceIn(0, (rows - sampleH).toInt())

        val sampleRect = Rect(sampleX, sampleY, sampleW, sampleH)
        val sampleMat = Mat(grayMat, sampleRect)
        val interiorMean = Core.mean(sampleMat).`val`[0]
        sampleMat.release()

        val anchorMean = (tl.meanGray + tr.meanGray + bl.meanGray + br.meanGray) / 4.0

        // Paper must be bright (>75) and significantly brighter than the black anchor squares
        if (interiorMean < 75.0 || (interiorMean - anchorMean) < 22.0) {
            return null
        }

        return listOf(tl.center, tr.center, br.center, bl.center)
    }

    /**
     * Extrapolates the 4 outer paper boundary corners from the 4 anchor centers.
     * Printed anchors are positioned at margin = 4%, size = 4%, so anchor centers are at 6% from paper edges.
     */
    fun extrapolatePaperCorners(anchors: List<Point>): List<Point> {
        if (anchors.size != 4) return anchors

        val tl = anchors[0]
        val tr = anchors[1]
        val br = anchors[2]
        val bl = anchors[3]

        val topVecX = tr.x - tl.x
        val topVecY = tr.y - tl.y
        val botVecX = br.x - bl.x
        val botVecY = br.y - bl.y

        val leftVecX = bl.x - tl.x
        val leftVecY = bl.y - tl.y
        val rightVecX = br.x - tr.x
        val rightVecY = br.y - tr.y

        val hFactor = 0.0682 // 0.06 / 0.88
        val vFactor = 0.0464 // 0.06 / 1.294

        val pTL = Point(
            tl.x - topVecX * hFactor - leftVecX * vFactor,
            tl.y - topVecY * hFactor - leftVecY * vFactor
        )
        val pTR = Point(
            tr.x + topVecX * hFactor - rightVecX * vFactor,
            tr.y + topVecY * hFactor - rightVecY * vFactor
        )
        val pBR = Point(
            br.x + botVecX * hFactor + rightVecX * vFactor,
            br.y + botVecY * hFactor + rightVecY * vFactor
        )
        val pBL = Point(
            bl.x - botVecX * hFactor + leftVecX * vFactor,
            bl.y - botVecY * hFactor + leftVecY * vFactor
        )

        return listOf(pTL, pTR, pBR, pBL)
    }

    /**
     * Fallback document contour detector for captured images.
     * Strictly validates that the contour is bright white paper, rejecting dark objects.
     */
    private fun detectValidatedDocumentContour(grayMat: Mat): List<Point>? {
        val blurred = Mat()
        Imgproc.GaussianBlur(grayMat, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 40.0, 120.0)
        blurred.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        edges.release()
        hierarchy.release()

        val totalArea = grayMat.rows().toDouble() * grayMat.cols().toDouble()
        val minExpectedArea = totalArea * 0.18

        var maxArea = 0.0
        var bestApprox: MatOfPoint2f? = null

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.025 * peri, true)

            val area = Imgproc.contourArea(contour)
            if (approx.total() == 4L && area > minExpectedArea && area > maxArea) {
                val approxPoints = MatOfPoint(*approx.toArray())
                if (Imgproc.isContourConvex(approxPoints)) {
                    val pts = approx.toArray().toList()
                    val ordered = orderCornerPoints(pts)
                    val w = hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y)
                    val h = hypot(ordered[3].x - ordered[0].x, ordered[3].y - ordered[0].y)
                    val aspect = if (w > 0) h / w else 0.0

                    // Document must be portrait aspect ratio
                    if (aspect in 1.10..1.75) {
                        // Crucial: check that the document interior is bright white paper, not a dark object
                        val centerPtX = (ordered[0].x + ordered[1].x + ordered[2].x + ordered[3].x) / 4.0
                        val centerPtY = (ordered[0].y + ordered[1].y + ordered[2].y + ordered[3].y) / 4.0
                        val sampleSize = (w * 0.25).toInt().coerceAtLeast(10)
                        val sampleRect = Rect(
                            (centerPtX - sampleSize / 2.0).toInt().coerceIn(0, grayMat.cols() - sampleSize),
                            (centerPtY - sampleSize / 2.0).toInt().coerceIn(0, grayMat.rows() - sampleSize),
                            sampleSize,
                            sampleSize
                        )
                        val sampleMat = Mat(grayMat, sampleRect)
                        val interiorMean = Core.mean(sampleMat).`val`[0]
                        sampleMat.release()

                        // Reject dark laptops, dark desks, etc.
                        if (interiorMean >= 80.0) {
                            maxArea = area
                            bestApprox?.release()
                            bestApprox = approx
                        } else {
                            approx.release()
                        }
                    } else {
                        approx.release()
                    }
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

        val approx = bestApprox
        if (approx != null) {
            val points = approx.toArray().toList()
            approx.release()
            return orderCornerPoints(points)
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
