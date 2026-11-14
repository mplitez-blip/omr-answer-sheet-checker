package com.omr.checker.data.repository

import com.omr.checker.data.database.AppDatabase
import com.omr.checker.data.database.DefaultTemplates
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.entity.ScanResultEntity
import com.omr.checker.data.entity.SheetTemplateEntity
import kotlinx.coroutines.flow.Flow

class OMRRepository(private val database: AppDatabase) {

    private val templateDao = database.templateDao()
    private val examDao = database.examDao()
    private val scanResultDao = database.scanResultDao()

    // --- Templates ---
    val allTemplates: Flow<List<SheetTemplateEntity>> = templateDao.getAllTemplatesFlow()

    suspend fun getTemplateById(id: Long): SheetTemplateEntity? =
        templateDao.getTemplateById(id)

    fun getTemplateByIdFlow(id: Long): Flow<SheetTemplateEntity?> =
        templateDao.getTemplateByIdFlow(id)

    suspend fun saveTemplate(template: SheetTemplateEntity): Long =
        templateDao.insert(template)

    suspend fun deleteTemplate(template: SheetTemplateEntity) =
        templateDao.delete(template)

    suspend fun ensureDefaultTemplate(): SheetTemplateEntity {
        val existing = templateDao.getDefaultTemplate()
        val defaultTemplate = DefaultTemplates.createDefault60TemplateEntity()
        if (existing != null) {
            val grid = existing.layoutConfig.elements.firstOrNull { it.type == com.omr.checker.data.model.ElementType.BUBBLE_GRID }
            if (grid?.gridConfig?.columns != 3) {
                val updated = existing.copy(layoutConfig = defaultTemplate.layoutConfig)
                templateDao.update(updated)
                return updated
            }
            return existing
        }
        val id = templateDao.insert(defaultTemplate)
        return defaultTemplate.copy(id = id)
    }

    // --- Exams ---
    val allExams: Flow<List<ExamEntity>> = examDao.getAllExamsFlow()

    fun getExamsForTemplate(templateId: Long): Flow<List<ExamEntity>> =
        examDao.getExamsByTemplateIdFlow(templateId)

    suspend fun getExamById(id: Long): ExamEntity? =
        examDao.getExamById(id)

    fun getExamByIdFlow(id: Long): Flow<ExamEntity?> =
        examDao.getExamByIdFlow(id)

    suspend fun getExamBySheetCode(code: String): ExamEntity? =
        examDao.getExamBySheetCode(code)

    suspend fun saveExam(exam: ExamEntity): Long =
        examDao.insert(exam)

    suspend fun deleteExam(exam: ExamEntity) =
        examDao.delete(exam)

    // --- Scan Results ---
    val allResults: Flow<List<ScanResultEntity>> = scanResultDao.getAllResultsFlow()

    fun getResultsForExam(examId: Long): Flow<List<ScanResultEntity>> =
        scanResultDao.getResultsForExamFlow(examId)

    suspend fun getScanResultById(id: Long): ScanResultEntity? =
        scanResultDao.getScanResultById(id)

    fun getScanResultByIdFlow(id: Long): Flow<ScanResultEntity?> =
        scanResultDao.getScanResultByIdFlow(id)

    suspend fun saveScanResult(result: ScanResultEntity): Long =
        scanResultDao.insert(result)

    suspend fun updateScanResult(result: ScanResultEntity) =
        scanResultDao.update(result)

    suspend fun deleteScanResult(result: ScanResultEntity) =
        scanResultDao.delete(result)

    suspend fun deleteResultsForExam(examId: Long) =
        scanResultDao.deleteResultsForExam(examId)

    // --- Scoring Helper ---
    /**
     * Calculates score and total points based on exam answer key.
     * Returns Pair(earnedScore, totalPossibleScore).
     */
    fun calculateScore(
        exam: ExamEntity,
        rawAnswers: Map<Int, Char?>
    ): Pair<Float, Float> {
        val summary = com.omr.checker.domain.ScoringEngine.grade(exam, rawAnswers)
        return Pair(summary.earnedScore, summary.totalPoints)
    }
}
