package com.example.modelrouter.network

import com.example.modelrouter.models.*
import retrofit2.Response

class ModelRepository(private val apiService: ApiService) {

    suspend fun getModels(): Response<ModelsResponse> {
        return apiService.getModels()
    }

    suspend fun searchModels(query: String, limit: Int = 20): Response<SearchResponse> {
        return apiService.searchModels(query, limit)
    }

    suspend fun getModelsByOwner(owner: String): Response<OwnerResponse> {
        return apiService.getModelsByOwner(owner)
    }

    suspend fun refreshData(): Response<RefreshResponse> {
        return apiService.refreshData()
    }

    suspend fun getDashboardData(): Response<DashboardData> {
        return apiService.getDashboardData()
    }

    suspend fun reloadConfig(): Response<ActionResponse> {
        return apiService.reloadConfig()
    }

    suspend fun lockModel(group: String, modelId: String): Response<ActionResponse> {
        return apiService.lockModel(mapOf("group" to group, "model_id" to modelId))
    }

    suspend fun unlockModel(): Response<ActionResponse> {
        return apiService.unlockModel()
    }

    suspend fun addModel(modelId: String, modelName: String, groupName: String): Response<ActionResponse> {
        return apiService.addModel(modelId, modelName, groupName)
    }

    suspend fun replaceModel(oldModelId: String, newModelId: String, newModelName: String, groupName: String): Response<ActionResponse> {
        return apiService.replaceModel(oldModelId, newModelId, newModelName, groupName)
    }

    suspend fun deleteModel(modelId: String, groupName: String): Response<ActionResponse> {
        return apiService.deleteModel(modelId, groupName)
    }
}