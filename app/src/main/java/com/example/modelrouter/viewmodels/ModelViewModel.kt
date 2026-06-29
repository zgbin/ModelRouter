package com.example.modelrouter.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.modelrouter.models.*
import com.example.modelrouter.network.NvidiaApiClient
import com.example.modelrouter.service.ConfigBackupManager
import com.example.modelrouter.service.ConfigManager
import com.example.modelrouter.utils.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _allModels = MutableLiveData<List<ModelItem>>()
    private val _models = MutableLiveData<List<ModelItem>>()
    val models: LiveData<List<ModelItem>> = _models

    private val _stats = MutableLiveData<ModelStats>()
    val stats: LiveData<ModelStats> = _stats

    private val _groups = MutableLiveData<List<GroupItem>>()
    val groups: LiveData<List<GroupItem>> = _groups

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    init {
        loadLocalModels()
        _groups.value = loadSavedGroups()
    }

    private fun loadSavedGroups(): List<GroupItem> {
        val json = prefs.getString("saved_groups", null) ?: return loadDefaultConfig()
        return try {
            val type = object : TypeToken<List<GroupItem>>() {}.type
            val saved: List<GroupItem> = gson.fromJson(json, type)
            if (saved.isNotEmpty()) saved else loadDefaultConfig()
        } catch (e: Exception) {
            loadDefaultConfig()
        }
    }

    private fun saveGroups() {
        val groups = _groups.value ?: return
        val json = gson.toJson(groups)
        prefs.edit().putString("saved_groups", json).apply()
        ConfigBackupManager.backupGroups(json)
        ConfigManager.reload()
    }

    fun loadModels() {
        _loading.value = true
        viewModelScope.launch {
            try {
                val response = NvidiaApiClient.apiService.getModels()
                if (response.isSuccessful) {
                    val data = response.body()
                    val items = data?.data?.map { m ->
                        ModelItem(
                            id = m.id,
                            name = m.id.split("/").lastOrNull() ?: m.id,
                            owner = m.ownedBy,
                            source = "nvidia_api"
                        )
                    } ?: emptyList()
                    _allModels.value = items
                    _models.value = items
                    val fetchedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
                    _stats.value = ModelStats(
                        totalCount = items.size,
                        ownersCount = items.map { it.owner }.distinct().size,
                        docVersion = "v1",
                        fetchedAt = fetchedAt,
                        topOwners = items.groupingBy { it.owner }.eachCount(),
                        sources = mapOf("nvidia_api" to items.size)
                    )
                    saveLocalModels(items, fetchedAt)
                } else {
                    _error.value = "API请求失败: ${response.code()}"
                }
            } catch (e: Exception) {
                _error.value = "网络连接失败: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun loadLocalModels() {
        try {
            val modelsJson = prefs.getString("cached_nvidia_models", null)
            val statsJson = prefs.getString("cached_nvidia_stats", null)
            if (modelsJson != null) {
                val type = object : TypeToken<List<ModelItem>>() {}.type
                val items: List<ModelItem> = gson.fromJson(modelsJson, type)
                _allModels.value = items
                _models.value = items
            }
            if (statsJson != null) {
                val stats: ModelStats = gson.fromJson(statsJson, ModelStats::class.java)
                _stats.value = stats
            }
        } catch (_: Exception) { }
    }

    private fun saveLocalModels(items: List<ModelItem>, fetchedAt: String) {
        try {
            val modelsJson = gson.toJson(items)
            val stats = _stats.value
            val statsJson = if (stats != null) gson.toJson(stats) else null
            prefs.edit()
                .putString("cached_nvidia_models", modelsJson)
                .putString("cached_nvidia_stats", statsJson)
                .apply()
        } catch (_: Exception) { }
    }

    fun searchModels(query: String) {
        _loading.value = true
        viewModelScope.launch {
            try {
                val allModels = _allModels.value ?: emptyList()
                if (query.isEmpty()) {
                    _models.value = allModels
                } else {
                    val q = query.lowercase()
                    val results = allModels.filter { m ->
                        m.id.lowercase().contains(q) ||
                        m.name.lowercase().contains(q) ||
                        m.owner.lowercase().contains(q)
                    }
                    _models.value = results
                }
            } catch (e: Exception) {
                _error.value = "搜索失败: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun refreshData() {
        loadModels()
    }

    fun addModel(modelId: String, modelName: String, groupName: String, providerId: String = "nvidia", callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val updatedGroups = currentGroups.map { group ->
            if (group.name == groupName) {
                val newPriority = group.models.size + 1
                val newModel = ConfigModelItem(
                    id = modelId,
                    name = modelName,
                    priority = newPriority,
                    provider = providerId,
                    _providerId = providerId,
                    timeout = 60
                )
                group.copy(models = group.models + newModel)
            } else {
                group
            }
        }
        _groups.value = updatedGroups
        saveGroups()
        callback(true, "模型已添加到 $groupName")
    }

    fun replaceModel(oldModelId: String, newModelId: String, newModelName: String, groupName: String, newProviderId: String = "nvidia", callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val updatedGroups = currentGroups.map { group ->
            if (group.name == groupName) {
                val updatedModels = group.models.map { model ->
                    if (model.id == oldModelId) {
                        model.copy(id = newModelId, name = newModelName, provider = newProviderId, _providerId = newProviderId)
                    } else {
                        model
                    }
                }
                group.copy(models = updatedModels)
            } else {
                group
            }
        }
        _groups.value = updatedGroups
        saveGroups()
        callback(true, "模型已替换为 $newModelName")
    }

    fun deleteModel(modelId: String, groupName: String, callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val updatedGroups = currentGroups.map { group ->
            if (group.name == groupName) {
                val updatedModels = group.models.filter { it.id != modelId }
                    .mapIndexed { index, model -> model.copy(priority = index + 1) }
                group.copy(models = updatedModels)
            } else {
                group
            }
        }
        _groups.value = updatedGroups
        saveGroups()
        callback(true, "模型已删除")
    }

    fun toggleModelEnabled(modelId: String, groupName: String, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val updatedGroups = currentGroups.map { group ->
            if (group.name == groupName) {
                val updatedModels = group.models.map { model ->
                    if (model.id == modelId) {
                        model.copy(enabled = enabled)
                    } else {
                        model
                    }
                }
                group.copy(models = updatedModels)
            } else {
                group
            }
        }
        _groups.value = updatedGroups
        saveGroups()
        val status = if (enabled) "启用" else "禁用"
        callback(true, "模型已$status")
    }

    fun renameGroup(oldName: String, newName: String, callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val exists = currentGroups.any { it.name == newName }
        if (exists) {
            callback(false, "分组名 $newName 已存在")
            return
        }
        val updatedGroups = currentGroups.map { group ->
            if (group.name == oldName) {
                group.copy(name = newName)
            } else {
                group
            }
        }
        _groups.value = updatedGroups
        saveGroups()
        callback(true, "分组已重命名为 $newName")
    }

    fun addGroup(name: String, description: String, port: Int, callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val exists = currentGroups.any { it.name == name }
        if (exists) {
            callback(false, "分组名 $name 已存在")
            return
        }
        val newGroup = GroupItem(
            name = name,
            description = description,
            port = port,
            models = emptyList()
        )
        _groups.value = currentGroups + newGroup
        saveGroups()
        callback(true, "分组 $name 已添加")
    }

    fun deleteGroup(groupName: String, callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val target = currentGroups.find { it.name == groupName }
        if (target == null) {
            callback(false, "分组不存在")
            return
        }
        _groups.value = currentGroups.filter { it.name != groupName }
        saveGroups()
        callback(true, "分组 $groupName 已删除")
    }

    fun toggleGroupEnabled(groupName: String, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        val currentGroups = _groups.value ?: emptyList()
        val updatedGroups = currentGroups.map { group ->
            if (group.name == groupName) {
                group.copy(enabled = enabled)
            } else {
                group
            }
        }
        _groups.value = updatedGroups
        saveGroups()
        val status = if (enabled) "启用" else "禁用"
        callback(true, "分组 $groupName 已$status")
    }

    fun clearError() {
        _error.value = null
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
