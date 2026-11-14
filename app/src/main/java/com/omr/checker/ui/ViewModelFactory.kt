package com.omr.checker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.omr.checker.data.repository.OMRRepository
import com.omr.checker.ui.designer.SheetDesignerViewModel
import com.omr.checker.ui.exams.AnswerKeyViewModel
import com.omr.checker.ui.exams.ExamListViewModel
import com.omr.checker.ui.results.ScanResultViewModel
import com.omr.checker.ui.scanner.ScannerViewModel
import com.omr.checker.ui.templates.TemplateListViewModel

class ViewModelFactory(
    private val repository: OMRRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(TemplateListViewModel::class.java) -> {
                TemplateListViewModel(repository) as T
            }
            modelClass.isAssignableFrom(SheetDesignerViewModel::class.java) -> {
                SheetDesignerViewModel(repository) as T
            }
            modelClass.isAssignableFrom(ExamListViewModel::class.java) -> {
                ExamListViewModel(repository) as T
            }
            modelClass.isAssignableFrom(AnswerKeyViewModel::class.java) -> {
                AnswerKeyViewModel(repository) as T
            }
            modelClass.isAssignableFrom(ScannerViewModel::class.java) -> {
                ScannerViewModel(repository) as T
            }
            modelClass.isAssignableFrom(ScanResultViewModel::class.java) -> {
                ScanResultViewModel(repository) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
