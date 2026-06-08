package com.example.modelrouter.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.modelrouter.R
import com.example.modelrouter.databinding.FragmentApiKeysBinding
import com.example.modelrouter.models.ProviderInfo
import com.example.modelrouter.models.ProviderModel
import com.example.modelrouter.models.RateLimitType
import com.example.modelrouter.models.KeySwitchStrategy
import com.example.modelrouter.service.ProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiKeysFragment : Fragment() {
    private lateinit var binding: FragmentApiKeysBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentApiKeysBinding.inflate(inflater, container, false)
        setupClickListeners()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun setupClickListeners() {
        binding.btnAddProvider.setOnClickListener {
            showAddProviderDialog()
        }
    }

    private fun loadData() {
        val container = binding.providersContainer
        while (container.childCount > 1) {
            container.removeViewAt(0)
        }

        val providers = ProviderManager.getAllProviders()
        var addBtnIndex = container.indexOfChild(binding.btnAddProvider)

        for (provider in providers) {
            val card = buildProviderCard(provider)
            container.addView(card, addBtnIndex)
            addBtnIndex++
        }
    }

    private fun buildProviderCard(provider: ProviderInfo): com.google.android.material.card.MaterialCardView {
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            radius = 16f
            cardElevation = 0f
            strokeColor = ContextCompat.getColor(context, R.color.border)
            strokeWidth = 1
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = TextView(requireContext()).apply {
            text = if (provider.isDefault) "★" else "●"
            setTextColor(ContextCompat.getColor(context, if (provider.isDefault) R.color.accent else R.color.info))
            textSize = 16f
        }

        val nameText = TextView(requireContext()).apply {
            text = provider.name
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 10
            }
        }

        val rateLimitText = TextView(requireContext()).apply {
            text = when (provider.rateLimitType) {
                RateLimitType.UNLIMITED -> "无限制"
                RateLimitType.PER_MINUTE -> "${provider.rateLimitValue}/min"
                RateLimitType.PER_5_HOURS -> "${provider.rateLimitValue}/5h"
                RateLimitType.PER_DAY -> "${provider.rateLimitValue}/day"
            }
            setTextColor(ContextCompat.getColor(context, R.color.info))
            textSize = 11f
            background = ContextCompat.getDrawable(context, R.color.chipBackground)
            setPadding(10, 4, 10, 4)
        }

        val strategyText = TextView(requireContext()).apply {
            text = when (provider.keySwitchStrategy) {
                KeySwitchStrategy.THRESHOLD -> "阈值切换"
                KeySwitchStrategy.EVERY_REQUEST -> "轮询切换"
            }
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 11f
            background = ContextCompat.getDrawable(context, R.color.chipBackground)
            setPadding(10, 4, 10, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener {
                showKeySwitchStrategyDialog(provider)
            }
        }

        headerRow.addView(icon)
        headerRow.addView(nameText)
        headerRow.addView(rateLimitText)
        headerRow.addView(strategyText)
        content.addView(headerRow)

        val baseUrlLabel = TextView(requireContext()).apply {
            text = "Base URL"
            setTextColor(ContextCompat.getColor(context, R.color.textMuted))
            textSize = 11f
            setPadding(0, 12, 0, 2)
        }
        content.addView(baseUrlLabel)

        val baseUrlText = TextView(requireContext()).apply {
            text = provider.baseUrl
            setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            background = ContextCompat.getDrawable(context, R.color.surfaceVariant)
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                showEditDialog("编辑 Base URL - ${provider.name}", provider.baseUrl) { newUrl ->
                    if (newUrl.isNotEmpty()) {
                        ProviderManager.updateBaseUrl(provider.id, newUrl)
                        loadData()
                    }
                }
            }
        }
        content.addView(baseUrlText)

        val keysLabel = TextView(requireContext()).apply {
            text = "API Keys (${provider.apiKeys.size}个)"
            setTextColor(ContextCompat.getColor(context, R.color.textMuted))
            textSize = 11f
            setPadding(0, 12, 0, 2)
        }
        content.addView(keysLabel)

        for ((index, key) in provider.apiKeys.withIndex()) {
            val keyRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val keyText = TextView(requireContext()).apply {
                text = "${index + 1}. ${maskKey(key)}"
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                background = ContextCompat.getDrawable(context, R.color.surfaceVariant)
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    showEditDialog("编辑 Key ${index + 1} - ${provider.name}", key) { newKey ->
                        if (newKey.isNotEmpty() && newKey != key) {
                            val currentProvider = ProviderManager.getProvider(provider.id) ?: provider
                            val updatedKeys = currentProvider.apiKeys.map { if (it == key) newKey else it }
                            ProviderManager.updateProvider(currentProvider.copy(apiKeys = updatedKeys))
                        }
                        loadData()
                    }
                }
            }

            val deleteBtn = TextView(requireContext()).apply {
                text = "删除"
                setTextColor(ContextCompat.getColor(context, R.color.error))
                textSize = 12f
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("确认删除")
                        .setMessage("确定要删除 Key ${index + 1} 吗？")
                        .setPositiveButton("删除") { _, _ ->
                            ProviderManager.removeApiKey(provider.id, key)
                            loadData()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }

            keyRow.addView(keyText)
            keyRow.addView(deleteBtn)
            content.addView(keyRow)
        }

        val addKeyBtn = TextView(requireContext()).apply {
            text = "+ 添加 API Key"
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 13f
            setPadding(0, 8, 0, 4)
            setOnClickListener {
                showEditDialog("添加 API Key - ${provider.name}", "") { newKey ->
                    if (newKey.isNotEmpty()) {
                        ProviderManager.addApiKey(provider.id, newKey)
                        loadData()
                    }
                }
            }
        }
        content.addView(addKeyBtn)

        val modelsLabel = TextView(requireContext()).apply {
            text = "可用模型 (${provider.models.size}个)"
            setTextColor(ContextCompat.getColor(context, R.color.textMuted))
            textSize = 11f
            setPadding(0, 8, 0, 2)
        }
        content.addView(modelsLabel)

        for ((modelIndex, model) in provider.models.withIndex()) {
            val modelRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 3, 0, 3)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val modelText = TextView(requireContext()).apply {
                text = model.name
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 12f
                background = ContextCompat.getDrawable(context, R.color.surfaceVariant)
                setPadding(12, 6, 12, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val modelIdHint = TextView(requireContext()).apply {
                text = model.id
                setTextColor(ContextCompat.getColor(context, R.color.textMuted))
                textSize = 9f
                setPadding(0, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val deleteModelBtn = TextView(requireContext()).apply {
                text = "删除"
                setTextColor(ContextCompat.getColor(context, R.color.error))
                textSize = 11f
                setPadding(12, 6, 12, 6)
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除模型")
                        .setMessage("确定要删除模型 ${model.name} 吗？")
                        .setPositiveButton("删除") { _, _ ->
                            val updatedModels = provider.models.filter { it.id != model.id }
                            ProviderManager.updateModels(provider.id, updatedModels)
                            loadData()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }

            modelRow.addView(modelText)
            modelRow.addView(modelIdHint)
            modelRow.addView(deleteModelBtn)
            content.addView(modelRow)
        }

        val modelsActionsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, 4, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val addModelBtn = TextView(requireContext()).apply {
            text = "+ 添加模型"
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 13f
            setPadding(0, 4, 16, 4)
            setOnClickListener {
                showAddModelDialog(provider)
            }
        }
        modelsActionsRow.addView(addModelBtn)

        val fetchModelsBtn = TextView(requireContext()).apply {
            text = "从API获取"
            setTextColor(ContextCompat.getColor(context, R.color.info))
            textSize = 13f
            setPadding(16, 4, 0, 4)
            setOnClickListener {
                fetchModelsFromApi(provider)
            }
        }
        modelsActionsRow.addView(fetchModelsBtn)

        content.addView(modelsActionsRow)

        val actionsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 12, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val rateLimitBtn = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "速率配置"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.info))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.chipBackground)
            cornerRadius = 8
            setPadding(8, 0, 8, 0)
            setOnClickListener {
                showRateLimitDialog(provider)
            }
        }
        actionsRow.addView(rateLimitBtn)

        if (!provider.isDefault) {
            val deleteProviderBtn = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "删除厂家"
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.error))
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.chipBackground)
                cornerRadius = 8
                setPadding(8, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除厂家")
                        .setMessage("确定要删除厂家 ${provider.name} 吗？此操作不可撤销。")
                        .setPositiveButton("删除") { _, _ ->
                            ProviderManager.removeProvider(provider.id)
                            loadData()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            actionsRow.addView(deleteProviderBtn)
        }

        content.addView(actionsRow)
        card.addView(content)
        return card
    }

    private fun showAddProviderDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val etName = EditText(requireContext()).apply {
            hint = "厂家名称 (如: OpenAI)"
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etName)

        val etId = EditText(requireContext()).apply {
            hint = "厂家ID (如: openai, 小写英文)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etId)

        val etBaseUrl = EditText(requireContext()).apply {
            hint = "Base URL (如: https://api.openai.com/v1)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etBaseUrl)

        val etApiKey = EditText(requireContext()).apply {
            hint = "API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etApiKey)

        val rateLimitLabel = TextView(requireContext()).apply {
            text = "速率限制方式"
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        layout.addView(rateLimitLabel)

        val spinnerRateLimit = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                RateLimitType.values().map { it.displayName }
            )
        }
        layout.addView(spinnerRateLimit)

        val etRateLimitValue = EditText(requireContext()).apply {
            hint = "限制值 (如: 40)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("40")
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etRateLimitValue)

        AlertDialog.Builder(requireContext())
            .setTitle("添加新厂家")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val name = etName.text.toString().trim()
                val id = etId.text.toString().trim().lowercase()
                val baseUrl = etBaseUrl.text.toString().trim()
                val apiKey = etApiKey.text.toString().trim()
                val rateLimitType = RateLimitType.values()[spinnerRateLimit.selectedItemPosition]
                val rateLimitValue = etRateLimitValue.text.toString().toIntOrNull() ?: 40

                if (name.isEmpty() || id.isEmpty() || baseUrl.isEmpty()) {
                    android.widget.Toast.makeText(context, "请填写必要信息", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val provider = ProviderInfo(
                    id = id,
                    name = name,
                    baseUrl = baseUrl,
                    apiKeys = if (apiKey.isNotEmpty()) listOf(apiKey) else emptyList(),
                    _rateLimitType = rateLimitType,
                    rateLimitValue = rateLimitValue,
                    switchThreshold = (rateLimitValue * 0.85).toInt(),
                    models = emptyList(),
                    enabled = true,
                    isDefault = false
                )

                if (ProviderManager.addProvider(provider)) {
                    loadData()
                } else {
                    android.widget.Toast.makeText(context, "厂家ID已存在", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showKeySwitchStrategyDialog(provider: ProviderInfo) {
        val options = KeySwitchStrategy.values().map { it.displayName }.toTypedArray()
        val currentIndex = provider.keySwitchStrategy.ordinal

        AlertDialog.Builder(requireContext())
            .setTitle("Key切换策略 - ${provider.name}")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selectedStrategy = KeySwitchStrategy.values()[which]
                if (selectedStrategy != provider.keySwitchStrategy) {
                    ProviderManager.updateKeySwitchStrategy(provider.id, selectedStrategy)
                    loadData()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRateLimitDialog(provider: ProviderInfo) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val rateLimitLabel = TextView(requireContext()).apply {
            text = "速率限制方式"
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            textSize = 14f
            setPadding(0, 0, 0, 4)
        }
        layout.addView(rateLimitLabel)

        val spinnerRateLimit = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                RateLimitType.values().map { it.displayName }
            )
            setSelection(provider.rateLimitType.ordinal)
        }
        layout.addView(spinnerRateLimit)

        val etRateLimitValue = EditText(requireContext()).apply {
            hint = "限制值"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(provider.rateLimitValue.toString())
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etRateLimitValue)

        val etSwitchThreshold = EditText(requireContext()).apply {
            hint = "切换阈值"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(provider.switchThreshold.toString())
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etSwitchThreshold)

        AlertDialog.Builder(requireContext())
            .setTitle("速率限制配置 - ${provider.name}")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val rateLimitType = RateLimitType.values()[spinnerRateLimit.selectedItemPosition]
                val rateLimitValue = etRateLimitValue.text.toString().toIntOrNull() ?: 40
                val switchThreshold = etSwitchThreshold.text.toString().toIntOrNull() ?: 35

                ProviderManager.updateRateLimit(provider.id, rateLimitType, rateLimitValue, switchThreshold)
                loadData()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddModelDialog(provider: ProviderInfo) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val etModelId = EditText(requireContext()).apply {
            hint = "模型ID (如: gpt-4o, deepseek-chat)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etModelId)

        val etModelName = EditText(requireContext()).apply {
            hint = "显示名称 (如: GPT-4o, DeepSeek Chat)"
            setPadding(0, 16, 0, 16)
        }
        layout.addView(etModelName)

        AlertDialog.Builder(requireContext())
            .setTitle("添加模型 - ${provider.name}")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val modelId = etModelId.text.toString().trim()
                val modelName = etModelName.text.toString().trim()

                if (modelId.isEmpty()) {
                    Toast.makeText(context, "模型ID不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (provider.models.any { it.id == modelId }) {
                    Toast.makeText(context, "模型 $modelId 已存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val name = modelName.ifEmpty { modelId.split("/").lastOrNull() ?: modelId }
                val updatedModels = provider.models + ProviderModel(modelId, name)
                ProviderManager.updateModels(provider.id, updatedModels)
                loadData()
                Toast.makeText(context, "已添加模型 $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fetchModelsFromApi(provider: ProviderInfo) {
        if (provider.apiKeys.isEmpty()) {
            Toast.makeText(context, "请先添加API Key", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            Toast.makeText(context, "正在从 ${provider.name} 获取模型列表...", Toast.LENGTH_SHORT).show()

            val result = withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val apiKey = provider.apiKeys.first()
                    val url = "${provider.baseUrl.trimEnd('/')}/models"
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) return@withContext null

                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val dataArray = json.get("data")?.asJsonArray ?: return@withContext null
                    val fetchedModels = mutableListOf<ProviderModel>()
                    for (item in dataArray) {
                        val obj = item.asJsonObject
                        val id = obj.get("id")?.asString ?: continue
                        val name = id.split("/").lastOrNull() ?: id
                        fetchedModels.add(ProviderModel(id, name))
                    }
                    fetchedModels
                } catch (e: Exception) {
                    null
                }
            }

            if (result == null || result.isEmpty()) {
                Toast.makeText(context, "获取模型列表失败，请手动添加", Toast.LENGTH_LONG).show()
                return@launch
            }

            val existingIds = provider.models.map { it.id }.toSet()
            val newModels = result.filter { it.id !in existingIds }
            if (newModels.isEmpty()) {
                Toast.makeText(context, "没有新模型可添加", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val mergedModels = provider.models + newModels
            ProviderManager.updateModels(provider.id, mergedModels)
            loadData()
            Toast.makeText(context, "已添加 ${newModels.size} 个新模型 (共${mergedModels.size}个)", Toast.LENGTH_LONG).show()
        }
    }

    private fun showEditDialog(title: String, currentValue: String, onSave: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(currentValue)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            minWidth = 600
            setPadding(48, 32, 48, 32)
            textSize = 13f
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                onSave(input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun maskKey(key: String): String {
        if (key.length <= 12) return key
        return key.substring(0, 8) + "****" + key.substring(key.length - 4)
    }
}
