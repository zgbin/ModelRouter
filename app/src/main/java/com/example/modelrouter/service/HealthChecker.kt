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
 * 后台自动健康检查器（备用）
 *
 * 主动式健康检测已由 ModelRouterServer.startHealthCheckWithBackoff 实现。
 * 此类保留作为辅助监控，定期检查是否有卡住的健康检测任务。
 */
object HealthChecker {

    private const val TAG = "HealthChecker"
    private const val SCAN_INTERVAL_MS = 60_000L

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) {
            Log.w(TAG, "HealthChecker already running")
            return
        }
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope?.launch {
            Log.i(TAG, "HealthChecker started (monitoring mode)")
            while (isActive) {
                try {
                    monitorStuckHealthChecks()
                } catch (e: Exception) {
                    Log.e(TAG, "Health check monitor error", e)
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

    /**
     * 监控卡住的健康检测任务
     * 如果健康检测运行超过5分钟仍未完成，强制标记为完成
     */
    private fun monitorStuckHealthChecks() {
        val activeChecks = RouterState.getActiveHealthChecks()
        for ((modelId, info) in activeChecks) {
            if (!info.running) continue
            // 健康检测由 ModelRouterServer.startHealthCheckWithBackoff 管理
            // 这里只做日志记录
            Log.d(TAG, "Active health check: model=$modelId, error=${info.errorType}")
        }
    }
}
