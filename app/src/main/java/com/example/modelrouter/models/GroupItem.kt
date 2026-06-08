package com.example.modelrouter.models

import com.google.gson.annotations.SerializedName

data class GroupItem(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("port") val port: Int = 8190,
    @SerializedName("models") val models: List<ConfigModelItem> = emptyList(),
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("is_backup") val isBackup: Boolean = false
)