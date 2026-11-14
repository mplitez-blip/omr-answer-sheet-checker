package com.omr.checker.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.repository.OMRRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnswerKeyUiState(
    val examId: Long = 0,
    val examTitle: String = "",
    val totalQuestions: Int = 60,
    val answerKey: Map<Int, Char> = emptyMap(),
    val pointsPerItem: Float = 1.0f,
    val isSaved: Boolean = false,
    val message: String? = null
)

class AnswerKeyViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnswerKeyUiState())
    val uiState: StateFlow<AnswerKeyUiState> = _uiState.asStateFlow()

    fun loadExam(examId: Long) {
        viewModelScope.launch {
            val exam = repository.getExamById(examId) ?: return@launch
            val template = repository.getTemplateById(exam.templateId)
            val totalQ = template?.totalItems ?: exam.answerKey.size.coerceAtLeast(60)

            _uiState.update {
                it.copy(
                    examId = exam.id,
                    examTitle = exam.title,
                    totalQuestions = totalQ,
                    answerKey = exam.answerKey,
                    pointsPerItem = exam.pointsPerItem
                )
            }
        }
    }

    fun setAnswer(question: Int, option: Char) {
        _uiState.update { state ->
            val updated = state.answerKey.toMutableMap()
            updated[question] = option
            state.copy(answerKey = updated)
        }
    }

    fun saveAnswerKey(onSaved: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val existing = repository.getExamById(state.examId) ?: return@launch
            val updated = existing.copy(
                answerKey = state.answerKey,
                pointsPerItem = state.pointsPerItem
            )
            repository.saveExam(updated)
            _uiState.update { it.copy(isSaved = true, message = "Answer key saved!") }
            onSaved()
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
