package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

enum class RateLimitType(val displayName: String) {
    @SerializedName("unlimited")
    UNLIMITED("无限制"),
    @SerializedName("per_minute")
    PER_MINUTE("每分钟限制"),
    @SerializedName("per_5_hours")
    PER_5_HOURS("每5小时限制"),
    @SerializedName("per_day")
    PER_DAY("每天限制")
}

enum class KeySwitchStrategy(val displayName: String) {
    @SerializedName("threshold")
    THRESHOLD("达到阈值后切换"),
    @SerializedName("every_request")
    EVERY_REQUEST("每个请求都切换")
}

data class ProviderModel(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String
)

data class ProviderInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("api_keys") val apiKeys: List<String> = emptyList(),
    @SerializedName("rate_limit_type") private val _rateLimitType: RateLimitType? = RateLimitType.PER_MINUTE,
    @SerializedName("rate_limit_value") val rateLimitValue: Int = 40,
    @SerializedName("switch_threshold") val switchThreshold: Int = 35,
    @SerializedName("key_switch_strategy") private val _keySwitchStrategy: KeySwitchStrategy? = KeySwitchStrategy.THRESHOLD,
    @SerializedName("models") val models: List<ProviderModel> = emptyList(),
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("is_default") val isDefault: Boolean = false
) {
    val rateLimitType: RateLimitType get() = _rateLimitType ?: RateLimitType.PER_MINUTE
    val keySwitchStrategy: KeySwitchStrategy get() = _keySwitchStrategy ?: KeySwitchStrategy.THRESHOLD
}
