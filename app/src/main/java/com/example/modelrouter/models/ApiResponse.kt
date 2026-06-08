package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

data class ModelsResponse(
    @SerializedName("models") val models: List<ModelItem> = emptyList(),
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("owners_count") val ownersCount: Int = 0,
    @SerializedName("doc_version") val docVersion: String = "",
    @SerializedName("fetched_at") val fetchedAt: String = "",
    @SerializedName("top_owners") val topOwners: Map<String, Int> = emptyMap(),
    @SerializedName("sources") val sources: Map<String, Int> = emptyMap()
)

data class SearchResponse(
    @SerializedName("results") val results: List<ModelItem> = emptyList(),
    @SerializedName("query") val query: String = ""
)

data class OwnerResponse(
    @SerializedName("owner") val owner: String = "",
    @SerializedName("count") val count: Int = 0,
    @SerializedName("models") val models: List<ModelItem> = emptyList()
)

data class RefreshResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("stats") val stats: ModelStats? = null,
    @SerializedName("message") val message: String = ""
)

data class ActionResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = ""
)