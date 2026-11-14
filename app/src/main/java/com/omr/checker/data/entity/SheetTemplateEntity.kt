package com.omr.checker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.omr.checker.data.model.SheetLayoutConfig

@Entity(tableName = "sheet_templates")
data class SheetTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val totalItems: Int = 60,
    val optionsPerItem: Int = 4, // 4 = A..D, 5 = A..E
    val isDefault: Boolean = false,
    val layoutConfig: SheetLayoutConfig,
    val createdAt: Long = System.currentTimeMillis()
)
