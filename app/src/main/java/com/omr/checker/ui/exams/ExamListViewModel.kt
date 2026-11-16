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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExamWithStats(
    val exam: ExamEntity,
    val templateName: String = "",
    val totalScans: Int = 0,
    val averageScore: Float? = null,
    val mps: Float? = null
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
            combine(repository.allExams, repository.allTemplates, repository.allResults) { exams, templates, results ->
                val templateMap = templates.associateBy { it.id }
                val resultsByExam = results.groupBy { it.examId }
                exams.map { exam ->
                    val tpl = templateMap[exam.templateId]
                    val examResults = resultsByExam[exam.id] ?: emptyList()
                    val avg = if (examResults.isNotEmpty()) examResults.map { it.score }.average().toFloat() else null
                    val mps = if (examResults.isNotEmpty()) examResults.map { it.percentage }.average().toFloat() else null
                    ExamWithStats(
                        exam = exam,
                        templateName = tpl?.name ?: "Template #${exam.templateId}",
                        totalScans = examResults.size,
                        averageScore = avg,
                        mps = mps
                    )
                }
            }.collect { list ->
                _uiState.update { it.copy(examsWithStats = list) }
            }
        }
    }

    fun createDefaultExam(title: String, section: String, templateId: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val template = repository.getTemplateById(templateId) ?: repository.ensureDefaultTemplate()
            val totalItems = template.totalItems
            val optionsCount = template.optionsPerItem.coerceIn(2, 6)
            val options = (0 until optionsCount).map { ('A' + it) }

            // Default sample answer key (cycling, easily editable)
            val defaultKey = mutableMapOf<Int, Char>()
            for (q in 1..totalItems) {
                defaultKey[q] = options[(q - 1) % optionsCount]
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
            _uiState.update { it.copy(message = "Exam '$title' created with template '${template.name}' ($totalItems items)!") }
            onCreated(examId)
        }
    }

    fun duplicateExam(
        originalExam: ExamEntity,
        newSection: String,
        newTitle: String = originalExam.title,
        onCreated: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val randomSuffix = (100..999).random()
            val examCode = "EXAM-${(System.currentTimeMillis() % 10000)}$randomSuffix"
            val sectionName = newSection.trim()
            val duplicatedExam = ExamEntity(
                templateId = originalExam.templateId,
                title = newTitle.ifBlank { originalExam.title },
                section = sectionName,
                date = originalExam.date,
                sheetCode = examCode,
                answerKey = originalExam.answerKey,
                pointsPerItem = originalExam.pointsPerItem
            )

            val newId = repository.saveExam(duplicatedExam)
            val secMsg = if (sectionName.isNotBlank()) " for $sectionName" else ""
            _uiState.update {
                it.copy(message = "Duplicated '${duplicatedExam.title}'$secMsg with identical answer key!")
            }
            onCreated(newId)
        }
    }

    fun updateExamTemplate(exam: ExamEntity, newTemplateId: Long) {
        viewModelScope.launch {
            val template = repository.getTemplateById(newTemplateId) ?: return@launch
            val totalItems = template.totalItems
            val optionsCount = template.optionsPerItem.coerceIn(2, 6)
            val options = (0 until optionsCount).map { ('A' + it) }

            val updatedKey = exam.answerKey.filterKeys { it in 1..totalItems }.toMutableMap()
            for (q in 1..totalItems) {
                if (!updatedKey.containsKey(q)) {
                    updatedKey[q] = options[(q - 1) % optionsCount]
                }
            }

            val updatedExam = exam.copy(
                templateId = template.id,
                answerKey = updatedKey
            )
            repository.saveExam(updatedExam)
            _uiState.update { it.copy(message = "Exam '${exam.title}' updated to '${template.name}' ($totalItems items)") }
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
