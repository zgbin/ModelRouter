package com.example.modelrouter.utils

object Constants {
    var BASE_URL = "http://192.168.100.100:8001/"
    var ROUTER_BASE_URL = "http://127.0.0.1:8190/"
    const val NVIDIA_API_MODELS = "https://integrate.api.nvidia.com/v1/models"
    const val MODELS_PAGE_URL = "https://build.nvidia.com/models?orderBy=weightPopular%3ADESC&pageSize=96"
    const val NIM_DOCS_BASE = "https://docs.nvidia.com/nim/large-language-models"
    const val CACHE_TTL = 300
    const val PREFS_NAME = "model_router"
    const val PREF_API_KEY = "api_key"
    const val PREF_SERVER_URL = "server_url"
    const val PREF_ROUTER_URL = "router_url"
    const val AUTO_REFRESH_INTERVAL = 5000L

    const val DEFAULT_SPEED_TEST_KEY = "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"
    const val DEFAULT_WORK_KEY_1 = "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"
    const val DEFAULT_WORK_KEY_2 = "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"
    const val DEFAULT_WORK_KEY_3 = "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"
    const val DEFAULT_RATE_LIMIT = 40
    const val DEFAULT_SWITCH_THRESHOLD = 35
    const val SPEED_TEST_TIMEOUT_MS = 120000L
    @Volatile
    var DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val DEFAULT_DASHBOARD_PORT = 8100
    const val CONFIG_VERSION = 5
    const val DEFAULT_PROVIDER_ID = "nvidia"
}
