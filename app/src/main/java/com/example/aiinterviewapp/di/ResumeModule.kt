package com.example.aiinterviewapp.di

import com.example.aiinterviewapp.data.repository.ResumeAnalysisRepositoryImpl
import com.example.aiinterviewapp.data.service.ResumeService
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.repository.ResumeAnalysisRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ResumeModule {

    @Binds
    @Singleton
    abstract fun bindResumeAnalysisRepository(
        resumeAnalysisRepositoryImpl: ResumeAnalysisRepositoryImpl
    ): ResumeAnalysisRepository

    @Binds
    @Singleton
    abstract fun bindResumeTextSource(
        resumeService: ResumeService
    ): ResumeTextSource
}
