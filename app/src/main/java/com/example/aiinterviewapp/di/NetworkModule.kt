package com.example.aiinterviewapp.di

import com.example.aiinterviewapp.BuildConfig
import com.example.aiinterviewapp.data.local.datastore.SessionStore
import com.example.aiinterviewapp.data.local.datastore.SessionTokenCache
import com.example.aiinterviewapp.data.remote.RetryInterceptor
import com.example.aiinterviewapp.data.remote.SessionAuthInterceptor
import com.example.aiinterviewapp.data.remote.SessionExpiryInterceptor
import com.example.aiinterviewapp.data.remote.api.SherifBackendApi
import com.example.aiinterviewapp.utils.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
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
    fun provideOkHttpClient(
        authInterceptor: SessionAuthInterceptor,
        retryInterceptor: RetryInterceptor,
        sessionExpiryInterceptor: SessionExpiryInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            // The session token is the credential now, so it gets the same
            // redaction the Gemini key used to get. RULE 12: no credential in
            // a log line, in any build.
            redactHeader("Authorization")
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            // Order is load-bearing.
            //
            // Auth runs first so the retry loop below re-sends the request with
            // its Authorization header already attached, rather than replaying
            // the unauthenticated original.
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            // Last, so it only ever sees a final response. Putting it earlier
            // would report an expiry for a 401 that the retry loop was about to
            // deal with, and sign a user out over a recoverable request.
            .addInterceptor(sessionExpiryInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // The read timeout is 60s and the retry budget adds at most two
            // backoffs, so the call timeout has to leave room for all three
            // attempts. At 90s the third attempt can still land inside it.
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The token mirror OkHttp reads synchronously.
     *
     * Built from [SessionStore.accessToken] rather than being written to by the
     * repository, so sign-in, sign-out and expiry all update it through the one
     * path that already owns the value. A cache maintained by hand alongside
     * the store is a cache that eventually disagrees with it.
     */
    @Provides
    @Singleton
    fun provideSessionTokenCache(sessionStore: SessionStore): SessionTokenCache =
        SessionTokenCache(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            source = sessionStore.accessToken
        )

    @Provides
    @Singleton
    fun provideSherifBackendApi(okHttpClient: OkHttpClient, json: Json): SherifBackendApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(Constants.SHERIF_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(SherifBackendApi::class.java)
    }
}
