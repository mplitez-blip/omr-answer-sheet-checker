package com.omr.checker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omr.checker.data.converter.RoomConverters
import com.omr.checker.data.dao.ExamDao
import com.omr.checker.data.dao.ScanResultDao
import com.omr.checker.data.dao.TemplateDao
import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.data.entity.ScanResultEntity
import com.omr.checker.data.entity.SheetTemplateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        SheetTemplateEntity::class,
        ExamEntity::class,
        ScanResultEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun templateDao(): TemplateDao
    abstract fun examDao(): ExamDao
    abstract fun scanResultDao(): ScanResultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "omr_checker.db"
            ).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Pre-populate with default 60-item answer sheet template
                    CoroutineScope(Dispatchers.IO).launch {
                        getInstance(context)
                            .templateDao()
                            .insert(DefaultTemplates.createDefault60TemplateEntity())
                    }
                }
            }).build()
        }
    }
}
