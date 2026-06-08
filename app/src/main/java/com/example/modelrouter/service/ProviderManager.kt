package com.example.modelrouter.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.modelrouter.models.ProviderInfo
import com.example.modelrouter.models.ProviderModel
import com.example.modelrouter.models.RateLimitType
import com.example.modelrouter.models.KeySwitchStrategy
import com.example.modelrouter.utils.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object ProviderManager {
    private const val TAG = "ProviderManager"
    private const val PREFS_NAME = "provider_manager"
    private const val KEY_PROVIDERS = "providers_json"
    private const val FIVE_HOURS_MS = 5 * 60 * 60 * 1000L

    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false
    private val gson = Gson()

    private val providers = CopyOnWriteArrayList<ProviderInfo>()

    private val providerRequestCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()
    private val providerLastResetMinute = ConcurrentHashMap<String, AtomicInteger>()
    private val providerLastReset5Hour = ConcurrentHashMap<String, AtomicLong>()
    private val providerLastResetDay = ConcurrentHashMap<String, AtomicInteger>()
    private val providerKeyIndex = ConcurrentHashMap<String, AtomicInteger>()

    private val lock = Any()

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initialized = true
        loadProviders()
    }

    private fun loadProviders() {
        providers.clear()
        val json = prefs.getString(KEY_PROVIDERS, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<ProviderInfo>>() {}.type
                val saved: List<ProviderInfo> = gson.fromJson(json, type)
                if (saved.isNotEmpty()) {
                    providers.addAll(saved)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load providers", e)
            }
        }
        providers.addAll(createDefaultProviders())
        saveProviders()
    }

    private fun saveProviders() {
        prefs.edit().putString(KEY_PROVIDERS, gson.toJson(providers.toList())).apply()
    }

    private fun createDefaultProviders(): List<ProviderInfo> {
        return listOf(
            ProviderInfo(
                id = "nvidia",
                name = "NVIDIA NIM",
                baseUrl = Constants.DEFAULT_BASE_URL,
                apiKeys = listOf(
                    Constants.DEFAULT_WORK_KEY_1,
                    Constants.DEFAULT_WORK_KEY_2,
                    Constants.DEFAULT_WORK_KEY_3
                ),
                _rateLimitType = RateLimitType.PER_MINUTE,
                rateLimitValue = 40,
                switchThreshold = 35,
                models = listOf(
                    ProviderModel("qwen/qwen3-next-80b-a3b-instruct", "Qwen3 Next 80B"),
                    ProviderModel("nvidia/llama-3.3-nemotron-super-49b-v1", "Nemotron Super 49B"),
                    ProviderModel("minimaxai/minimax-m2.5", "MiniMax M2.5"),
                    ProviderModel("stepfun-ai/step-3.5-flash", "Step 3.5 Flash"),
                    ProviderModel("meta/llama-3.1-70b-instruct", "Llama 3.1 70B"),
                    ProviderModel("qwen/qwen3-coder-480b-a35b-instruct", "Qwen3 Coder 480B"),
                    ProviderModel("meta/llama-3.1-405b-instruct", "Llama 3.1 405B")
                ),
                enabled = true,
                isDefault = true
            ),
            ProviderInfo(
                id = "agnes",
                name = "Agnes AI",
                baseUrl = "https://apihub.agnes-ai.com/v1",
                apiKeys = listOf(""),
                _rateLimitType = RateLimitType.UNLIMITED,
                rateLimitValue = 0,
                switchThreshold = 0,
                models = listOf(
                    ProviderModel("agnes-2.0-flash", "Agnes 2.0 Flash"),
                    ProviderModel("agnes-1.5-flash", "Agnes 1.5 Flash")
                ),
                enabled = true,
                isDefault = true
            )
        )
    }

    fun getAllProviders(): List<ProviderInfo> = providers.toList()

    fun getProvider(providerId: String): ProviderInfo? {
        return providers.find { it.id == providerId }
    }

    fun getEnabledProviders(): List<ProviderInfo> = providers.filter { it.enabled }

    fun addProvider(provider: ProviderInfo): Boolean {
        synchronized(lock) {
            if (providers.any { it.id == provider.id }) return false
            providers.add(provider)
            saveProviders()
            return true
        }
    }

    fun updateProvider(provider: ProviderInfo): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == provider.id }
            if (index < 0) return false
            providers[index] = provider
            saveProviders()
            return true
        }
    }

    fun removeProvider(providerId: String): Boolean {
        synchronized(lock) {
            val provider = providers.find { it.id == providerId }
            if (provider?.isDefault == true) return false
            val toRemove = providers.filter { it.id == providerId }
            if (toRemove.isEmpty()) return false
            providers.removeAll(toRemove)
            providerRequestCounts.remove(providerId)
            providerLastResetMinute.remove(providerId)
            providerLastReset5Hour.remove(providerId)
            providerLastResetDay.remove(providerId)
            providerKeyIndex.remove(providerId)
            saveProviders()
            return true
        }
    }

    fun addApiKey(providerId: String, key: String): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == providerId }
            if (index < 0) return false
            val provider = providers[index]
            if (provider.apiKeys.contains(key)) return false
            providers[index] = provider.copy(apiKeys = provider.apiKeys + key)
            saveProviders()
            return true
        }
    }

    fun removeApiKey(providerId: String, key: String): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == providerId }
            if (index < 0) return false
            val provider = providers[index]
            if (provider.apiKeys.size <= 1) return false
            providers[index] = provider.copy(apiKeys = provider.apiKeys.filter { it != key })
            saveProviders()
            return true
        }
    }

    fun updateRateLimit(providerId: String, rateLimitType: RateLimitType, rateLimitValue: Int, switchThreshold: Int): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == providerId }
            if (index < 0) return false
            val provider = providers[index]
            providers[index] = provider.copy(
                _rateLimitType = rateLimitType,
                rateLimitValue = rateLimitValue,
                switchThreshold = switchThreshold
            )
            saveProviders()
            return true
        }
    }

    fun updateKeySwitchStrategy(providerId: String, strategy: KeySwitchStrategy): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == providerId }
            if (index < 0) return false
            val provider = providers[index]
            providers[index] = provider.copy(_keySwitchStrategy = strategy)
            saveProviders()
            return true
        }
    }

    fun updateModels(providerId: String, models: List<ProviderModel>): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == providerId }
            if (index < 0) return false
            val provider = providers[index]
            providers[index] = provider.copy(models = models)
            saveProviders()
            return true
        }
    }

    fun updateBaseUrl(providerId: String, baseUrl: String): Boolean {
        synchronized(lock) {
            val index = providers.indexOfFirst { it.id == providerId }
            if (index < 0) return false
            val provider = providers[index]
            providers[index] = provider.copy(baseUrl = baseUrl)
            saveProviders()
            return true
        }
    }

    private fun resetCountersIfNeeded(providerId: String, rateLimitType: RateLimitType) {
        val counts = providerRequestCounts.getOrPut(providerId) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()

        when (rateLimitType) {
            RateLimitType.PER_MINUTE -> {
                val currentMinute = (now / 60000).toInt()
                val lastReset = providerLastResetMinute.getOrPut(providerId) { AtomicInteger(currentMinute) }
                if (currentMinute != lastReset.get()) {
                    synchronized(lock) {
                        if (currentMinute != lastReset.get()) {
                            lastReset.set(currentMinute)
                            counts.clear()
                        }
                    }
                }
            }
            RateLimitType.PER_5_HOURS -> {
                val current5Hour = (now / FIVE_HOURS_MS)
                val lastReset = providerLastReset5Hour.getOrPut(providerId) { AtomicLong(current5Hour) }
                if (current5Hour != lastReset.get()) {
                    synchronized(lock) {
                        if (current5Hour != lastReset.get()) {
                            lastReset.set(current5Hour)
                            counts.clear()
                        }
                    }
                }
            }
            RateLimitType.PER_DAY -> {
                val currentDay = (now / 86400_000).toInt()
                val lastReset = providerLastResetDay.getOrPut(providerId) { AtomicInteger(currentDay) }
                if (currentDay != lastReset.get()) {
                    synchronized(lock) {
                        if (currentDay != lastReset.get()) {
                            lastReset.set(currentDay)
                            counts.clear()
                        }
                    }
                }
            }
            RateLimitType.UNLIMITED -> {}
        }
    }

    fun getNextKey(providerId: String): String {
        synchronized(lock) {
            val provider = providers.find { it.id == providerId } ?: return ""
            if (provider.apiKeys.isEmpty()) return ""
            if (provider.apiKeys.size == 1) return provider.apiKeys.first()

            if (provider.keySwitchStrategy == KeySwitchStrategy.EVERY_REQUEST) {
                val keyIdx = providerKeyIndex.getOrPut(providerId) { AtomicInteger(0) }
                val currentIdx = Math.floorMod(keyIdx.getAndIncrement(), provider.apiKeys.size)
                return provider.apiKeys[currentIdx]
            }

            resetCountersIfNeeded(providerId, provider.rateLimitType)

            val counts = providerRequestCounts.getOrPut(providerId) { ConcurrentHashMap() }
            val keyIdx = providerKeyIndex.getOrPut(providerId) { AtomicInteger(0) }
            val keys = provider.apiKeys
            val threshold = provider.switchThreshold

            val idx = Math.floorMod(keyIdx.get(), keys.size)
            val currentKey = keys[idx]
            val currentCount = counts.getOrPut(currentKey) { AtomicInteger(0) }

            if (provider.rateLimitType == RateLimitType.UNLIMITED || currentCount.get() < threshold) {
                currentCount.incrementAndGet()
                return currentKey
            }

            for (attempt in 0 until keys.size) {
                val nextIdx = (idx + 1 + attempt) % keys.size
                val nextKey = keys[nextIdx]
                val nextCount = counts.getOrPut(nextKey) { AtomicInteger(0) }
                if (nextCount.get() < threshold) {
                    keyIdx.set(nextIdx)
                    nextCount.incrementAndGet()
                    return nextKey
                }
            }

            keyIdx.set((idx + 1) % keys.size)
            val fallbackKey = keys[keyIdx.get()]
            counts.getOrPut(fallbackKey) { AtomicInteger(0) }.incrementAndGet()
            return fallbackKey
        }
    }

    fun peekNextKey(providerId: String, excludeKey: String): String {
        synchronized(lock) {
            val provider = providers.find { it.id == providerId } ?: return ""
            if (provider.apiKeys.isEmpty()) return ""

            resetCountersIfNeeded(providerId, provider.rateLimitType)

            val counts = providerRequestCounts.getOrPut(providerId) { ConcurrentHashMap() }
            val keyIdx = providerKeyIndex.getOrPut(providerId) { AtomicInteger(0) }
            val keys = provider.apiKeys
            val threshold = provider.switchThreshold

            for ((idx, key) in keys.withIndex()) {
                if (key == excludeKey) continue
                val count = counts.getOrPut(key) { AtomicInteger(0) }
                if (provider.rateLimitType == RateLimitType.UNLIMITED || count.get() < threshold) {
                    keyIdx.set(idx)
                    count.incrementAndGet()
                    return key
                }
            }

            val altKey = keys.firstOrNull { it != excludeKey } ?: keys.firstOrNull() ?: ""
            if (altKey.isNotEmpty()) {
                keyIdx.set(keys.indexOf(altKey) % keys.size)
                counts.getOrPut(altKey) { AtomicInteger(0) }.incrementAndGet()
            }
            return altKey
        }
    }

    fun isEarly429(providerId: String, currentKey: String): Boolean {
        synchronized(lock) {
            val provider = providers.find { it.id == providerId } ?: return true
            resetCountersIfNeeded(providerId, provider.rateLimitType)
            val counts = providerRequestCounts[providerId] ?: return true
            val count = counts[currentKey]?.get() ?: 0
            return count < provider.rateLimitValue
        }
    }

    fun getRequestCounts(providerId: String): Map<String, Int> {
        val provider = providers.find { it.id == providerId } ?: return emptyMap()
        resetCountersIfNeeded(providerId, provider.rateLimitType)
        val counts = providerRequestCounts[providerId] ?: return emptyMap()
        return counts.map { (k, v) -> k to v.get() }.toMap()
    }

    fun getProviderStatus(providerId: String): Map<String, Any>? {
        val provider = providers.find { it.id == providerId } ?: return null
        resetCountersIfNeeded(providerId, provider.rateLimitType)
        val counts = providerRequestCounts[providerId] ?: ConcurrentHashMap()
        return mapOf(
            "id" to provider.id,
            "name" to provider.name,
            "base_url" to provider.baseUrl,
            "rate_limit_type" to provider.rateLimitType.name,
            "rate_limit_value" to provider.rateLimitValue,
            "switch_threshold" to provider.switchThreshold,
            "api_key_count" to provider.apiKeys.size,
            "model_count" to provider.models.size,
            "enabled" to provider.enabled,
            "request_counts" to counts.map { (k, v) -> k to v.get() }.toMap()
        )
    }

    fun getBaseUrlForModel(modelId: String): String {
        val providerId = findProviderIdForModel(modelId)
        return providers.find { it.id == providerId }?.baseUrl ?: Constants.DEFAULT_BASE_URL
    }

    fun getProviderIdForModel(modelId: String): String {
        return findProviderIdForModel(modelId)
    }

    private fun findProviderIdForModel(modelId: String): String {
        for (provider in providers) {
            if (provider.models.any { it.id == modelId }) {
                return provider.id
            }
        }
        return Constants.DEFAULT_PROVIDER_ID
    }

    suspend fun fetchModelsFromProvider(providerId: String, client: OkHttpClient): List<ProviderModel> {
        val provider = providers.find { it.id == providerId } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = if (provider.apiKeys.isNotEmpty()) provider.apiKeys.first() else ""
                val url = "${provider.baseUrl.trimEnd('/')}/models"
                val requestBuilder = Request.Builder().url(url).get()
                if (apiKey.isNotEmpty()) {
                    requestBuilder.header("Authorization", "Bearer $apiKey")
                }
                val request = requestBuilder.build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return@withContext emptyList()

                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val dataArray = json.get("data")?.asJsonArray ?: return@withContext emptyList()
                val models = mutableListOf<ProviderModel>()
                for (item in dataArray) {
                    val obj = item.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val name = id.split("/").lastOrNull() ?: id
                    models.add(ProviderModel(id, name))
                }
                updateModels(providerId, models)
                models
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch models from provider $providerId", e)
                emptyList()
            }
        }
    }

    fun reload() {
        loadProviders()
    }

    fun getSpeedTestKey(providerId: String): String {
        val provider = providers.find { it.id == providerId } ?: return ""
        if (provider.apiKeys.isEmpty()) return ""
        return provider.apiKeys.first()
    }

    private class AtomicLong(initialValue: Long) {
        private val value = java.util.concurrent.atomic.AtomicLong(initialValue)
        fun get(): Long = value.get()
        fun set(newValue: Long) = value.set(newValue)
    }
}
