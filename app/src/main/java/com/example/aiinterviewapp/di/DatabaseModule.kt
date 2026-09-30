package com.example.aiinterviewapp.di

import android.content.Context
import androidx.room.Room
import com.example.aiinterviewapp.data.local.dao.InterviewDao
import com.example.aiinterviewapp.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ai_interview_db"
            // Every migration is listed explicitly. There is deliberately no
            // fallbackToDestructiveMigration: a missing migration would then
            // silently delete a user's interview history, which is data loss
            // rather than an upgrade (RULE 15).
        ).addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3
        ).build()
    }

    @Provides
    @Singleton
    fun provideInterviewDao(database: AppDatabase): InterviewDao {
        return database.interviewDao()
    }
}
