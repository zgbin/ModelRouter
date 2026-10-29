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

    // TTFT（首字节时间）与完整响应时间分开统计，避免污染 selectFastestModel 的排序依据
    private val ttftResultsMap = ConcurrentHashMap<String, Long>()
    private val _ttftResults = MutableLiveData<Map<String, Long>>(emptyMap())
    val ttftResults: LiveData<Map<String, Long>> = _ttftResults

    // 连续失败计数：达到阈值才判定模型异常，避免单次抖动触发解锁/健康检测
    private val consecutiveFailures = ConcurrentHashMap<String, AtomicInteger>()
    private const val FAILURE_THRESHOLD = 2

    // ===== 被动测速：用真实请求的首字节延迟（TTFT）替代主动测速 =====
    // 自动测速关闭时 selectFastestModel 仍能凭真实流量知道哪个模型快/慢，零额外探测开销
    private val realLatencyMap = ConcurrentHashMap<String, LatencyStat>()

    data class LatencyStat(val ewmaMs: Long, val lastAt: Long)

    // EWMA 权重：new = old * 7/10 + sample * 3/10
    private const val LATENCY_FRESH_MS = 10 * 60 * 1000L // 超过10分钟无样本视为过期，排队恢复后的模型不被旧数据永久压后

    // 慢降级：真实请求持续明显变慢（排队/网络波动）时短暂绕行该模型
    private const val SLOW_ABSOLUTE_MS = 8000L          // 首字节超过8秒视为慢
    private const val SLOW_RELATIVE_FLOOR_MS = 2000L    // 相对劣化判定的最低延迟，避免低基线噪声
    private const val SLOW_STREAK_THRESHOLD = 2         // 连续慢样本达到阈值才降级，单次抖动不触发
    private const val DEGRADE_COOLDOWN_MS = 60_000L     // 降级冷却60秒，到期自动回到候选
    private val slowStreaks = ConcurrentHashMap<String, AtomicInteger>()
    private val degradedUntilMap = ConcurrentHashMap<String, Long>()

    /**
     * 记录一次真实请求的首字节延迟样本（被动测速）。
     * 明显变慢（绝对慢或相对自身基线劣化3倍）连续达到阈值时，将模型置入慢降级冷却。
     */
    fun recordLatency(modelId: String, ms: Long) {
        val now = System.currentTimeMillis()
        var prevEwma: Long? = null
        realLatencyMap.compute(modelId) { _, stat ->
            prevEwma = stat?.ewmaMs
            if (stat == null) LatencyStat(ms, now)
            else LatencyStat((stat.ewmaMs * 7 + ms * 3) / 10, now)
        }
        val base = prevEwma
        val slow = ms >= SLOW_ABSOLUTE_MS ||
            (base != null && base > 0 && ms >= SLOW_RELATIVE_FLOOR_MS && ms > base * 3)
        if (slow) {
            val streak = slowStreaks.computeIfAbsent(modelId) { AtomicInteger(0) }.incrementAndGet()
            if (streak >= SLOW_STREAK_THRESHOLD) {
                slowStreaks.remove(modelId)
                degradedUntilMap[modelId] = now + DEGRADE_COOLDOWN_MS
                Log.w(TAG, "Model $modelId degraded: slow responses (${ms}ms), routing around for ${DEGRADE_COOLDOWN_MS / 1000}s")
            }
        } else {
            slowStreaks.remove(modelId)
        }
    }

    /** 取模型的真实流量延迟 EWMA（毫秒）；超过新鲜窗口的数据视为未知 */
    fun getRealLatency(modelId: String): Long? {
        val stat = realLatencyMap[modelId] ?: return null
        if (System.currentTimeMillis() - stat.lastAt > LATENCY_FRESH_MS) return null
        return stat.ewmaMs
    }

    fun getRealLatencies(): Map<String, Long> =
        realLatencyMap.keys.mapNotNull { id -> getRealLatency(id)?.let { id to it } }.toMap()

    /** 模型是否处于慢降级冷却期（冷却到期自动恢复参与自动选择） */
    fun isDegraded(modelId: String): Boolean {
        val until = degradedUntilMap[modelId] ?: return false
        if (System.currentTimeMillis() >= until) {
            degradedUntilMap.remove(modelId)
            return false
        }
        return true
    }

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

    /** 记录流式首字节时间（TTFT），仅用于展示，不参与 selectFastestModel 排序 */
    fun updateTTFT(modelId: String, ttftMs: Long) {
        ttftResultsMap[modelId] = ttftMs
        _ttftResults.postValue(ttftResultsMap.toMap())
    }

    fun getTTFTResults(): Map<String, Long> = ttftResultsMap.toMap()

    /**
     * 记录一次请求失败。
     * @return true 表示连续失败达到阈值，应判定模型异常（标记错误/解锁/健康检测）；
     *         false 表示偶发抖动，只需为本次请求切换模型
     */
    fun recordFailure(modelId: String): Boolean {
        val counter = consecutiveFailures.computeIfAbsent(modelId) { AtomicInteger(0) }
        val n = counter.incrementAndGet()
        return n >= FAILURE_THRESHOLD
    }

    /** 请求成功，清零连续失败计数 */
    fun recordSuccess(modelId: String) {
        consecutiveFailures.remove(modelId)
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
        // 清除错误状态的 -1 测速结果，避免 selectFastestModel 计算异常
        if (speedTestResultsMap[modelId] == -1L) {
            speedTestResultsMap.remove(modelId)
            _speedTestResults.postValue(speedTestResultsMap.toMap())
        }
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
        ttftResultsMap.keys.filter { !activeModelIds.contains(it) }.forEach { ttftResultsMap.remove(it) }
        modelAvailabilityMap.keys.filter { !activeModelIds.contains(it) }.forEach { modelAvailabilityMap.remove(it) }
        modelErrorMap.keys.filter { !activeModelIds.contains(it) }.forEach { modelErrorMap.remove(it) }
        consecutiveFailures.keys.filter { !activeModelIds.contains(it) }.forEach { consecutiveFailures.remove(it) }
        healthCheckInfoMap.keys.filter { !activeModelIds.contains(it) }.forEach { healthCheckInfoMap.remove(it) }
        realLatencyMap.keys.filter { !activeModelIds.contains(it) }.forEach { realLatencyMap.remove(it) }
        slowStreaks.keys.filter { !activeModelIds.contains(it) }.forEach { slowStreaks.remove(it) }
        degradedUntilMap.keys.filter { !activeModelIds.contains(it) }.forEach { degradedUntilMap.remove(it) }
        _speedTestResults.postValue(speedTestResultsMap.toMap())
        _ttftResults.postValue(ttftResultsMap.toMap())
        _modelErrors.postValue(modelErrorMap.toMap())
    }

    // 启动健康检测任务，记录出错前是否为锁定状态
    // 使用 putIfAbsent 保证原子性，避免并发重复启动健康检测
    fun startHealthCheck(modelId: String, groupName: String, wasLocked: Boolean, errorType: String): HealthCheckInfo? {
        val info = HealthCheckInfo(modelId, groupName, wasLocked, errorType, running = true)
        val existing = healthCheckInfoMap.putIfAbsent(modelId, info)
        return if (existing == null) info else null  // 返回 null 表示已有检测在运行
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
            // 清除错误状态的 -1 测速结果，避免 selectFastestModel 计算异常
            if (speedTestResultsMap[modelId] == -1L) {
                speedTestResultsMap.remove(modelId)
                _speedTestResults.postValue(speedTestResultsMap.toMap())
            }
            // 如果之前是锁定状态，且当前分组没有其他锁定（避免覆盖用户手动锁定的新模型），才恢复锁定
            if (info.wasLocked && !lockedModelsMap.containsKey(info.groupName)) {
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
