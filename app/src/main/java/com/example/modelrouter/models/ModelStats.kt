package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

data class ModelStats(
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("owners_count") val ownersCount: Int = 0,
    @SerializedName("doc_version") val docVersion: String = "unknown",
    @SerializedName("fetched_at") val fetchedAt: String = "",
    @SerializedName("top_owners") val topOwners: Map<String, Int> = emptyMap(),
    @SerializedName("sources") val sources: Map<String, Int> = emptyMap()
)