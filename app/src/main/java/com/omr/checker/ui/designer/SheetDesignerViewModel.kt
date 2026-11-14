package com.omr.checker.ui.designer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.database.DefaultTemplates
import com.omr.checker.data.entity.SheetTemplateEntity
import com.omr.checker.data.model.BubbleGridConfig
import com.omr.checker.data.model.ElementType
import com.omr.checker.data.model.SheetElement
import com.omr.checker.data.model.SheetLayoutConfig
import com.omr.checker.data.repository.OMRRepository
import com.omr.checker.generator.PdfSheetGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DesignerUiState(
    val templateId: Long = 0,
    val templateName: String = "Custom Answer Sheet",
    val elements: List<SheetElement> = emptyList(),
    val selectedElementId: String? = null,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportedPdf: File? = null,
    val userMessage: String? = null
)

class SheetDesignerViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DesignerUiState())
    val uiState: StateFlow<DesignerUiState> = _uiState.asStateFlow()

    init {
        loadDefaultTemplate()
    }

    fun loadDefaultTemplate() {
        val defaultLayout = DefaultTemplates.createDefault60Layout()
        _uiState.update {
            it.copy(
                templateId = 0,
                templateName = "60-Item Answer Sheet",
                elements = defaultLayout.elements,
                selectedElementId = null
            )
        }
    }

    fun loadTemplate(templateId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val template = repository.getTemplateById(templateId)
            if (template != null) {
                _uiState.update {
                    it.copy(
                        templateId = template.id,
                        templateName = template.name,
                        elements = template.layoutConfig.elements,
                        isLoading = false,
                        selectedElementId = null
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun updateTemplateName(name: String) {
        _uiState.update { it.copy(templateName = name) }
    }

    fun selectElement(id: String?) {
        _uiState.update { it.copy(selectedElementId = id) }
    }

    fun updateElementPosition(id: String, deltaNormX: Float, deltaNormY: Float) {
        _uiState.update { state ->
            val updated = state.elements.map { el ->
                if (el.id == id) {
                    val newX = (el.x + deltaNormX).coerceIn(0.04f, 0.96f - el.width)
                    val newY = (el.y + deltaNormY).coerceIn(0.04f, 0.96f - el.height)
                    el.copy(x = newX, y = newY)
                } else el
            }
            state.copy(elements = updated)
        }
    }

    fun updateElementLabel(id: String, newLabel: String) {
        _uiState.update { state ->
            val updated = state.elements.map { el ->
                if (el.id == id) el.copy(label = newLabel) else el
            }
            state.copy(elements = updated)
        }
    }

    fun updateGridConfig(id: String, questions: Int, columns: Int) {
        _uiState.update { state ->
            val updated = state.elements.map { el ->
                if (el.id == id && el.type == ElementType.BUBBLE_GRID) {
                    val newConfig = BubbleGridConfig(
                        startQuestion = 1,
                        endQuestion = questions.coerceIn(10, 100),
                        options = listOf('A', 'B', 'C', 'D'),
                        columns = columns.coerceIn(1, 4)
                    )
                    el.copy(
                        label = "Questions (1-$questions)",
                        gridConfig = newConfig
                    )
                } else el
            }
            state.copy(elements = updated)
        }
    }

    fun addElement(type: ElementType) {
        val newElement = when (type) {
            ElementType.TEST_NAME -> SheetElement(
                type = ElementType.TEST_NAME,
                label = "Test Name",
                x = 0.08f, y = 0.06f, width = 0.54f, height = 0.045f
            )
            ElementType.ANSWER_SHEET_CODE -> SheetElement(
                type = ElementType.ANSWER_SHEET_CODE,
                label = "Sheet Code",
                x = 0.65f, y = 0.06f, width = 0.27f, height = 0.045f
            )
            ElementType.STUDENT_NAME -> SheetElement(
                type = ElementType.STUDENT_NAME,
                label = "Student Name",
                x = 0.08f, y = 0.115f, width = 0.54f, height = 0.04f
            )
            ElementType.DATE -> SheetElement(
                type = ElementType.DATE,
                label = "Date",
                x = 0.65f, y = 0.115f, width = 0.27f, height = 0.04f
            )
            ElementType.SECTION -> SheetElement(
                type = ElementType.SECTION,
                label = "Section",
                x = 0.08f, y = 0.165f, width = 0.40f, height = 0.04f
            )
            ElementType.BUBBLE_GRID -> SheetElement(
                type = ElementType.BUBBLE_GRID,
                label = "Questions (1-60)",
                x = 0.08f, y = 0.22f, width = 0.84f, height = 0.69f,
                gridConfig = BubbleGridConfig(1, 60, listOf('A', 'B', 'C', 'D'), 2)
            )
        }

        _uiState.update { state ->
            state.copy(
                elements = state.elements + newElement,
                selectedElementId = newElement.id
            )
        }
    }

    fun removeElement(id: String) {
        _uiState.update { state ->
            state.copy(
                elements = state.elements.filterNot { it.id == id },
                selectedElementId = if (state.selectedElementId == id) null else state.selectedElementId
            )
        }
    }

    fun saveTemplate(onSuccess: (Long) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val bubbleGrid = state.elements.firstOrNull { it.type == ElementType.BUBBLE_GRID }
            val totalQuestions = bubbleGrid?.gridConfig?.let { it.endQuestion - it.startQuestion + 1 } ?: 60

            val layoutConfig = SheetLayoutConfig(
                pageWidth = 2480,
                pageHeight = 3508,
                cornerAnchorSizeFraction = 0.04f,
                elements = state.elements
            )

            val templateEntity = SheetTemplateEntity(
                id = state.templateId,
                name = state.templateName.ifBlank { "Custom Answer Sheet" },
                totalItems = totalQuestions,
                optionsPerItem = 4,
                isDefault = false,
                layoutConfig = layoutConfig
            )

            val savedId = repository.saveTemplate(templateEntity)
            _uiState.update { it.copy(templateId = savedId, userMessage = "Template saved successfully!") }
            onSuccess(savedId)
        }
    }

    fun exportPdf(context: Context, onReady: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val state = _uiState.value
            val layoutConfig = SheetLayoutConfig(
                pageWidth = 2480,
                pageHeight = 3508,
                cornerAnchorSizeFraction = 0.04f,
                elements = state.elements
            )

            val fileName = "${state.templateName.replace("[^a-zA-Z0-9]".toRegex(), "_")}_Sheet.pdf"
            val outputFile = File(context.cacheDir, fileName)

            withContext(Dispatchers.IO) {
                PdfSheetGenerator.generatePdf(
                    context = context,
                    layoutConfig = layoutConfig,
                    sheetTitle = state.templateName,
                    sheetCode = "SHEET-60",
                    outputFile = outputFile
                )
            }

            _uiState.update { it.copy(isExporting = false, exportedPdf = outputFile) }
            onReady(outputFile)
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
