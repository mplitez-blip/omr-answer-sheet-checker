package com.omr.checker.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.entity.ScanResultEntity
import com.omr.checker.data.entity.SheetTemplateEntity
import com.omr.checker.data.repository.OMRRepository
import com.omr.checker.domain.ScoringEngine
import com.omr.checker.vision.CornerStatus
import com.omr.checker.vision.OmrEngine
import com.omr.checker.vision.OmrScanOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream

data class ScannerUiState(
    val exam: ExamEntity? = null,
    val template: SheetTemplateEntity? = null,
    val isScanning: Boolean = false,
    val isProcessing: Boolean = false,
    val isCornersLocked: Boolean = false,
    val detectedCorners: List<Point>? = null,
    val cornerStatus: CornerStatus = CornerStatus(),
    val scanOutput: OmrScanOutput? = null,
    val gradeSummary: ScoringEngine.ScoreSummary? = null,
    val lastSavedScanId: Long? = null,
    val statusMessage: String = "Position sheet within guide frame"
)

class ScannerViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    fun loadExam(examId: Long) {
        viewModelScope.launch {
            val exam = repository.getExamById(examId)
            val template = exam?.let { repository.getTemplateById(it.templateId) }
                ?: repository.ensureDefaultTemplate()

            _uiState.update {
                it.copy(
                    exam = exam,
                    template = template,
                    statusMessage = "Ready to scan for ${exam?.title ?: "Exam"}"
                )
            }
        }
    }

    fun updateCornerStatus(status: CornerStatus) {
        val count = status.detectedCount
        val msg = when {
            status.isAllDetected -> "✔ 4/4 Corner Marks Locked • Auto-Capturing..."
            count == 0 -> "Align sheet corner marks inside the 4 guide boxes"
            count == 1 -> "1/4 Corner Marks Detected"
            count == 2 -> "2/4 Corner Marks Detected"
            else -> {
                val missing = when {
                    !status.isTopLeftDetected -> "Top-Left"
                    !status.isTopRightDetected -> "Top-Right"
                    !status.isBottomLeftDetected -> "Bottom-Left"
                    else -> "Bottom-Right"
                }
                "3/4 Detected • Align $missing Corner"
            }
        }

        _uiState.update {
            it.copy(
                isCornersLocked = status.isAllDetected,
                detectedCorners = status.toPointList(),
                cornerStatus = status,
                statusMessage = msg
            )
        }
    }

    fun updateCornersLocked(isLocked: Boolean, corners: List<Point>?) {
        _uiState.update {
            it.copy(
                isCornersLocked = isLocked,
                detectedCorners = corners,
                statusMessage = if (isLocked) "✔ 4 Corner Marks Detected - Auto-Capturing..." else "Position sheet within guide frame"
            )
        }
    }

    fun processCapturedImage(
        bitmap: Bitmap,
        context: Context,
        studentName: String = "",
        studentCode: String = "",
        onGraded: (Long) -> Unit
    ) {
        val exam = _uiState.value.exam ?: return
        val template = _uiState.value.template ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, statusMessage = "Analyzing sheet with OpenCV...") }

            try {
                val output = withContext(Dispatchers.Default) {
                    OmrEngine.processAnswerSheet(bitmap, template.layoutConfig)
                }

                val summary = ScoringEngine.grade(exam, output.rawAnswers)

                // Annotate the rectified bitmap with score, checkmarks ("✔"), wrong marks ("❌"), and answer keys
                val annotatedBitmap = withContext(Dispatchers.Default) {
                    OmrEngine.annotateGradedSheet(
                        rectifiedBitmap = output.rectifiedBitmap,
                        layoutConfig = template.layoutConfig,
                        exam = exam,
                        rawAnswers = output.rawAnswers,
                        scoreSummary = summary
                    )
                }

                // Save annotated image for visual audit
                val imagePath = withContext(Dispatchers.IO) {
                    val scansDir = File(context.filesDir, "scans")
                    if (!scansDir.exists()) scansDir.mkdirs()
                    val file = File(scansDir, "scan_${exam.id}_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        annotatedBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    file.absolutePath
                }

                val scanResult = ScanResultEntity(
                    examId = exam.id,
                    studentName = studentName,
                    studentCode = studentCode,
                    section = exam.section,
                    rawAnswers = output.rawAnswers,
                    ambiguousQuestions = output.ambiguousQuestions,
                    score = summary.earnedScore,
                    totalPoints = summary.totalPoints,
                    percentage = summary.percentage,
                    scannedImagePath = imagePath
                )

                val savedId = repository.saveScanResult(scanResult)

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        scanOutput = output,
                        gradeSummary = summary,
                        lastSavedScanId = savedId,
                        statusMessage = "Scored: ${summary.earnedScore}/${summary.totalPoints} (${summary.percentage.toInt()}%)"
                    )
                }

                onGraded(savedId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        statusMessage = "Scan failed: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }
}
