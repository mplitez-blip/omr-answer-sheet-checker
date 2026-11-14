package com.omr.checker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exams",
    foreignKeys = [
        ForeignKey(
            entity = SheetTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("templateId"),
        Index("sheetCode", unique = true)
    ]
)
data class ExamEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val templateId: Long,
    val title: String,
    val section: String = "",
    val date: String = "",
    val sheetCode: String, // Machine-readable or printed code, e.g. "MATH-01"
    val answerKey: Map<Int, Char>, // Item number (1-based) -> correct option ('A', 'B', etc.)
    val pointsPerItem: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis()
)
