package com.omr.checker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omr.checker.data.entity.ScanResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ScanResultEntity): Long

    @Update
    suspend fun update(result: ScanResultEntity)

    @Delete
    suspend fun delete(result: ScanResultEntity)

    @Query("SELECT * FROM scan_results WHERE id = :id")
    suspend fun getScanResultById(id: Long): ScanResultEntity?

    @Query("SELECT * FROM scan_results WHERE id = :id")
    fun getScanResultByIdFlow(id: Long): Flow<ScanResultEntity?>

    @Query("SELECT * FROM scan_results WHERE examId = :examId ORDER BY scannedAt DESC")
    fun getResultsForExamFlow(examId: Long): Flow<List<ScanResultEntity>>

    @Query("SELECT * FROM scan_results ORDER BY scannedAt DESC")
    fun getAllResultsFlow(): Flow<List<ScanResultEntity>>

    @Query("SELECT COUNT(*) FROM scan_results WHERE examId = :examId")
    suspend fun getScanCountForExam(examId: Long): Int

    @Query("SELECT AVG(score) FROM scan_results WHERE examId = :examId")
    suspend fun getAverageScoreForExam(examId: Long): Float?

    @Query("DELETE FROM scan_results WHERE examId = :examId")
    suspend fun deleteResultsForExam(examId: Long)
}
