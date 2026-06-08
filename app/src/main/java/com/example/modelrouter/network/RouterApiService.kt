package com.example.modelrouter.network

import com.example.modelrouter.models.ActionResponse
import com.example.modelrouter.models.DashboardData
import com.example.modelrouter.utils.Constants
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface RouterApi {
    @GET("api/dashboard")
    suspend fun getDashboardData(): Response<DashboardData>

    @POST("api/reload")
    suspend fun reloadConfig(): Response<ActionResponse>

    @POST("api/lock_model")
    suspend fun lockModel(@Body body: Map<String, String>): Response<ActionResponse>

    @POST("api/unlock_model")
    suspend fun unlockModel(): Response<ActionResponse>
}

object RouterApiClient {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    var apiService: RouterApi = buildApi()

    private fun buildApi(): RouterApi {
        return Retrofit.Builder()
            .baseUrl(Constants.ROUTER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RouterApi::class.java)
    }

    fun rebuild() {
        apiService = buildApi()
    }
}
