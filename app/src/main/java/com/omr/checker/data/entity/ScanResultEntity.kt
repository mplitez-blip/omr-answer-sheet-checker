package com.omr.checker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_results",
    foreignKeys = [
        ForeignKey(
            entity = ExamEntity::class,
            parentColumns = ["id"],
            childColumns = ["examId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("examId")
    ]
)
data class ScanResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val examId: Long,
    val studentName: String = "",
    val studentCode: String = "",
    val section: String = "",
    val rawAnswers: Map<Int, Char?>, // Question (1-based) -> Mark ('A', 'B', ...) or null if blank
    val ambiguousQuestions: List<Int> = emptyList(), // Questions with multiple or dubious marks
    val score: Float,
    val totalPoints: Float,
    val percentage: Float = if (totalPoints > 0) (score / totalPoints) * 100f else 0f,
    val scannedImagePath: String? = null,
    val scannedAt: Long = System.currentTimeMillis()
)
