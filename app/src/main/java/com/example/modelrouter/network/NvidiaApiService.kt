package com.example.modelrouter.network

import com.example.modelrouter.models.ModelItem
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface NvidiaApiService {
    @GET("v1/models")
    suspend fun getModels(
        @Header("Authorization") auth: String? = null
    ): Response<NvidiaModelsResponse>
}

data class NvidiaModelsResponse(
    @SerializedName("data") val data: List<NvidiaModel> = emptyList()
)

data class NvidiaModel(
    @SerializedName("id") val id: String = "",
    @SerializedName("object") val objectType: String = "",
    @SerializedName("created") val created: Long = 0,
    @SerializedName("owned_by") val ownedBy: String = ""
)

object NvidiaApiClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://integrate.api.nvidia.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: NvidiaApiService = retrofit.create(NvidiaApiService::class.java)
}
