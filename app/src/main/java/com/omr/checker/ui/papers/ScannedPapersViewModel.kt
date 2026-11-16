package com.omr.checker.ui.papers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.entity.ScanResultEntity
import com.omr.checker.data.repository.OMRRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PaperSortOption(val displayName: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    SCORE_DESC("Highest Score"),
    SCORE_ASC("Lowest Score"),
    NAME_ASC("Student Name (A-Z)")
}

data class PaperItemUiModel(
    val result: ScanResultEntity,
    val examTitle: String,
    val examSection: String,
    val totalQuestions: Int,
    val isPass: Boolean
)

data class ScannedPapersSummaryStats(
    val totalCount: Int = 0,
    val averageScore: Float = 0f,
    val averagePercentage: Float = 0f,
    val highestScore: Float = 0f,
    val lowestScore: Float = 0f,
    val passRate: Float = 0f
)

data class ScannedPapersUiState(
    val exams: List<ExamEntity> = emptyList(),
    val selectedExamId: Long? = null,
    val searchQuery: String = "",
    val sortOption: PaperSortOption = PaperSortOption.DATE_DESC,
    val papers: List<PaperItemUiModel> = emptyList(),
    val stats: ScannedPapersSummaryStats = ScannedPapersSummaryStats(),
    val message: String? = null
)

class ScannedPapersViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannedPapersUiState())
    val uiState: StateFlow<ScannedPapersUiState> = _uiState.asStateFlow()

    private val selectedExamIdFlow = MutableStateFlow<Long?>(null)
    private val searchQueryFlow = MutableStateFlow("")
    private val sortOptionFlow = MutableStateFlow(PaperSortOption.DATE_DESC)

    init {
        loadData()
    }

    fun setSelectedExam(examId: Long?) {
        selectedExamIdFlow.value = examId
        _uiState.update { it.copy(selectedExamId = examId) }
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateSortOption(option: PaperSortOption) {
        sortOptionFlow.value = option
        _uiState.update { it.copy(sortOption = option) }
    }

    fun deleteScan(result: ScanResultEntity) {
        viewModelScope.launch {
            try {
                repository.deleteScanResult(result)
                _uiState.update { it.copy(message = "Scanned paper deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "Error deleting: ${e.message}") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                repository.allExams,
                repository.allResults,
                selectedExamIdFlow,
                searchQueryFlow,
                sortOptionFlow
            ) { exams, allResults, examIdFilter, query, sort ->
                val examMap = exams.associateBy { it.id }

                val filteredByExam = if (examIdFilter != null && examIdFilter > 0) {
                    allResults.filter { it.examId == examIdFilter }
                } else {
                    allResults
                }

                val filteredByQuery = if (query.isNotBlank()) {
                    val q = query.trim().lowercase()
                    filteredByExam.filter {
                        it.studentName.lowercase().contains(q) ||
                                it.studentCode.lowercase().contains(q) ||
                                (examMap[it.examId]?.title?.lowercase()?.contains(q) == true)
                    }
                } else {
                    filteredByExam
                }

                val sortedList = when (sort) {
                    PaperSortOption.DATE_DESC -> filteredByQuery.sortedByDescending { it.scannedAt }
                    PaperSortOption.DATE_ASC -> filteredByQuery.sortedBy { it.scannedAt }
                    PaperSortOption.SCORE_DESC -> filteredByQuery.sortedByDescending { it.percentage }
                    PaperSortOption.SCORE_ASC -> filteredByQuery.sortedBy { it.percentage }
                    PaperSortOption.NAME_ASC -> filteredByQuery.sortedBy { it.studentName.ifBlank { "zzz" }.lowercase() }
                }

                val uiModels = sortedList.map { result ->
                    val exam = examMap[result.examId]
                    PaperItemUiModel(
                        result = result,
                        examTitle = exam?.title ?: "Exam #${result.examId}",
                        examSection = exam?.section ?: result.section,
                        totalQuestions = exam?.answerKey?.size ?: result.rawAnswers.size,
                        isPass = result.percentage >= 60f
                    )
                }

                val count = filteredByExam.size
                val stats = if (count > 0) {
                    val scores = filteredByExam.map { it.score }
                    val pcts = filteredByExam.map { it.percentage }
                    val passCount = filteredByExam.count { it.percentage >= 60f }

                    ScannedPapersSummaryStats(
                        totalCount = count,
                        averageScore = scores.average().toFloat(),
                        averagePercentage = pcts.average().toFloat(),
                        highestScore = scores.maxOrNull() ?: 0f,
                        lowestScore = scores.minOrNull() ?: 0f,
                        passRate = (passCount.toFloat() / count) * 100f
                    )
                } else {
                    ScannedPapersSummaryStats()
                }

                _uiState.update {
                    it.copy(
                        exams = exams,
                        papers = uiModels,
                        stats = stats
                    )
                }
            }.collect {}
        }
    }
}
