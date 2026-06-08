package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

data class ModelItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("owner") val owner: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("link") val link: String = "",
    @SerializedName("params") val params: String = "",
    @SerializedName("source") val source: String = "",
    @SerializedName("popularity_rank") val popularityRank: Int = 999,
    @SerializedName("hot") val hot: Boolean = false,
    @SerializedName("_score") val score: Int = 0
)