package com.example.aiinterviewapp.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aiinterviewapp.data.local.dao.InterviewDao
import com.example.aiinterviewapp.data.local.entity.InterviewEntity
import com.example.aiinterviewapp.data.local.db.Converters

@Database(
    entities = [InterviewEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun interviewDao(): InterviewDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_interviews_date ON interviews (date)")
            }
        }
    }
}
