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

data class CornerStatus(
    val isTopLeftDetected: Boolean = false,
    val isTopRightDetected: Boolean = false,
    val isBottomLeftDetected: Boolean = false,
    val isBottomRightDetected: Boolean = false,
    val topLeftPoint: Point? = null,
    val topRightPoint: Point? = null,
    val bottomLeftPoint: Point? = null,
    val bottomRightPoint: Point? = null
) {
    val detectedCount: Int
        get() = (if (isTopLeftDetected) 1 else 0) +
                (if (isTopRightDetected) 1 else 0) +
                (if (isBottomLeftDetected) 1 else 0) +
                (if (isBottomRightDetected) 1 else 0)

    val isAllDetected: Boolean
        get() = isTopLeftDetected && isTopRightDetected && isBottomLeftDetected && isBottomRightDetected

    fun toPointList(): List<Point>? {
        if (!isAllDetected) return null
        return listOf(topLeftPoint!!, topRightPoint!!, bottomRightPoint!!, bottomLeftPoint!!)
    }
}

data class CornerDetectionResult(
    val corners: List<Point>?,
    val isDetected: Boolean,
    val status: CornerStatus = CornerStatus()
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

    // Bubble darkness thresholds (relative to row baseline and absolute scale)
    private const val MIN_SHADE_DARKNESS = 0.42f
    private const val MIN_ROW_DELTA = 0.14f
    private const val MIN_SEPARATION_DELTA = 0.08f

    /**
     * Processes an image bitmap of a sheet, detects the document boundary,
     * warps perspective, and extracts bubble responses.
     */
    fun processAnswerSheet(
        bitmap: Bitmap,
        layoutConfig: SheetLayoutConfig,
        hintCorners: List<Point>? = null
    ): OmrScanOutput {
        val srcRgba = Mat()
        Utils.bitmapToMat(bitmap, srcRgba)

        val srcGray = Mat()
        Imgproc.cvtColor(srcRgba, srcGray, Imgproc.COLOR_RGBA2GRAY)

        val anchorFraction = layoutConfig.cornerAnchorSizeFraction.coerceAtLeast(0.035f)
        val anchorOffset = WARPED_WIDTH * (0.04 + anchorFraction * 0.5)

        // 1. Detect 4 Corners of Sheet (Multi-scale anchor detection with sub-pixel refinement)
        val detectedAnchors = detectFiducialsMultiScale(srcGray, hintCorners)

        val warpedGray = Mat()
        val detectedCornersList: List<Point>?

        if (detectedAnchors != null && detectedAnchors.size == 4) {
            val srcCorners = MatOfPoint2f(*detectedAnchors.toTypedArray())
            val dstCorners = MatOfPoint2f(
                Point(anchorOffset, anchorOffset),
                Point(WARPED_WIDTH - anchorOffset, anchorOffset),
                Point(WARPED_WIDTH - anchorOffset, WARPED_HEIGHT - anchorOffset),
                Point(anchorOffset, WARPED_HEIGHT - anchorOffset)
            )

            val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
            Imgproc.warpPerspective(srcGray, warpedGray, transform, Size(WARPED_WIDTH, WARPED_HEIGHT), Imgproc.INTER_LINEAR)
            transform.release()
            srcCorners.release()
            dstCorners.release()
            detectedCornersList = detectedAnchors
        } else {
            // Fallback: document contour detection (paper boundary)
            val docCorners = detectValidatedDocumentContour(srcGray)
            if (docCorners != null && docCorners.size == 4) {
                val srcCorners = MatOfPoint2f(*docCorners.toTypedArray())
                val dstCorners = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(WARPED_WIDTH, 0.0),
                    Point(WARPED_WIDTH, WARPED_HEIGHT),
                    Point(0.0, WARPED_HEIGHT)
                )

                val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
                Imgproc.warpPerspective(srcGray, warpedGray, transform, Size(WARPED_WIDTH, WARPED_HEIGHT), Imgproc.INTER_LINEAR)
                transform.release()
                srcCorners.release()
                dstCorners.release()
                detectedCornersList = docCorners
            } else {
                // Direct resize fallback if camera is already closely framing the sheet
                Imgproc.resize(srcGray, warpedGray, Size(WARPED_WIDTH, WARPED_HEIGHT))
                detectedCornersList = null
            }
        }

        // 2. Automatic Orientation Verification:
        // The top 18% of an upright answer sheet contains header info (Test Name, Student Name, Sheet Code, Instructions).
        // The bottom 12% is empty white margin.
        // If the bottom region contains significantly more ink than the top region, the sheet was captured upside down (180°).
        val checkMarginX = (WARPED_WIDTH * 0.12).toInt()
        val checkW = (WARPED_WIDTH * 0.76).toInt()
        val topCheckY = (WARPED_HEIGHT * 0.04).toInt()
        val checkH = (WARPED_HEIGHT * 0.14).toInt()
        val botCheckY = (WARPED_HEIGHT * 0.82).toInt()

        val topRoi = Mat(warpedGray, Rect(checkMarginX, topCheckY, checkW, checkH))
        val botRoi = Mat(warpedGray, Rect(checkMarginX, botCheckY, checkW, checkH))

        val topInk = 255.0 - Core.mean(topRoi).`val`[0]
        val botInk = 255.0 - Core.mean(botRoi).`val`[0]
        topRoi.release()
        botRoi.release()

        if (botInk > topInk * 1.35) {
            Core.rotate(warpedGray, warpedGray, Core.ROTATE_180)
        }

        // 3. Extract Bubble Responses Using Relative Darkness (Robust against printed letters and lighting gradients)
        val bubbleLocations = BubbleCoordinateHelper.calculateBubbleLocations(layoutConfig)
        val groupedByQuestion = bubbleLocations.groupBy { it.question }

        val rawAnswers = mutableMapOf<Int, Char?>()
        val ambiguousQuestions = mutableListOf<Int>()
        var validAnswerCount = 0

        for ((question, bubbles) in groupedByQuestion) {
            val bubbleFills = bubbles.map { bubble ->
                val darkness = calculateBubbleDarkness(warpedGray, bubble, WARPED_WIDTH.toFloat(), WARPED_HEIGHT.toFloat())
                Pair(bubble.option, darkness)
            }.sortedByDescending { it.second }

            val topOption = bubbleFills.firstOrNull()
            val secondOption = bubbleFills.getOrNull(1)

            val topDarkness = topOption?.second ?: 0f
            val secondDarkness = secondOption?.second ?: 0f

            val otherBubbles = bubbleFills.drop(1)
            val otherBaseline = if (otherBubbles.isNotEmpty()) {
                otherBubbles.map { it.second }.average().toFloat()
            } else 0f

            val topDelta = topDarkness - otherBaseline
            val separation = topDarkness - secondDarkness

            val remainingAfterSecond = otherBubbles.drop(1)
            val remainingBaseline = if (remainingAfterSecond.isNotEmpty()) {
                remainingAfterSecond.map { it.second }.average().toFloat()
            } else otherBaseline
            val secondDelta = secondDarkness - remainingBaseline

            when {
                // Double mark detected: both top and second bubbles are significantly darker than baseline
                topDelta >= MIN_ROW_DELTA && topDarkness >= MIN_SHADE_DARKNESS && secondDelta >= 0.12f && separation < MIN_SEPARATION_DELTA -> {
                    rawAnswers[question] = null
                    ambiguousQuestions.add(question)
                }
                // Valid single mark: top bubble is significantly darker than row baseline and clearly separated
                topDelta >= MIN_ROW_DELTA && topDarkness >= MIN_SHADE_DARKNESS && separation >= MIN_SEPARATION_DELTA -> {
                    rawAnswers[question] = topOption?.first
                    validAnswerCount++
                }
                // Soft pencil shade with strong separation
                topDelta >= 0.12f && topDarkness >= 0.38f && separation >= 0.10f -> {
                    rawAnswers[question] = topOption?.first
                    validAnswerCount++
                }
                // Blank row or unshaded
                else -> {
                    rawAnswers[question] = null
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
        rectifiedColor.release()

        return OmrScanOutput(
            rawAnswers = rawAnswers,
            ambiguousQuestions = ambiguousQuestions,
            rectifiedBitmap = rectifiedBitmap,
            detectedCorners = detectedCornersList,
            confidence = confidence
        )
    }

    /**
     * Calculates the normalized darkness (0.0f = white paper, 1.0f = black ink)
     * inside the inner circular ROI of a bubble.
     */
    private fun calculateBubbleDarkness(
        grayMat: Mat,
        bubble: BubbleLocation,
        pageWidth: Float,
        pageHeight: Float
    ): Float {
        val cx = (bubble.normCenterX * pageWidth).toInt()
        val cy = (bubble.normCenterY * pageHeight).toInt()
        // Sample inner 65% of bubble radius to strictly sample inside the circle,
        // avoiding printed borders while capturing pen/pencil strokes.
        val radius = (bubble.normRadius * pageWidth * 0.65f).toInt().coerceAtLeast(3)

        var totalPixels = 0
        var sumDarkness = 0.0

        val r2 = radius * radius
        val minX = (cx - radius).coerceAtLeast(0)
        val maxX = (cx + radius).coerceAtMost(grayMat.cols() - 1)
        val minY = (cy - radius).coerceAtLeast(0)
        val maxY = (cy + radius).coerceAtMost(grayMat.rows() - 1)

        val buffer = ByteArray(1)

        for (y in minY..maxY) {
            val dy = y - cy
            val dy2 = dy * dy
            for (x in minX..maxX) {
                val dx = x - cx
                if (dx * dx + dy2 <= r2) {
                    totalPixels++
                    grayMat.get(y, x, buffer)
                    val gray = buffer[0].toInt() and 0xFF
                    sumDarkness += (255 - gray) / 255.0
                }
            }
        }

        return if (totalPixels > 0) (sumDarkness / totalPixels).toFloat() else 0f
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
     * Evaluates the 4 corner marker guide regions and reports individual corner lock statuses.
     */
    fun detectLiveCorners(grayMat: Mat): CornerDetectionResult {
        val status = detectCornerMarkersStatus(grayMat)
        return CornerDetectionResult(
            corners = status.toPointList(),
            isDetected = status.isAllDetected,
            status = status
        )
    }

    /**
     * Multi-scale anchor detector for captured images.
     * Downscales high-resolution photos for lightning-fast and shadow-resistant detection,
     * then applies sub-pixel local refinement on the full-resolution image.
     */
    fun detectFiducialsMultiScale(
        grayMat: Mat,
        hintNormalizedCorners: List<Point>? = null
    ): List<Point>? {
        val origW = grayMat.cols().toDouble()
        val origH = grayMat.rows().toDouble()

        // 1. If image is high-res (>850px width), downscale to standard detection width (~800px)
        val targetWidth = 800.0
        val downscaled: Mat
        val scaleX: Double
        val scaleY: Double

        if (origW > targetWidth * 1.05) {
            val scale = targetWidth / origW
            val targetH = (origH * scale).toInt().coerceAtLeast(1)
            downscaled = Mat()
            Imgproc.resize(grayMat, downscaled, Size(targetWidth, targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            scaleX = origW / targetWidth
            scaleY = origH / targetH.toDouble()
        } else {
            downscaled = grayMat
            scaleX = 1.0
            scaleY = 1.0
        }

        // Try anchor detection on downscaled image
        var status = detectCornerMarkersStatus(downscaled)
        if (!status.isAllDetected) {
            // Apply CLAHE to overcome ambient shadow gradients across sheet
            val claheMat = Mat()
            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            clahe.apply(downscaled, claheMat)
            val claheStatus = detectCornerMarkersStatus(claheMat)
            claheMat.release()
            if (claheStatus.detectedCount > status.detectedCount) {
                status = claheStatus
            }
        }

        if (downscaled != grayMat) {
            downscaled.release()
        }

        var candidateAnchors: List<Point>? = null
        if (status.isAllDetected) {
            val pts = status.toPointList()!!
            candidateAnchors = pts.map { Point(it.x * scaleX, it.y * scaleY) }
        } else if (hintNormalizedCorners != null && hintNormalizedCorners.size == 4) {
            // Use locked hint corners from live preview
            candidateAnchors = hintNormalizedCorners.map { Point(it.x * origW, it.y * origH) }
        }

        if (candidateAnchors == null || candidateAnchors.size != 4) {
            return null
        }

        // 2. Sub-pixel local refinement on the original full-res grayMat
        return refineAnchorCentersLocally(grayMat, candidateAnchors)
    }

    /**
     * Refines candidate anchor positions on the full-resolution image using localized ROIs.
     * Provides sub-pixel accuracy impervious to global lighting gradients.
     */
    private fun refineAnchorCentersLocally(
        grayMat: Mat,
        approxPoints: List<Point>
    ): List<Point> {
        val cols = grayMat.cols()
        val rows = grayMat.rows()
        val refined = mutableListOf<Point>()

        // Search window radius around candidate: ~4% of image width
        val windowRadius = (cols * 0.04).toInt().coerceIn(24, 150)

        for (pt in approxPoints) {
            val minX = (pt.x - windowRadius).toInt().coerceIn(0, cols - 1)
            val minY = (pt.y - windowRadius).toInt().coerceIn(0, rows - 1)
            val maxX = (pt.x + windowRadius).toInt().coerceIn(0, cols - 1)
            val maxY = (pt.y + windowRadius).toInt().coerceIn(0, rows - 1)
            val w = maxX - minX
            val h = maxY - minY

            if (w < 10 || h < 10) {
                refined.add(pt)
                continue
            }

            val roiRect = Rect(minX, minY, w, h)
            val roiMat = Mat(grayMat, roiRect)

            val binaryRoi = Mat()
            Imgproc.threshold(roiMat, binaryRoi, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(binaryRoi, binaryRoi, Imgproc.MORPH_OPEN, kernel)
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binaryRoi, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            binaryRoi.release()
            hierarchy.release()
            roiMat.release()

            var bestCenter: Point? = null
            var bestDist = Double.MAX_VALUE
            val roiCenterX = w / 2.0
            val roiCenterY = h / 2.0
            val totalRoiArea = (w * h).toDouble()

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area in (totalRoiArea * 0.015)..(totalRoiArea * 0.75)) {
                    val rect = Imgproc.boundingRect(contour)
                    val aspect = rect.width.toDouble() / rect.height.toDouble()
                    if (aspect in 0.50..2.00) {
                        val m = Imgproc.moments(contour)
                        if (m.m00 > 0.0) {
                            val cx = m.m10 / m.m00
                            val cy = m.m01 / m.m00
                            val dist = hypot(cx - roiCenterX, cy - roiCenterY)
                            if (dist < bestDist) {
                                bestDist = dist
                                bestCenter = Point(minX + cx, minY + cy)
                            }
                        }
                    }
                }
                contour.release()
            }

            refined.add(bestCenter ?: pt)
        }

        return refined
    }

    /**
     * Detects the small solid dark fiducial marker squares in each of the 4 corner quadrants.
     * Returns individual detection status for each corner so UI guide boxes can light up green independently.
     */
    fun detectCornerMarkersStatus(grayMat: Mat): CornerStatus {
        val cols = grayMat.cols().toDouble()
        val rows = grayMat.rows().toDouble()
        val totalArea = cols * rows

        // 1. Otsu thresholding with morphological opening to sever thin framing lines
        val binary = Mat()
        Imgproc.threshold(grayMat, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)

        var candidates = findAnchorCandidates(binary, grayMat, totalArea)
        binary.release()

        // 2. Adaptive threshold fallback if Otsu found fewer than 4 candidates
        if (candidates.size < 4) {
            val adaptBinary = Mat()
            Imgproc.adaptiveThreshold(
                grayMat, adaptBinary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                31, 10.0
            )
            Imgproc.morphologyEx(adaptBinary, adaptBinary, Imgproc.MORPH_OPEN, kernel)

            val adaptCandidates = findAnchorCandidates(adaptBinary, grayMat, totalArea)
            adaptBinary.release()

            if (adaptCandidates.size > candidates.size) {
                candidates = adaptCandidates
            }
        }
        kernel.release()

        if (candidates.isEmpty()) {
            return CornerStatus()
        }

        val midX = cols / 2.0
        val midY = rows / 2.0

        val tlCandidates = candidates.filter { it.center.x < midX && it.center.y < midY }
        val trCandidates = candidates.filter { it.center.x >= midX && it.center.y < midY }
        val blCandidates = candidates.filter { it.center.x < midX && it.center.y >= midY }
        val brCandidates = candidates.filter { it.center.x >= midX && it.center.y >= midY }

        var bestQuad: List<AnchorCandidate>? = null
        var bestScore = Double.MAX_VALUE

        // Search for the 4-tuple of candidates across quadrants that best forms an A4 rectangle
        for (cTl in tlCandidates) {
            for (cTr in trCandidates) {
                val topW = hypot(cTr.center.x - cTl.center.x, cTr.center.y - cTl.center.y)
                if (topW < cols * 0.25) continue

                for (cBl in blCandidates) {
                    val leftH = hypot(cBl.center.x - cTl.center.x, cBl.center.y - cTl.center.y)
                    if (leftH < rows * 0.25) continue

                    for (cBr in brCandidates) {
                        val botW = hypot(cBr.center.x - cBl.center.x, cBr.center.y - cBl.center.y)
                        val rightH = hypot(cBr.center.x - cTr.center.x, cBr.center.y - cTr.center.y)

                        val avgW = (topW + botW) / 2.0
                        val avgH = (leftH + rightH) / 2.0
                        val aspect = if (avgW > 0) avgH / avgW else 0.0

                        if (aspect !in 1.05..1.90) continue
                        if (abs(topW - botW) / avgW > 0.28) continue
                        if (abs(leftH - rightH) / avgH > 0.28) continue

                        val d1 = hypot(cBr.center.x - cTl.center.x, cBr.center.y - cTl.center.y)
                        val d2 = hypot(cBl.center.x - cTr.center.x, cBl.center.y - cTr.center.y)
                        val avgD = (d1 + d2) / 2.0
                        if (abs(d1 - d2) / avgD > 0.22) continue

                        val areas = listOf(cTl.area, cTr.area, cBl.area, cBr.area)
                        val minA = areas.minOrNull()!!
                        val maxA = areas.maxOrNull()!!
                        if (maxA > minA * 2.8) continue

                        val score = (abs(topW - botW) / avgW) +
                                    (abs(leftH - rightH) / avgH) +
                                    (abs(d1 - d2) / avgD) +
                                    abs(aspect - 1.414) * 0.5

                        if (score < bestScore) {
                            bestScore = score
                            bestQuad = listOf(cTl, cTr, cBl, cBr)
                        }
                    }
                }
            }
        }

        if (bestQuad != null) {
            return CornerStatus(
                isTopLeftDetected = true,
                isTopRightDetected = true,
                isBottomLeftDetected = true,
                isBottomRightDetected = true,
                topLeftPoint = bestQuad[0].center,
                topRightPoint = bestQuad[1].center,
                bottomLeftPoint = bestQuad[2].center,
                bottomRightPoint = bestQuad[3].center
            )
        }

        // Fallback for live preview corner indicators: pick outermost marker in each quadrant
        val tl = tlCandidates.minByOrNull { hypot(it.center.x, it.center.y) }
        val tr = trCandidates.minByOrNull { hypot(cols - it.center.x, it.center.y) }
        val bl = blCandidates.minByOrNull { hypot(it.center.x, rows - it.center.y) }
        val br = brCandidates.minByOrNull { hypot(cols - it.center.x, rows - it.center.y) }

        return CornerStatus(
            isTopLeftDetected = tl != null,
            isTopRightDetected = tr != null,
            isBottomLeftDetected = bl != null,
            isBottomRightDetected = br != null,
            topLeftPoint = tl?.center,
            topRightPoint = tr?.center,
            bottomLeftPoint = bl?.center,
            bottomRightPoint = br?.center
        )
    }

    private fun findAnchorCandidates(
        binaryMat: Mat,
        grayMat: Mat,
        totalArea: Double
    ): List<AnchorCandidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val minAnchorArea = totalArea * 0.00010
        val maxAnchorArea = totalArea * 0.040

        val candidates = mutableListOf<AnchorCandidate>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minAnchorArea..maxAnchorArea) {
                val rect = Imgproc.boundingRect(contour)
                val aspect = rect.width.toDouble() / rect.height.toDouble()

                // Anchor is square: aspect ratio between 0.50 and 2.00 (allowing perspective tilt)
                if (aspect in 0.50..2.00) {
                    val boxArea = rect.width.toDouble() * rect.height.toDouble()
                    val solidity = if (boxArea > 0) area / boxArea else 0.0

                    // Solid filled square: solidity >= 0.50
                    if (solidity >= 0.50) {
                        val safeRect = Rect(
                            rect.x.coerceIn(0, grayMat.cols() - 1),
                            rect.y.coerceIn(0, grayMat.rows() - 1),
                            rect.width.coerceAtMost(grayMat.cols() - rect.x),
                            rect.height.coerceAtMost(grayMat.rows() - rect.y)
                        )
                        if (safeRect.width >= 3 && safeRect.height >= 3) {
                            val subMat = Mat(grayMat, safeRect)
                            val meanGray = Core.mean(subMat).`val`[0]
                            subMat.release()

                            // Must be dark ink
                            if (meanGray < 155.0) {
                                // Check local surrounding background contrast
                                val padX = (rect.width * 0.4).toInt().coerceIn(2, 20)
                                val padY = (rect.height * 0.4).toInt().coerceIn(2, 20)
                                val bgX = (rect.x - padX).coerceIn(0, grayMat.cols() - 1)
                                val bgY = (rect.y - padY).coerceIn(0, grayMat.rows() - 1)
                                val bgW = (rect.width + 2 * padX).coerceAtMost(grayMat.cols() - bgX)
                                val bgH = (rect.height + 2 * padY).coerceAtMost(grayMat.rows() - bgY)

                                val bgRect = Rect(bgX, bgY, bgW, bgH)
                                val bgMat = Mat(grayMat, bgRect)
                                val bgMean = Core.mean(bgMat).`val`[0]
                                bgMat.release()

                                // Surrounding background must be brighter than square (paper contrast)
                                if (bgMean - meanGray >= 10.0 && bgMean >= 45.0) {
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
            }
            contour.release()
        }

        return candidates
    }

    /**
     * Detects the 4 printed solid black fiducial anchor squares on the OMR sheet.
     */
    fun detectFiducialCornerMarks(grayMat: Mat): List<Point>? {
        val status = detectCornerMarkersStatus(grayMat)
        return status.toPointList()
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
