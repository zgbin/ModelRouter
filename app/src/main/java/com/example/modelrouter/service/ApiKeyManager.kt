package com.example.modelrouter.service

import android.content.Context
import android.content.SharedPreferences
import com.example.modelrouter.utils.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object ApiKeyManager {
    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false
    private val gson = Gson()

    private val workKeys = CopyOnWriteArrayList<String>()
    @Volatile
    private var speedTestKey: String = Constants.DEFAULT_SPEED_TEST_KEY

    private val keyRequestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastResetTime = AtomicInteger((System.currentTimeMillis() / 60000).toInt())
    private val maxPerMinute = AtomicInteger(40)
    private val switchThreshold = AtomicInteger(35)
    private val currentKeyIndex = AtomicInteger(0)

    private val keyLock = Any()

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("api_key_manager", Context.MODE_PRIVATE)
        initialized = true
        loadKeys()
    }

    private fun loadKeys() {
        speedTestKey = prefs.getString("speed_test_key", Constants.DEFAULT_SPEED_TEST_KEY) ?: Constants.DEFAULT_SPEED_TEST_KEY
        workKeys.clear()
        val json = prefs.getString("work_keys_json", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                val saved: List<String> = gson.fromJson(json, type)
                workKeys.addAll(saved)
            } catch (_: Exception) {
                workKeys.addAll(listOf(Constants.DEFAULT_WORK_KEY_1, Constants.DEFAULT_WORK_KEY_2, Constants.DEFAULT_WORK_KEY_3))
            }
        } else {
            workKeys.addAll(listOf(Constants.DEFAULT_WORK_KEY_1, Constants.DEFAULT_WORK_KEY_2, Constants.DEFAULT_WORK_KEY_3))
        }
        maxPerMinute.set(prefs.getInt("max_per_minute", 40))
        switchThreshold.set(prefs.getInt("switch_threshold", 35))
    }

    private fun saveWorkKeys() {
        prefs.edit().putString("work_keys_json", gson.toJson(workKeys)).apply()
    }

    fun setSpeedTestKey(key: String) {
        speedTestKey = key
        prefs.edit().putString("speed_test_key", key).apply()
    }

    fun getSpeedTestKeyDisplay(): String = speedTestKey

    fun addWorkKey(key: String) {
        if (key.isBlank() || workKeys.contains(key)) return
        workKeys.add(key)
        saveWorkKeys()
    }

    fun removeWorkKey(key: String) {
        workKeys.remove(key)
        saveWorkKeys()
    }

    fun getWorkKeys(): List<String> = workKeys.toList()

    fun setMaxPerMinute(max: Int) {
        maxPerMinute.set(max)
        prefs.edit().putInt("max_per_minute", max).apply()
    }

    fun getMaxPerMinute(): Int = maxPerMinute.get()

    fun setSwitchThreshold(threshold: Int) {
        switchThreshold.set(threshold)
        prefs.edit().putInt("switch_threshold", threshold).apply()
    }

    fun getSwitchThreshold(): Int = switchThreshold.get()

    fun reloadKeys() = loadKeys()

    private fun resetCountersIfNewMinute() {
        val currentMinute = (System.currentTimeMillis() / 60000).toInt()
        if (currentMinute != lastResetTime.get()) {
            synchronized(keyLock) {
                if (currentMinute != lastResetTime.get()) {
                    lastResetTime.set(currentMinute)
                    keyRequestCounts.clear()
                }
            }
        }
    }

    fun getNextKey(): String {
        synchronized(keyLock) {
            ensureInit()
            resetCountersIfNewMinute()
            val threshold = switchThreshold.get()
            val keys = workKeys.toList()
            if (keys.isEmpty()) return ""

            val idx = Math.floorMod(currentKeyIndex.get(), keys.size)
            val currentKey = keys[idx]
            val currentCount = keyRequestCounts.getOrPut(currentKey) { AtomicInteger(0) }

            if (currentCount.get() < threshold) {
                currentCount.incrementAndGet()
                return currentKey
            }

            for (attempt in 0 until keys.size) {
                val nextIdx = (idx + 1 + attempt) % keys.size
                val nextKey = keys[nextIdx]
                val nextCount = keyRequestCounts.getOrPut(nextKey) { AtomicInteger(0) }
                if (nextCount.get() < threshold) {
                    currentKeyIndex.set(nextIdx)
                    nextCount.incrementAndGet()
                    return nextKey
                }
            }

            currentKeyIndex.set((idx + 1) % keys.size)
            val fallbackKey = keys[currentKeyIndex.get()]
            keyRequestCounts.getOrPut(fallbackKey) { AtomicInteger(0) }.incrementAndGet()
            return fallbackKey
        }
    }

    fun peekNextKey(excludeKey: String): String {
        synchronized(keyLock) {
            ensureInit()
            resetCountersIfNewMinute()
            val keys = workKeys.toList()
            if (keys.isEmpty()) return ""
            val threshold = switchThreshold.get()

            for ((idx, key) in keys.withIndex()) {
                if (key == excludeKey) continue
                val count = keyRequestCounts.getOrPut(key) { AtomicInteger(0) }
                if (count.get() < threshold) {
                    currentKeyIndex.set(idx)
                    count.incrementAndGet()
                    return key
                }
            }

            val altKey = keys.firstOrNull { it != excludeKey } ?: keys.firstOrNull() ?: ""
            if (altKey.isNotEmpty()) {
                currentKeyIndex.set(keys.indexOf(altKey) % keys.size)
                keyRequestCounts.getOrPut(altKey) { AtomicInteger(0) }.incrementAndGet()
            }
            return altKey
        }
    }

    fun getSpeedTestKey(): String {
        synchronized(keyLock) {
            ensureInit()
            resetCountersIfNewMinute()
            val count = keyRequestCounts.getOrPut(speedTestKey) { AtomicInteger(0) }.get()
            if (count < switchThreshold.get()) {
                keyRequestCounts[speedTestKey]!!.incrementAndGet()
                return speedTestKey
            }
        }
        return getNextKey()
    }

    fun isEarly429(currentKey: String): Boolean {
        synchronized(keyLock) {
            resetCountersIfNewMinute()
            val count = keyRequestCounts[currentKey]?.get() ?: 0
            return count < maxPerMinute.get()
        }
    }

    fun getRequestCounts(): Map<String, Int> {
        resetCountersIfNewMinute()
        return keyRequestCounts.map { (k, v) -> k to v.get() }.toMap()
    }

    fun getStatus(): KeyManagerStatusData {
        resetCountersIfNewMinute()
        return KeyManagerStatusData(
            maxPerMinute = maxPerMinute.get(),
            switchThreshold = switchThreshold.get(),
            requestCounts = keyRequestCounts.map { (k, v) -> k to v.get() }.toMap()
        )
    }

    private fun ensureInit() {
        if (workKeys.isEmpty()) {
            workKeys.addAll(listOf(Constants.DEFAULT_WORK_KEY_1, Constants.DEFAULT_WORK_KEY_2, Constants.DEFAULT_WORK_KEY_3))
        }
    }
}

data class KeyManagerStatusData(
    val maxPerMinute: Int,
    val switchThreshold: Int,
    val requestCounts: Map<String, Int>
)
