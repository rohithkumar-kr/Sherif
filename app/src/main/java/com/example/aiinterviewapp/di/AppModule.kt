package com.example.aiinterviewapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import com.example.aiinterviewapp.data.local.datastore.DataStoreResumeProfileStore
import com.example.aiinterviewapp.data.local.datastore.dataStore
import com.example.aiinterviewapp.data.service.PdfExportService
import com.example.aiinterviewapp.data.service.ResumeService
import com.example.aiinterviewapp.data.service.VoiceService
import com.example.aiinterviewapp.domain.repository.ResumeProfileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAuthPreferences(@ApplicationContext context: Context): AuthPreferences {
        return AuthPreferences(context)
    }

    @Provides
    @Singleton
    fun provideVoiceService(@ApplicationContext context: Context): VoiceService {
        return VoiceService(context)
    }

    @Provides
    @Singleton
    fun provideResumeService(@ApplicationContext context: Context): ResumeService {
        return ResumeService(context)
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideResumeProfileStore(
        dataStore: DataStore<Preferences>,
        json: Json
    ): ResumeProfileStore = DataStoreResumeProfileStore(dataStore, json)

    @Provides
    @Singleton
    fun providePdfExportService(@ApplicationContext context: Context): PdfExportService {
        return PdfExportService(context)
    }
}
