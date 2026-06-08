package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

data class ConfigModelItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("priority") val priority: Int = 1,
    @SerializedName("provider") val provider: String = "nvidia",
    @SerializedName("provider_id") private val _providerId: String? = "nvidia",
    @SerializedName("timeout") val timeout: Int = 60,
    @SerializedName("endpoint") val endpoint: String = "",
    @SerializedName("api_key") val apiKey: String = "",
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("is_fallback") val isFallback: Boolean = false
) {
    val providerId: String get() = _providerId ?: provider ?: "nvidia"
}