package com.example.modelrouter.service

import android.content.Context
import android.content.SharedPreferences
import com.example.modelrouter.models.ConfigModelItem
import com.example.modelrouter.models.GroupItem
import com.example.modelrouter.utils.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ConfigManager {

    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false
    private val gson = Gson()
    @Volatile
    private var cachedGroups: List<GroupItem>? = null

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        initialized = true
    }

    private fun loadGroups(): List<GroupItem> {
        val json = prefs.getString("saved_groups", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<GroupItem>>() {}.type
                val saved: List<GroupItem> = gson.fromJson(json, type)
                if (saved.isNotEmpty()) return saved
            } catch (_: Exception) {}
        }
        return loadDefaultConfig()
    }

    @Synchronized
    fun reload() {
        cachedGroups = null
    }

    @Synchronized
    private fun getGroups(): List<GroupItem> {
        if (!initialized) return loadDefaultConfig()
        if (cachedGroups == null) {
            cachedGroups = loadGroups()
        }
        return cachedGroups ?: loadDefaultConfig()
    }

    fun getAllGroups(): List<GroupItem> = getGroups()

    fun getDefaultGroup(): String = "综合对话组"

    fun getGroupByPort(port: Int): String {
        return getGroups().find { it.port == port }?.name ?: "综合对话组"
    }

    fun getModelTimeout(modelId: String): Int {
        return getGroups().flatMap { it.models }.find { it.id == modelId }?.timeout ?: 60
    }

    fun selectFastestModel(groupName: String): String? {
        val group = getGroups().find { it.name == groupName && it.enabled } ?: return null
        val enabledModels = group.models.filter { it.enabled }
        if (enabledModels.isEmpty()) return null
        val speedResults = RouterState.getSpeedTestResults()
        val available = enabledModels.filter { m ->
            val rt = speedResults[m.id]
            rt != null && rt >= 0 && rt <= 120000
        }
        val candidates = if (available.isNotEmpty()) available else enabledModels
        val sortedBySpeed = candidates.sortedBy { speedResults[it.id] ?: Long.MAX_VALUE }
        val bestTime = speedResults[sortedBySpeed.first().id] ?: Long.MAX_VALUE
        val nearBest = sortedBySpeed.filter { m ->
            val rt = speedResults[m.id]
            if (rt == null) true
            else rt <= bestTime * 1.5
        }
        return nearBest.minByOrNull { RouterState.getActiveConnections(it.id) }?.id
    }

    fun getConfig(): Map<String, Any> {
        return mapOf(
            "groups" to getGroups().map { g ->
                mapOf(
                    "name" to g.name,
                    "port" to g.port,
                    "description" to g.description,
                    "enabled" to g.enabled,
                    "models" to g.models.map { m ->
                        mapOf(
                            "id" to m.id,
                            "name" to m.name,
                            "priority" to m.priority,
                            "provider" to m.provider,
                            "timeout" to m.timeout,
                            "enabled" to m.enabled
                        )
                    }
                )
            }
        )
    }

    private fun loadDefaultConfig(): List<GroupItem> {
        return listOf(
            GroupItem(
                name = "综合对话组",
                description = "通用对话和问答(全部支持tools)",
                port = 8190,
                models = listOf(
                    ConfigModelItem(id = "qwen/qwen3-next-80b-a3b-instruct", name = "Qwen3 Next 80B (最快)", priority = 1, provider = "nvidia", timeout = 30),
                    ConfigModelItem(id = "nvidia/llama-3.3-nemotron-super-49b-v1", name = "Nemotron Super 49B", priority = 2, provider = "nvidia", timeout = 30),
                    ConfigModelItem(id = "minimaxai/minimax-m2.5", name = "MiniMax M2.5", priority = 3, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "stepfun-ai/step-3.5-flash", name = "Step 3.5 Flash", priority = 4, provider = "nvidia", timeout = 30),
                    ConfigModelItem(id = "meta/llama-3.1-70b-instruct", name = "Llama 3.1 70B Instruct", priority = 5, provider = "nvidia", timeout = 60)
                )
            ),
            GroupItem(
                name = "代码组",
                description = "代码生成、审查、调试(全部支持tools)",
                port = 8191,
                enabled = false,
                models = listOf(
                    ConfigModelItem(id = "qwen/qwen3-coder-480b-a35b-instruct", name = "Qwen3 Coder 480B (代码最强)", priority = 1, provider = "nvidia", timeout = 90),
                    ConfigModelItem(id = "z-ai/glm-5.1", name = "glm5.1", priority = 2, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "minimaxai/minimax-m2.5", name = "MiniMax M2.5", priority = 3, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "minimaxai/minimax-m2.7", name = "MiniMax M2.7", priority = 4, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "z-ai/glm5", name = "glm5", priority = 5, provider = "nvidia", timeout = 60)
                )
            ),
            GroupItem(
                name = "复杂组",
                description = "复杂任务处理(全部支持tools)",
                port = 8192,
                enabled = false,
                models = listOf(
                    ConfigModelItem(id = "meta/llama-3.1-405b-instruct", name = "Llama 3.1 405B Instruct", priority = 1, provider = "nvidia", timeout = 90),
                    ConfigModelItem(id = "nvidia/llama-3.3-nemotron-super-49b-v1", name = "Llama 3.3 Nemotron Super 49B V1", priority = 2, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "qwen/qwen3-next-80b-a3b-instruct", name = "Qwen3 Next 80B", priority = 3, provider = "nvidia", timeout = 60)
                )
            ),
            GroupItem(
                name = "图像组",
                description = "图像解析(全部支持tools)",
                port = 8193,
                enabled = false,
                models = listOf(
                    ConfigModelItem(id = "qwen/qwen3-next-80b-a3b-instruct", name = "Qwen3 Next 80B", priority = 1, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "stepfun-ai/step-3.5-flash", name = "Step 3.5 Flash", priority = 2, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "minimaxai/minimax-m2.5", name = "MiniMax M2.5", priority = 3, provider = "nvidia", timeout = 60)
                )
            ),
            GroupItem(
                name = "语音处理",
                description = "语音处理(全部支持tools)",
                port = 8194,
                enabled = false,
                models = listOf(
                    ConfigModelItem(id = "qwen/qwen3-next-80b-a3b-instruct", name = "Qwen3 Next 80B", priority = 1, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "minimaxai/minimax-m2.5", name = "MiniMax M2.5", priority = 2, provider = "nvidia", timeout = 60),
                    ConfigModelItem(id = "stepfun-ai/step-3.5-flash", name = "Step 3.5 Flash", priority = 3, provider = "nvidia", timeout = 60)
                )
            )
        )
    }
}
