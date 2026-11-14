package com.omr.checker.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.entity.ScanResultEntity
import com.omr.checker.data.repository.OMRRepository
import com.omr.checker.domain.ScoringEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanResultUiState(
    val scanResult: ScanResultEntity? = null,
    val exam: ExamEntity? = null,
    val studentName: String = "",
    val studentCode: String = "",
    val rawAnswers: Map<Int, Char?> = emptyMap(),
    val scoreSummary: ScoringEngine.ScoreSummary? = null,
    val message: String? = null
)

class ScanResultViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanResultUiState())
    val uiState: StateFlow<ScanResultUiState> = _uiState.asStateFlow()

    fun loadResult(scanResultId: Long) {
        viewModelScope.launch {
            val result = repository.getScanResultById(scanResultId) ?: return@launch
            val exam = repository.getExamById(result.examId) ?: return@launch
            val summary = ScoringEngine.grade(exam, result.rawAnswers)

            _uiState.update {
                it.copy(
                    scanResult = result,
                    exam = exam,
                    studentName = result.studentName,
                    studentCode = result.studentCode,
                    rawAnswers = result.rawAnswers,
                    scoreSummary = summary
                )
            }
        }
    }

    fun updateStudentName(name: String) {
        _uiState.update { it.copy(studentName = name) }
    }

    fun updateStudentCode(code: String) {
        _uiState.update { it.copy(studentCode = code) }
    }

    fun overrideAnswer(question: Int, newAnswer: Char?) {
        val state = _uiState.value
        val exam = state.exam ?: return

        val updatedAnswers = state.rawAnswers.toMutableMap()
        if (newAnswer == null) {
            updatedAnswers.remove(question)
        } else {
            updatedAnswers[question] = newAnswer
        }

        val newSummary = ScoringEngine.grade(exam, updatedAnswers)

        _uiState.update {
            it.copy(
                rawAnswers = updatedAnswers,
                scoreSummary = newSummary
            )
        }
    }

    fun saveChanges(onSaved: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val existing = state.scanResult ?: return@launch
            val summary = state.scoreSummary ?: return@launch

            val updated = existing.copy(
                studentName = state.studentName,
                studentCode = state.studentCode,
                rawAnswers = state.rawAnswers,
                score = summary.earnedScore,
                totalPoints = summary.totalPoints,
                percentage = summary.percentage
            )

            repository.updateScanResult(updated)
            _uiState.update { it.copy(message = "Saved!") }
            onSaved()
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
