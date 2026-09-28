package com.example.aiinterviewapp.di

import com.example.aiinterviewapp.data.ocr.MlKitResumeOcrEngine
import com.example.aiinterviewapp.data.repository.DefaultResumeTextExtractor
import com.example.aiinterviewapp.data.repository.ResumeAnalysisRepositoryImpl
import com.example.aiinterviewapp.data.service.ResumeService
import com.example.aiinterviewapp.data.service.ResumeTextSource
import com.example.aiinterviewapp.domain.ocr.ResumeOcrEngine
import com.example.aiinterviewapp.domain.repository.ResumeAnalysisRepository
import com.example.aiinterviewapp.domain.repository.ResumeTextExtractor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    /**
     * Production OCR. Tests bind a fake engine instead, so the unit suite never
     * depends on a native ML Kit model being installed.
     */
    @Binds
    @Singleton
    abstract fun bindResumeOcrEngine(
        mlKitResumeOcrEngine: MlKitResumeOcrEngine
    ): ResumeOcrEngine

    @Binds
    @Singleton
    abstract fun bindResumeTextExtractor(
        defaultResumeTextExtractor: DefaultResumeTextExtractor
    ): ResumeTextExtractor

    companion object {

        /**
         * File and bitmap work is bound to IO explicitly rather than inherited
         * from the caller's dispatcher, so the extractor stays off the main
         * thread no matter which screen invokes it.
         */
        @Provides
        @Singleton
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
