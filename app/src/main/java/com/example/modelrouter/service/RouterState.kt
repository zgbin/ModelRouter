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

    // 健康检测管理：记录出错前是否为锁定状态，以及健康检测任务是否在运行
    private val healthCheckInfoMap = ConcurrentHashMap<String, HealthCheckInfo>()

    data class HealthCheckInfo(
        val modelId: String,
        val groupName: String,
        val wasLocked: Boolean,
        val errorType: String,
        @Volatile var running: Boolean = true
    )

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
    }

    /**
     * 清除模型错误状态，恢复为可用
     */
    fun clearModelError(modelId: String) {
        modelErrorMap.remove(modelId)
        _modelErrors.postValue(modelErrorMap.toMap())
        modelAvailabilityMap[modelId] = true
        Log.i(TAG, "Model $modelId recovered, error cleared")
    }

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
        healthCheckInfoMap.keys.filter { !activeModelIds.contains(it) }.forEach { healthCheckInfoMap.remove(it) }
        _speedTestResults.postValue(speedTestResultsMap.toMap())
        _modelErrors.postValue(modelErrorMap.toMap())
    }

    // 启动健康检测任务，记录出错前是否为锁定状态
    fun startHealthCheck(modelId: String, groupName: String, wasLocked: Boolean, errorType: String): HealthCheckInfo {
        val info = HealthCheckInfo(modelId, groupName, wasLocked, errorType, running = true)
        healthCheckInfoMap[modelId] = info
        return info
    }

    // 获取健康检测信息
    fun getHealthCheckInfo(modelId: String): HealthCheckInfo? = healthCheckInfoMap[modelId]

    // 健康检测完成，恢复模型状态
    fun finishHealthCheck(modelId: String) {
        val info = healthCheckInfoMap.remove(modelId)
        if (info != null) {
            info.running = false
            // 清除模型错误状态
            modelErrorMap.remove(modelId)
            _modelErrors.postValue(modelErrorMap.toMap())
            modelAvailabilityMap[modelId] = true
            // 如果之前是锁定状态，恢复锁定
            if (info.wasLocked) {
                lockedModelsMap[info.groupName] = modelId
                _lockedModels.postValue(lockedModelsMap.toMap())
                Log.i(TAG, "Restored lock for model $modelId in group ${info.groupName} after health recovery")
            }
            Log.i(TAG, "Health check finished for model $modelId, model recovered")
        }
    }

    // 检查模型是否正在进行健康检测
    fun isHealthCheckRunning(modelId: String): Boolean {
        return healthCheckInfoMap[modelId]?.running == true
    }

    // 获取所有正在进行的健康检测
    fun getActiveHealthChecks(): Map<String, HealthCheckInfo> = healthCheckInfoMap.toMap()
}
