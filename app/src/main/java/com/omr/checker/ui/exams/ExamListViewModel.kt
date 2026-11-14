package com.omr.checker.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.entity.SheetTemplateEntity
import com.omr.checker.data.repository.OMRRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExamWithStats(
    val exam: ExamEntity,
    val totalScans: Int = 0,
    val averageScore: Float? = null
)

data class ExamListUiState(
    val examsWithStats: List<ExamWithStats> = emptyList(),
    val templates: List<SheetTemplateEntity> = emptyList(),
    val isCreatingExam: Boolean = false,
    val message: String? = null
)

class ExamListViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExamListUiState())
    val uiState: StateFlow<ExamListUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            repository.allTemplates.collect { templates ->
                _uiState.update { it.copy(templates = templates) }
            }
        }

        viewModelScope.launch {
            repository.allExams.collect { exams ->
                val list = exams.map { exam ->
                    ExamWithStats(exam = exam)
                }
                _uiState.update { it.copy(examsWithStats = list) }
            }
        }
    }

    fun createDefaultExam(title: String, section: String, templateId: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val template = repository.getTemplateById(templateId) ?: repository.ensureDefaultTemplate()
            val totalItems = template.totalItems

            // Default sample answer key (A, B, C, D cycling, easily editable)
            val defaultKey = mutableMapOf<Int, Char>()
            val options = listOf('A', 'B', 'C', 'D')
            for (q in 1..totalItems) {
                defaultKey[q] = options[(q - 1) % 4]
            }

            val examCode = "EXAM-${System.currentTimeMillis() % 10000}"
            val exam = ExamEntity(
                templateId = template.id,
                title = title.ifBlank { "Untitled Exam" },
                section = section,
                sheetCode = examCode,
                answerKey = defaultKey,
                pointsPerItem = 1.0f
            )

            val examId = repository.saveExam(exam)
            _uiState.update { it.copy(message = "Exam '$title' created!") }
            onCreated(examId)
        }
    }

    fun deleteExam(exam: ExamEntity) {
        viewModelScope.launch {
            repository.deleteExam(exam)
            _uiState.update { it.copy(message = "Exam deleted") }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
