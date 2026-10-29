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

    fun selectFastestModel(groupName: String, exclude: Set<String> = emptySet()): String? {
        val group = getGroups().find { it.name == groupName && it.enabled } ?: return null
        val enabledModels = group.models.filter { it.enabled }
        if (enabledModels.isEmpty()) return null
        // 慢降级冷却中的模型暂不参与自动选择（全部降级时退化为全部候选）
        val activeModels = enabledModels.filter { !RouterState.isDegraded(it.id) }.ifEmpty { enabledModels }
        // 换模型重试时排除已试过的模型，否则大概率重复选中同一个模型，误判"无可用模型"而放弃故障转移
        val pool = activeModels.filter { it.id !in exclude }
        if (pool.isEmpty()) return null
        val speedResults = RouterState.getSpeedTestResults()
        val realLatencies = RouterState.getRealLatencies()
        // 排序依据：新鲜的真实流量被动延迟（TTFT EWMA）优先，反映当前真实状况；
        // 无被动数据时用主动测速结果兜底（冷启动/低流量场景）
        fun rankOf(m: ConfigModelItem): Long =
            realLatencies[m.id] ?: speedResults[m.id]?.takeIf { it >= 0 } ?: Long.MAX_VALUE
        // 已标记错误/测速失败的模型排到最后（isModelAvailable 同时检查 errorMap 和 speedResults）；
        // 不再按"是否有主动测速结果"分层——自动测速关闭时，被动延迟数据足以参与排序，
        // 单次手动测速过的模型不应永久压过被动延迟更好的未测速模型。全部不可用时退化为全部候选，仍尝试请求
        val candidates = pool.filter { RouterState.isModelAvailable(it.id) }.ifEmpty { pool }
        val sortedBySpeed = candidates.sortedBy { rankOf(it) }
        val bestTime = rankOf(sortedBySpeed.first())
        // 当候选模型均无延迟数据时，直接从所有候选中选最少连接的
        if (bestTime == Long.MAX_VALUE) {
            return candidates.minByOrNull { RouterState.getActiveConnections(it.id) }?.id
        }
        val nearBest = sortedBySpeed.filter { m ->
            val rt = rankOf(m)
            if (rt == Long.MAX_VALUE) true
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
