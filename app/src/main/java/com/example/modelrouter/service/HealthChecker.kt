package com.example.modelrouter.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 后台自动健康检查器
 *
 * 模型失败后自动发起 health check，恢复模型可用状态。
 * - 前 10 次：固定 2 分钟间隔
 * - 10 次之后：指数退避（2min, 4min, 8min... 上限 30min）
 *
 * 调用关系：
 * - 由 RouterService 启动/停止
 * - 使用 SpeedTester 执行测速
 * - 通过 RouterState 读取错误状态、更新恢复/失败状态
 * - 通过 ConfigManager 获取模型配置（providerId）
 */
object HealthChecker {

    private const val TAG = "HealthChecker"
    /** 扫描间隔：每 15 秒检查一次是否有模型需要 health check */
    private const val SCAN_INTERVAL_MS = 15_000L

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile
    private var running = false

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val speedTester by lazy {
        SpeedTester(client, ApiKeyManager)
    }

    fun start() {
        if (running) {
            Log.w(TAG, "HealthChecker already running")
            return
        }
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope?.launch {
            Log.i(TAG, "HealthChecker started (base interval=${RouterState.HEALTH_CHECK_BASE_INTERVAL_MS / 1000}s, backoff after ${RouterState.HEALTH_CHECK_BACKOFF_THRESHOLD} failures)")
            while (isActive) {
                try {
                    checkFailedModels()
                } catch (e: Exception) {
                    Log.e(TAG, "Health check cycle error", e)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        Log.i(TAG, "HealthChecker stopped")
    }

    private suspend fun checkFailedModels() {
        val modelsToCheck = RouterState.getModelsNeedingHealthCheck()
        if (modelsToCheck.isEmpty()) return

        // 从配置中获取所有模型及其 providerId
        val allGroups = ConfigManager.getAllGroups()
        val modelProviderMap = mutableMapOf<String, String>()
        for (group in allGroups) {
            for (model in group.models) {
                if (model.enabled) {
                    modelProviderMap[model.id] = model.providerId
                }
            }
        }

        for (modelId in modelsToCheck) {
            val providerId = modelProviderMap[modelId] ?: "nvidia"
            val failures = RouterState.getHealthCheckFailures(modelId)
            Log.i(TAG, "Health checking model $modelId (provider=$providerId, previous failures=$failures)")

            try {
                val result = speedTester.testModel(modelId, providerId)
                if (result.success) {
                    Log.i(TAG, "Health check PASSED for $modelId, recovering (responseTime=${result.responseTime}ms)")
                    RouterState.clearModelError(modelId)
                    RouterState.updateSpeedTestResult(modelId, result.responseTime)
                } else {
                    val interval = RouterState.recordHealthCheckFailure(modelId)
                    Log.w(TAG, "Health check FAILED for $modelId: ${result.error}, next retry in ${interval / 1000}s")
                }
            } catch (e: Exception) {
                val interval = RouterState.recordHealthCheckFailure(modelId)
                Log.e(TAG, "Health check exception for $modelId: ${e.message}, next retry in ${interval / 1000}s")
            }
        }
    }
}
