package com.example.modelrouter.network

import com.example.modelrouter.models.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @GET("api/models")
    suspend fun getModels(): Response<ModelsResponse>

    @GET("api/search")
    suspend fun searchModels(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20
    ): Response<SearchResponse>

    @GET("api/owners/{owner}")
    suspend fun getModelsByOwner(@Path("owner") owner: String): Response<OwnerResponse>

    @POST("api/refresh")
    suspend fun refreshData(): Response<RefreshResponse>

    @GET("api/dashboard")
    suspend fun getDashboardData(): Response<DashboardData>

    @POST("api/reload")
    suspend fun reloadConfig(): Response<ActionResponse>

    @POST("api/lock_model")
    suspend fun lockModel(@Body body: Map<String, String>): Response<ActionResponse>

    @POST("api/unlock_model")
    suspend fun unlockModel(): Response<ActionResponse>

    @POST("add_model")
    @FormUrlEncoded
    suspend fun addModel(
        @Field("model_id") modelId: String,
        @Field("model_name") modelName: String,
        @Field("group_name") groupName: String
    ): Response<ActionResponse>

    @POST("replace_model")
    @FormUrlEncoded
    suspend fun replaceModel(
        @Field("old_model_id") oldModelId: String,
        @Field("new_model_id") newModelId: String,
        @Field("new_model_name") newModelName: String,
        @Field("group_name") groupName: String
    ): Response<ActionResponse>

    @POST("delete_model")
    @FormUrlEncoded
    suspend fun deleteModel(
        @Field("model_id") modelId: String,
        @Field("group_name") groupName: String
    ): Response<ActionResponse>
}