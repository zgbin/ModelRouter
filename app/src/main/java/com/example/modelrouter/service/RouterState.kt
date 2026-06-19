package com.example.modelrouter.service

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object RouterState {
    private const val TAG = "RouterState"

    private val lockedModelsMap = ConcurrentHashMap<String, String>()
    private val _lockedModels = MutableLiveData<Map<String, String>>(emptyMap())
    val lockedModels: LiveData<Map<String, String>> = _lockedModels

    private val speedTestResultsMap = ConcurrentHashMap<String, Long>()
    private val _speedTestResults = MutableLiveData<Map<String, Long>>(emptyMap())
    val speedTestResults: LiveData<Map<String, Long>> = _speedTestResults

    private val modelErrorMap = ConcurrentHashMap<String, String>()
    private val _modelErrors = MutableLiveData<Map<String, String>>(emptyMap())
    val modelErrors: LiveData<Map<String, String>> = _modelErrors

    private val modelAvailabilityMap = ConcurrentHashMap<String, Boolean>()

    private val activeConnections = ConcurrentHashMap<String, AtomicInteger>()

    // ===== Health check 状态跟踪 =====
    /** 每个模型连续 health check 失败次数 */
    private val healthCheckFailures = ConcurrentHashMap<String, Int>()
    /** 每个模型下次允许 health check 的时间戳(ms) */
    private val nextHealthCheckTime = ConcurrentHashMap<String, Long>()

    /** 基础 health check 间隔: 2分钟 */
    const val HEALTH_CHECK_BASE_INTERVAL_MS = 120_000L
    /** 指数退避前允许的连续失败次数 */
    const val HEALTH_CHECK_BACKOFF_THRESHOLD = 10
    /** 指数退避最大间隔: 30分钟 */
    const val HEALTH_CHECK_MAX_INTERVAL_MS = 1_800_000L

    fun acquireModel(modelId: String): Int {
        val counter = activeConnections.computeIfAbsent(modelId) { AtomicInteger(0) }
        return counter.incrementAndGet()
    }

    fun releaseModel(modelId: String): Int {
        val counter = activeConnections[modelId] ?: return 0
        val newCount = counter.decrementAndGet()
        if (newCount < 0) {
            Log.w("RouterState", "releaseModel underflow for $modelId, resetting to 0")
            counter.set(0)
            return 0
        }
        if (newCount == 0) {
            activeConnections.remove(modelId, counter)
        }
        return newCount
    }

    fun getActiveConnections(modelId: String): Int {
        return activeConnections[modelId]?.get() ?: 0
    }

    fun getActiveConnectionsMap(): Map<String, Int> {
        return activeConnections.map { (k, v) -> k to v.get() }.toMap()
    }

    fun lockModel(groupName: String, modelId: String) {
        lockedModelsMap[groupName] = modelId
        _lockedModels.postValue(lockedModelsMap.toMap())
    }

    fun unlockGroup(groupName: String) {
        lockedModelsMap.remove(groupName)
        _lockedModels.postValue(lockedModelsMap.toMap())
    }

    fun unlockAll() {
        lockedModelsMap.clear()
        _lockedModels.postValue(emptyMap())
    }

    fun getLockedModel(groupName: String): String? {
        return lockedModelsMap[groupName]
    }

    fun getLockedModels(): Map<String, String> = lockedModelsMap.toMap()

    @Deprecated("Use lockModel(groupName, modelId)")
    fun lockModel(modelId: String) {
        lockModel("综合对话组", modelId)
    }

    @Deprecated("Use unlockGroup()")
    fun unlockModel() {
        unlockAll()
    }

    @Deprecated("Use getLockedModel(groupName)")
    fun getLockedModel(): String? = lockedModelsMap.values.firstOrNull()

    fun updateSpeedTestResult(modelId: String, responseTime: Long) {
        speedTestResultsMap[modelId] = responseTime
        _speedTestResults.postValue(speedTestResultsMap.toMap())

        modelAvailabilityMap[modelId] = responseTime >= 0 && responseTime <= 120000
        if (responseTime >= 0) {
            modelErrorMap.remove(modelId)
            _modelErrors.postValue(modelErrorMap.toMap())
        }
    }

    fun updateModelError(modelId: String, error: String) {
        modelErrorMap[modelId] = error
        _modelErrors.postValue(modelErrorMap.toMap())
        speedTestResultsMap[modelId] = -1L
        _speedTestResults.postValue(speedTestResultsMap.toMap())
        modelAvailabilityMap[modelId] = false
        // 首次失败时初始化 health check 计划
        if (!healthCheckFailures.containsKey(modelId)) {
            healthCheckFailures[modelId] = 0
            nextHealthCheckTime[modelId] = System.currentTimeMillis() + HEALTH_CHECK_BASE_INTERVAL_MS
            Log.i(TAG, "Model $modelId marked error: $error, health check scheduled in ${HEALTH_CHECK_BASE_INTERVAL_MS / 1000}s")
        }
    }

    /**
     * 清除模型错误状态，恢复为可用（health check 成功时调用）
     */
    fun clearModelError(modelId: String) {
        modelErrorMap.remove(modelId)
        _modelErrors.postValue(modelErrorMap.toMap())
        healthCheckFailures.remove(modelId)
        nextHealthCheckTime.remove(modelId)
        modelAvailabilityMap[modelId] = true
        Log.i(TAG, "Model $modelId recovered, error cleared")
    }

    /**
     * 记录一次 health check 失败，并计算下次检查时间
     * 前 10 次固定 2 分钟间隔，之后指数退避（2^n * 2分钟，上限 30 分钟）
     * @return 下次检查的间隔(ms)
     */
    fun recordHealthCheckFailure(modelId: String): Long {
        val failures = (healthCheckFailures[modelId] ?: 0) + 1
        healthCheckFailures[modelId] = failures

        val interval = if (failures <= HEALTH_CHECK_BACKOFF_THRESHOLD) {
            HEALTH_CHECK_BASE_INTERVAL_MS
        } else {
            // 指数退避: 2^(failures - threshold) * 基础间隔，上限 30 分钟
            val exponent = failures - HEALTH_CHECK_BACKOFF_THRESHOLD
            val backoff = HEALTH_CHECK_BASE_INTERVAL_MS * (1L shl exponent)
            minOf(backoff, HEALTH_CHECK_MAX_INTERVAL_MS)
        }
        nextHealthCheckTime[modelId] = System.currentTimeMillis() + interval
        Log.w(TAG, "Health check failed for $modelId (attempt $failures), next retry in ${interval / 1000}s")
        return interval
    }

    /**
     * 返回当前需要 health check 的模型列表（有错误且已到检查时间）
     */
    fun getModelsNeedingHealthCheck(): List<String> {
        val now = System.currentTimeMillis()
        return modelErrorMap.keys.filter { modelId ->
            val nextTime = nextHealthCheckTime[modelId]
            nextTime != null && now >= nextTime
        }
    }

    fun getHealthCheckFailures(modelId: String): Int = healthCheckFailures[modelId] ?: 0

    fun getModelErrors(): Map<String, String> = modelErrorMap.toMap()

    fun getModelError(modelId: String): String? = modelErrorMap[modelId]

    fun getSpeedTestResults(): Map<String, Long> = speedTestResultsMap.toMap()

    fun isModelAvailable(modelId: String): Boolean {
        if (modelErrorMap.containsKey(modelId)) return false
        val rt = speedTestResultsMap[modelId]
        if (rt == null) return true
        return rt >= 0 && rt <= 120000
    }

    fun getModelAvailability(): Map<String, Boolean> = modelAvailabilityMap.toMap()

    fun cleanupStaleData(activeModelIds: Set<String>) {
        activeConnections.keys.filter { !activeModelIds.contains(it) }.forEach { activeConnections.remove(it) }
        speedTestResultsMap.keys.filter { !activeModelIds.contains(it) }.forEach { speedTestResultsMap.remove(it) }
        modelAvailabilityMap.keys.filter { !activeModelIds.contains(it) }.forEach { modelAvailabilityMap.remove(it) }
        modelErrorMap.keys.filter { !activeModelIds.contains(it) }.forEach { modelErrorMap.remove(it) }
        healthCheckFailures.keys.filter { !activeModelIds.contains(it) }.forEach { healthCheckFailures.remove(it) }
        nextHealthCheckTime.keys.filter { !activeModelIds.contains(it) }.forEach { nextHealthCheckTime.remove(it) }
        _speedTestResults.postValue(speedTestResultsMap.toMap())
        _modelErrors.postValue(modelErrorMap.toMap())
    }
}
