package com.example.aiinterviewapp.di

import com.example.aiinterviewapp.data.repository.AuthRepositoryImpl
import com.example.aiinterviewapp.data.repository.InterviewRepositoryImpl
import com.example.aiinterviewapp.domain.repository.AuthRepository
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindInterviewRepository(
        interviewRepositoryImpl: InterviewRepositoryImpl
    ): InterviewRepository

    /**
     * Binds the session repository to its single implementation.
     *
     * Without this, Hilt cannot satisfy `AuthRepository` at the two injection
     * sites that need it: `LoginViewModel`, which starts a session, and
     * `MainActivity`, which ends one.
     */
    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        authRepositoryImpl: AuthRepositoryImpl
    ): AuthRepository
}
