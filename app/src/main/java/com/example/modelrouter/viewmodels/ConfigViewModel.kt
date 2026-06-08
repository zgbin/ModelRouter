package com.example.modelrouter.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.modelrouter.models.GroupItem
import com.example.modelrouter.network.ModelRepository
import com.example.modelrouter.network.RetrofitClient
import kotlinx.coroutines.launch

class ConfigViewModel : ViewModel() {
    private val repository = ModelRepository(RetrofitClient.apiService)

    private val _groups = MutableLiveData<List<GroupItem>>()
    val groups: LiveData<List<GroupItem>> = _groups

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun loadConfig() {
        _loading.value = true
        viewModelScope.launch {
            try {
                val response = repository.getModels()
                if (response.isSuccessful) {
                    _groups.value = listOf(
                        GroupItem(
                            name = "综合对话组",
                            description = "通用对话和问答(全部支持tools)",
                            port = 8190,
                            models = listOf(
                                com.example.modelrouter.models.ConfigModelItem(
                                    id = "qwen/qwen3-next-80b-a3b-instruct",
                                    name = "Qwen3 Next 80B (最快)",
                                    priority = 1,
                                    provider = "nvidia",
                                    timeout = 30
                                ),
                                com.example.modelrouter.models.ConfigModelItem(
                                    id = "nvidia/llama-3.3-nemotron-super-49b-v1",
                                    name = "Nemotron Super 49B",
                                    priority = 2,
                                    provider = "nvidia",
                                    timeout = 30
                                ),
                                com.example.modelrouter.models.ConfigModelItem(
                                    id = "minimaxai/minimax-m2.5",
                                    name = "MiniMax M2.5",
                                    priority = 3,
                                    provider = "nvidia",
                                    timeout = 60
                                )
                            )
                        ),
                        GroupItem(
                            name = "代码组",
                            description = "代码生成、审查、调试(全部支持tools)",
                            port = 8191,
                            models = listOf(
                                com.example.modelrouter.models.ConfigModelItem(
                                    id = "qwen/qwen3-coder-480b-a35b-instruct",
                                    name = "Qwen3 Coder 480B (代码最强)",
                                    priority = 1,
                                    provider = "nvidia",
                                    timeout = 90
                                ),
                                com.example.modelrouter.models.ConfigModelItem(
                                    id = "z-ai/glm-5.1",
                                    name = "glm5.1",
                                    priority = 2,
                                    provider = "nvidia",
                                    timeout = 60
                                )
                            )
                        ),
                        GroupItem(
                            name = "复杂组",
                            description = "复杂任务处理(全部支持tools)",
                            port = 8192,
                            models = listOf(
                                com.example.modelrouter.models.ConfigModelItem(
                                    id = "meta/llama-3.1-405b-instruct",
                                    name = "Llama 3.1 405B Instruct",
                                    priority = 1,
                                    provider = "nvidia",
                                    timeout = 90
                                )
                            )
                        )
                    )
                } else {
                    _error.value = "加载配置失败"
                }
            } catch (e: Exception) {
                _error.value = "网络连接失败: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun replaceModel(oldModelId: String, newModelId: String, newModelName: String, groupName: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val response = repository.replaceModel(oldModelId, newModelId, newModelName, groupName)
                if (response.isSuccessful) {
                    val result = response.body()
                    callback(result?.success ?: false, result?.message ?: "")
                } else {
                    callback(false, "替换失败")
                }
            } catch (e: Exception) {
                callback(false, "网络连接失败: ${e.message}")
            }
        }
    }

    fun deleteModel(modelId: String, groupName: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val response = repository.deleteModel(modelId, groupName)
                if (response.isSuccessful) {
                    val result = response.body()
                    callback(result?.success ?: false, result?.message ?: "")
                } else {
                    callback(false, "删除失败")
                }
            } catch (e: Exception) {
                callback(false, "网络连接失败: ${e.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}