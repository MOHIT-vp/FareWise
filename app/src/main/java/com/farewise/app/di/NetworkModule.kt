package com.farewise.app.di

import com.farewise.app.BuildConfig
import com.farewise.app.data.remote.GoogleMapsApiService
import com.farewise.app.data.remote.OpenWeatherApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Constructs the Retrofit stack used to hit the Google Maps Routes API.
 *
 * Why a dedicated module (not lumped into RepositoryModule):
 *   • network plumbing is genuinely singleton-scoped and shared across
 *     repositories — keeping it here means later repositories (e.g. a real
 *     transit feed) can inject [GoogleMapsApiService] without touching any
 *     of the existing bindings,
 *   • it isolates BuildConfig access to a single file, so production builds
 *     don't accidentally leak the API key into logs or diagnostic output.
 *
 * The auth interceptor adds the `X-Goog-Api-Key` header on every request.
 * If the key is missing (developer hasn't populated local.properties yet),
 * the interceptor still attaches the header as empty string — the upstream
 * repository treats any 4xx from Routes API as "fall back to synthetic
 * data" and the app continues to work.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleMapsApi

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenWeatherApi

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * Tag every outbound request with the Google Maps API key.
     *
     * Using an [Interceptor] (not a request header parameter in the Retrofit
     * interface) keeps the key out of every call site and prevents it from
     * appearing in stack traces if a call fails.
     */
    @Provides
    @Singleton
    @GoogleMapsApi
    fun provideGoogleMapsAuthInterceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .addHeader("X-Goog-Api-Key", BuildConfig.GOOGLE_MAPS_API_KEY)
            .build()
        chain.proceed(req)
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // BASIC in debug, NONE in release — full bodies would echo the
            // rider's live coordinates to logcat, which is a privacy leak.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @GoogleMapsApi authInterceptor: Interceptor,
        logging: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        // Routes API is fast (<1s p95), but Bengaluru mobile networks are
        // spiky — 15s gives enough slack without the UI hanging forever.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .build()

    @Provides
    @Singleton
    @GoogleMapsApi
    fun provideGoogleMapsRetrofit(
        client: OkHttpClient,
        moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(GoogleMapsApiService.BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideGoogleMapsApiService(
        @GoogleMapsApi retrofit: Retrofit
    ): GoogleMapsApiService = retrofit.create(GoogleMapsApiService::class.java)

    /**
     * OpenWeatherMap stack — independent Retrofit because the auth scheme is
     * "appid as a URL query param", not a header, and they share no headers
     * with Google Maps. Reusing the OkHttp client (and therefore the Maps
     * auth interceptor) would either leak that key into OWM requests or
     * require per-host filtering — cheaper to just stand up a clean client.
     */
    @Provides
    @Singleton
    @OpenWeatherApi
    fun provideOpenWeatherOkHttpClient(
        logging: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(logging)
        .build()

    @Provides
    @Singleton
    @OpenWeatherApi
    fun provideOpenWeatherRetrofit(
        @OpenWeatherApi client: OkHttpClient,
        moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(OpenWeatherApiService.BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideOpenWeatherApiService(
        @OpenWeatherApi retrofit: Retrofit
    ): OpenWeatherApiService = retrofit.create(OpenWeatherApiService::class.java)
}
