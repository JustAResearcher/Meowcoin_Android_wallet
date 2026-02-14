package com.meowcoin.wallet.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Network client for Meowcoin blockchain API.
 * Default base URL points to the Meowcoin explorer API.
 */
object ApiClient {

    // Primary Meowcoin explorer endpoint
    private const val BASE_URL = "https://explorer.mewccrypto.com/"

    // Fallback endpoints
    private val FALLBACK_URLS = listOf(
        "https://mewc.cryptoscope.io/",
        "https://explorer.meowcoin.org/"
    )

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val apiService: MeowcoinApiService by lazy {
        createService(BASE_URL)
    }

    fun createService(baseUrl: String): MeowcoinApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MeowcoinApiService::class.java)
    }

    fun getFallbackServices(): List<MeowcoinApiService> {
        return FALLBACK_URLS.map { createService(it) }
    }
}
