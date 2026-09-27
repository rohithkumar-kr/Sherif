package com.example.aiinterviewapp.di

import com.example.aiinterviewapp.BuildConfig
import com.example.aiinterviewapp.data.remote.api.GeminiApi
import com.example.aiinterviewapp.utils.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        prettyPrint = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("x-goog-api-key")
        }

        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                .build()
            chain.proceed(request)
        }

        val retryInterceptor = Interceptor { chain ->
            var response = chain.proceed(chain.request())
            var tryCount = 0
            val maxLimit = 3

            // Retry rate-limit (429) and transient server errors (5xx) with
            // exponential backoff. Requests are never retried when the body
            // has already been consumed by an upstream interceptor.
            val requestBody = chain.request().body
            while (
                !response.isSuccessful &&
                tryCount < maxLimit &&
                (response.code == 429 || response.code in 500..599) &&
                (requestBody == null || !requestBody.isOneShot())
            ) {
                tryCount++
                val waitTime = Math.pow(2.0, tryCount.toDouble()).toLong() * 1000
                Thread.sleep(waitTime)
                response.close()
                response = chain.proceed(chain.request())
            }
            response
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(okHttpClient: OkHttpClient, json: Json): GeminiApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GeminiApi::class.java)
    }
}
