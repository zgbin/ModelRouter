package com.example.modelrouter.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object StatsManager {
    private val callStats = ConcurrentHashMap<String, AtomicInteger>()
    private val errorStats = ConcurrentHashMap<String, AtomicInteger>()
    private val totalCalls = AtomicInteger(0)
    private val totalErrors = AtomicInteger(0)

    fun recordCall(modelId: String, success: Boolean) {
        totalCalls.incrementAndGet()
        callStats.getOrPut(modelId) { AtomicInteger(0) }.incrementAndGet()
        if (!success) {
            totalErrors.incrementAndGet()
            errorStats.getOrPut(modelId) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    fun getStats(): Map<String, Int> {
        return callStats.map { (k, v) -> k to v.get() }.toMap()
    }

    fun getTotalCalls(): Int = totalCalls.get()
    fun getTotalErrors(): Int = totalErrors.get()
    fun getModelStats(): Map<String, Int> = callStats.map { (k, v) -> k to v.get() }.toMap()
    fun getErrorStats(): Map<String, Int> = errorStats.map { (k, v) -> k to v.get() }.toMap()

    fun cleanupStaleData(activeModelIds: Set<String>) {
        callStats.keys.filter { !activeModelIds.contains(it) }.forEach { callStats.remove(it) }
        errorStats.keys.filter { !activeModelIds.contains(it) }.forEach { errorStats.remove(it) }
    }
}
