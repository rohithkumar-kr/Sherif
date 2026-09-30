package com.example.aiinterviewapp.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aiinterviewapp.data.local.dao.InterviewDao
import com.example.aiinterviewapp.data.local.entity.InterviewEntity
import com.example.aiinterviewapp.data.local.entity.LEGACY_LOCAL_OWNER
import com.example.aiinterviewapp.data.local.db.Converters

@Database(
    entities = [InterviewEntity::class],
    version = 3,
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

        /**
         * Adds per-user ownership to existing interviews.
         *
         * Three things this deliberately does **not** do.
         *
         * It does not drop the table. `fallbackToDestructiveMigration` would
         * "work" by deleting every interview the user has, which is data loss
         * dressed as a successful upgrade (RULE 15).
         *
         * It does not leave `userId` nullable. Nullable ownership is the worst
         * of both worlds: queries that forget the predicate would return
         * orphaned rows to whoever happens to be signed in.
         *
         * It does not attribute old rows to the first person who signs in. Their
         * owner is unknown, so they go to [LEGACY_LOCAL_OWNER], which no real
         * Google subject id can equal. The user keeps their data; the next user
         * simply does not see it.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE interviews ADD COLUMN userId TEXT NOT NULL DEFAULT '$LEGACY_LOCAL_OWNER'"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_interviews_userId_date " +
                        "ON interviews (userId, date)"
                )
            }
        }
    }
}
