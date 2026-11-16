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

    private const val FILL_THRESHOLD = 0.32f
    private const val BLANK_THRESHOLD = 0.22f
    private const val AMBIGUITY_DELTA = 0.10f

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
            detectedCorners = detectedCornersList,
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
        val status = detectCornerMarkersStatus(downscaled)

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

        // Partition candidates by quadrant and pick outermost marker in each quadrant
        val tl = candidates
            .filter { it.center.x < midX && it.center.y < midY }
            .minByOrNull { hypot(it.center.x, it.center.y) }

        val tr = candidates
            .filter { it.center.x >= midX && it.center.y < midY }
            .minByOrNull { hypot(cols - it.center.x, it.center.y) }

        val bl = candidates
            .filter { it.center.x < midX && it.center.y >= midY }
            .minByOrNull { hypot(it.center.x, rows - it.center.y) }

        val br = candidates
            .filter { it.center.x >= midX && it.center.y >= midY }
            .minByOrNull { hypot(cols - it.center.x, rows - it.center.y) }

        val hasAll4 = tl != null && tr != null && bl != null && br != null

        if (hasAll4) {
            // Size uniformity: all 4 anchors should have similar physical size
            val areas = listOf(tl.area, tr.area, bl.area, br.area)
            val minA = areas.minOrNull()!!
            val maxA = areas.maxOrNull()!!
            val isSizeOk = maxA <= minA * 5.0

            // Geometry check
            val topW = hypot(tr.center.x - tl.center.x, tr.center.y - tl.center.y)
            val botW = hypot(br.center.x - bl.center.x, br.center.y - bl.center.y)
            val leftH = hypot(bl.center.x - tl.center.x, bl.center.y - tl.center.y)
            val rightH = hypot(br.center.x - tr.center.x, br.center.y - tr.center.y)

            val avgW = (topW + botW) / 2.0
            val avgH = (leftH + rightH) / 2.0
            val aspect = if (avgW > 0) avgH / avgW else 0.0

            val isGeometryOk = avgW >= cols * 0.20 && avgH >= rows * 0.20 &&
                    aspect in 0.90..2.20 &&
                    abs(topW - botW) / avgW <= 0.50 &&
                    abs(leftH - rightH) / avgH <= 0.50

            if (isSizeOk && isGeometryOk) {
                return CornerStatus(
                    isTopLeftDetected = true,
                    isTopRightDetected = true,
                    isBottomLeftDetected = true,
                    isBottomRightDetected = true,
                    topLeftPoint = tl.center,
                    topRightPoint = tr.center,
                    bottomLeftPoint = bl.center,
                    bottomRightPoint = br.center
                )
            }
        }

        // Return individual corner detection status for live visual feedback
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
