package com.omr.checker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omr.checker.data.entity.SheetTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: SheetTemplateEntity): Long

    @Update
    suspend fun update(template: SheetTemplateEntity)

    @Delete
    suspend fun delete(template: SheetTemplateEntity)

    @Query("SELECT * FROM sheet_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): SheetTemplateEntity?

    @Query("SELECT * FROM sheet_templates WHERE id = :id")
    fun getTemplateByIdFlow(id: Long): Flow<SheetTemplateEntity?>

    @Query("SELECT * FROM sheet_templates ORDER BY isDefault DESC, createdAt DESC")
    fun getAllTemplatesFlow(): Flow<List<SheetTemplateEntity>>

    @Query("SELECT * FROM sheet_templates WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultTemplate(): SheetTemplateEntity?

    @Query("SELECT COUNT(*) FROM sheet_templates")
    suspend fun getTemplateCount(): Int
}
