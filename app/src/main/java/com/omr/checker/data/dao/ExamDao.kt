package com.omr.checker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omr.checker.data.entity.ExamEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exam: ExamEntity): Long

    @Update
    suspend fun update(exam: ExamEntity)

    @Delete
    suspend fun delete(exam: ExamEntity)

    @Query("SELECT * FROM exams WHERE id = :id")
    suspend fun getExamById(id: Long): ExamEntity?

    @Query("SELECT * FROM exams WHERE id = :id")
    fun getExamByIdFlow(id: Long): Flow<ExamEntity?>

    @Query("SELECT * FROM exams WHERE sheetCode = :sheetCode LIMIT 1")
    suspend fun getExamBySheetCode(sheetCode: String): ExamEntity?

    @Query("SELECT * FROM exams ORDER BY createdAt DESC")
    fun getAllExamsFlow(): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams WHERE templateId = :templateId ORDER BY createdAt DESC")
    fun getExamsByTemplateIdFlow(templateId: Long): Flow<List<ExamEntity>>

    @Query("SELECT COUNT(*) FROM exams")
    suspend fun getExamCount(): Int
}
