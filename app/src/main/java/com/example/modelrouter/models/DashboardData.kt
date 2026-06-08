package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

data class DashboardData(
    @SerializedName("groups") val groups: List<DashboardGroup> = emptyList(),
    @SerializedName("api_call_stats") val apiCallStats: ApiCallStats = ApiCallStats(),
    @SerializedName("lock_status") val lockStatus: LockStatus = LockStatus(),
    @SerializedName("group_current_model") val groupCurrentModel: Map<String, String> = emptyMap(),
    @SerializedName("key_manager_status") val keyManagerStatus: KeyManagerStatus = KeyManagerStatus()
)

data class DashboardGroup(
    @SerializedName("name") val name: String,
    @SerializedName("port") val port: Int? = null,
    @SerializedName("models") val models: List<DashboardModel> = emptyList()
)

data class DashboardModel(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("provider_name") val providerName: String = "",
    @SerializedName("status") val status: ModelStatus = ModelStatus()
)

data class ModelStatus(
    @SerializedName("is_healthy") val isHealthy: Boolean = true,
    @SerializedName("avg_response_time") val avgResponseTime: Double? = null,
    @SerializedName("total_requests") val totalRequests: Int = 0,
    @SerializedName("is_current") val isCurrent: Boolean = false,
    @SerializedName("is_locked") val isLocked: Boolean = false,
    @SerializedName("error_message") val errorMessage: String? = null
)

data class ApiCallStats(
    @SerializedName("total_calls") val totalCalls: Int = 0,
    @SerializedName("group_stats") val groupStats: Map<String, Map<String, Int>> = emptyMap()
)

data class LockStatus(
    @SerializedName("locked") val locked: Boolean = false,
    @SerializedName("group") val group: String = "",
    @SerializedName("model_id") val modelId: String = ""
)

data class KeyManagerStatus(
    @SerializedName("max_per_minute") val maxPerMinute: String = "?",
    @SerializedName("request_counts") val requestCounts: Map<String, Int> = emptyMap()
)
