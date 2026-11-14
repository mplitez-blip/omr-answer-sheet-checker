package com.omr.checker.ui.templates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.data.entity.SheetTemplateEntity
import com.omr.checker.data.repository.OMRRepository
import com.omr.checker.generator.PdfSheetGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TemplateListUiState(
    val isExporting: Boolean = false,
    val exportedPdf: File? = null,
    val message: String? = null
)

class TemplateListViewModel(
    private val repository: OMRRepository
) : ViewModel() {

    val templates: StateFlow<List<SheetTemplateEntity>> = repository.allTemplates
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(TemplateListUiState())
    val uiState: StateFlow<TemplateListUiState> = _uiState.asStateFlow()

    init {
        ensureDefaultTemplate()
    }

    private fun ensureDefaultTemplate() {
        viewModelScope.launch {
            repository.ensureDefaultTemplate()
        }
    }

    fun exportTemplatePdf(context: Context, template: SheetTemplateEntity, onReady: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val cleanName = template.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val outputFile = File(context.cacheDir, "${cleanName}_Sheet.pdf")

            withContext(Dispatchers.IO) {
                PdfSheetGenerator.generatePdf(
                    context = context,
                    layoutConfig = template.layoutConfig,
                    sheetTitle = template.name,
                    sheetCode = "DEF-60",
                    outputFile = outputFile
                )
            }

            _uiState.update { it.copy(isExporting = false, exportedPdf = outputFile) }
            onReady(outputFile)
        }
    }

    fun deleteTemplate(template: SheetTemplateEntity) {
        viewModelScope.launch {
            if (!template.isDefault) {
                repository.deleteTemplate(template)
                _uiState.update { it.copy(message = "Template deleted") }
            } else {
                _uiState.update { it.copy(message = "Default template cannot be deleted") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
