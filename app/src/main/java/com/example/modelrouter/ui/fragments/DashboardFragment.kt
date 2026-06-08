package com.example.modelrouter.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.modelrouter.R
import com.example.modelrouter.databinding.FragmentDashboardBinding
import com.example.modelrouter.models.*
import com.example.modelrouter.network.RouterApiClient
import com.example.modelrouter.service.SpeedTester
import com.example.modelrouter.service.RouterState
import com.example.modelrouter.ui.adapters.DashboardGroupAdapter
import com.example.modelrouter.viewmodels.ModelViewModel
import com.example.modelrouter.service.ApiKeyManager
import com.example.modelrouter.service.ProviderManager
import com.example.modelrouter.service.StatsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DashboardFragment : Fragment() {
    private lateinit var binding: FragmentDashboardBinding
    private val viewModel: ModelViewModel by activityViewModels()
    private lateinit var adapter: DashboardGroupAdapter
    private var speedTestResults = mutableMapOf<String, Long>()
    private var keyManagerStatus: KeyManagerStatus? = null
    private var groupCurrentModels = mutableMapOf<String, String>()
    private var autoRefreshJob: Job? = null
    private var autoSpeedTestJob: Job? = null
    private var batchSpeedTestJob: Job? = null

    private val buildingUI = AtomicBoolean(false)
    private val lastBuildTime = AtomicLong(0)
    private val lastStatsTotalCalls = java.util.concurrent.atomic.AtomicInteger(-1)
    private val uiUpdatePending = AtomicBoolean(false)

    private val autoSpeedTestPref by lazy {
        requireContext().getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)
    }

    private val speedTester by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        SpeedTester(client, ApiKeyManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDashboardBinding.inflate(inflater, container, false)
        setupRecyclerView()
        setupObservers()
        setupListeners()
        postBuildUI()
        fetchRuntimeFromServer()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        postBuildUI()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
        stopAutoSpeedTest()
        cancelBatchSpeedTest()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            postBuildUI()
            fetchRuntimeFromServer()
            startAutoRefresh()
            if (autoSpeedTestPref.getBoolean("auto_speed_test", false)) {
                startAutoSpeedTest()
            }
        } else {
            stopAutoRefresh()
            stopAutoSpeedTest()
            cancelBatchSpeedTest()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopAutoRefresh()
        stopAutoSpeedTest()
        cancelBatchSpeedTest()
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(10000)
                if (isActive) {
                    fetchRuntimeFromServer()
                    val groups = viewModel.groups.value ?: emptyList()
                    val activeIds = groups.flatMap { it.models.map { m -> m.id } }.toSet()
                    if (activeIds.isNotEmpty()) {
                        RouterState.cleanupStaleData(activeIds)
                        StatsManager.cleanupStaleData(activeIds)
                    }
                }
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun setupRecyclerView() {
        adapter = DashboardGroupAdapter(
            onLockClick = { modelId, groupName -> lockModelOnServer(modelId, groupName) },
            onUnlockClick = { groupName -> unlockGroupOnServer(groupName) },
            onSpeedTestClick = { modelId -> runSpeedTest(modelId) }
        )
        binding.rvGroups.layoutManager = LinearLayoutManager(context)
        binding.rvGroups.adapter = adapter
    }

    private fun setupObservers() {
        viewModel.groups.observe(viewLifecycleOwner) {
            postBuildUI()
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        RouterState.lockedModels.observe(viewLifecycleOwner) {
            postBuildUI()
        }

        RouterState.speedTestResults.observe(viewLifecycleOwner) { results ->
            results?.let {
                speedTestResults.clear()
                speedTestResults.putAll(it)
                postBuildUI()
            }
        }

        RouterState.modelErrors.observe(viewLifecycleOwner) {
            postBuildUI()
        }
    }

    private fun setupListeners() {
        binding.btnUnlock.setOnClickListener {
            unlockAllModels()
        }

        binding.btnSpeedTestAll.setOnClickListener {
            runAllSpeedTests()
        }

        val autoEnabled = autoSpeedTestPref.getBoolean("auto_speed_test", false)
        binding.switchAutoSpeedTest.isChecked = autoEnabled
        if (autoEnabled) {
            startAutoSpeedTest()
        }

        binding.switchAutoSpeedTest.setOnCheckedChangeListener { _, isChecked ->
            autoSpeedTestPref.edit().putBoolean("auto_speed_test", isChecked).apply()
            if (isChecked) {
                startAutoSpeedTest()
            } else {
                stopAutoSpeedTest()
            }
        }
    }

    private fun fetchRuntimeFromServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RouterApiClient.apiService.getDashboardData()
                if (response.isSuccessful) {
                    response.body()?.let { data ->
                        keyManagerStatus = data.keyManagerStatus
                        groupCurrentModels.clear()
                        groupCurrentModels.putAll(data.groupCurrentModel)
                        withContext(Dispatchers.Main) {
                            postBuildUI()
                        }
                    }
                }
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun postBuildUI() {
        val now = System.currentTimeMillis()
        val last = lastBuildTime.get()
        if (now - last < 200 && last > 0) {
            uiUpdatePending.set(true)
            return
        }

        lastBuildTime.set(now)

        if (!buildingUI.compareAndSet(false, true)) {
            uiUpdatePending.set(true)
            return
        }

        lifecycleScope.launch {
            try {
                val groups = viewModel.groups.value ?: emptyList()
                if (!::binding.isInitialized) return@launch

                val currentTotalCalls = StatsManager.getTotalCalls()

                if (groups.isEmpty()) {
                    binding.tvTotalCalls.text = currentTotalCalls.toString()
                    binding.tvGroupCount.text = "0"
                    binding.tvModelCount.text = "0"
                    binding.tvHealthyCount.text = "0/0"
                    binding.rvGroups.visibility = View.GONE
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.cardLockStatus.visibility = View.GONE
                    return@launch
                }

                lastStatsTotalCalls.set(currentTotalCalls)

                binding.tvGroupCount.text = groups.size.toString()
                val totalModels = groups.sumOf { it.models.size }
                binding.tvModelCount.text = totalModels.toString()

                val lockedModels = RouterState.getLockedModels()
                val localResults = speedTestResults.toMap()
                val liveCallStats = StatsManager.getModelStats()

                val dashboardGroups = groups.map { g ->
                    val sortedModels = g.models.sortedBy { m ->
                        val rt = localResults[m.id]
                        when {
                            rt == null -> Long.MAX_VALUE
                            rt < 0 -> Long.MAX_VALUE - 1
                            rt > 120000 -> Long.MAX_VALUE - 2
                            else -> rt
                        }
                    }

                    val lockedModelId = lockedModels[g.name]
                    var currentModelId = lockedModelId ?: sortedModels.firstOrNull { m ->
                        val rt = localResults[m.id]
                        rt != null && rt >= 0 && rt <= 120000
                    }?.id ?: sortedModels.firstOrNull()?.id ?: ""

                    DashboardGroup(
                        name = g.name,
                        port = g.port,
                        models = sortedModels.map { m ->
                            val responseTime = localResults[m.id]
                            val modelError = RouterState.getModelError(m.id)
                            val isAvailable = modelError == null && (responseTime == null || (responseTime >= 0 && responseTime <= 120000))
                            val errorMsg = modelError ?: if (!isAvailable) "失败" else null
                            DashboardModel(
                                id = m.id,
                                name = m.name,
                                providerName = ProviderManager.getProvider(m.providerId)?.name ?: m.providerId,
                                status = ModelStatus(
                                    isHealthy = isAvailable,
                                    totalRequests = liveCallStats[m.id] ?: 0,
                                    avgResponseTime = responseTime?.let { if (it >= 0) it / 1000.0 else null },
                                    isCurrent = m.id == currentModelId,
                                    isLocked = m.id == lockedModelId,
                                    errorMessage = errorMsg
                                )
                            )
                        }
                    )
                }

                if (!::binding.isInitialized) return@launch

                val healthyCount = dashboardGroups.sumOf { g -> g.models.count { it.status.isHealthy } }
                binding.tvHealthyCount.text = "$healthyCount/$totalModels"
                binding.tvTotalCalls.text = currentTotalCalls.toString()

                adapter.submitList(dashboardGroups)

                if (lockedModels.isNotEmpty()) {
                    binding.cardLockStatus.visibility = View.VISIBLE
                    binding.tvLockedModel.text = lockedModels.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                } else {
                    binding.cardLockStatus.visibility = View.GONE
                }

                keyManagerStatus?.let { buildKeyManagerStatus(it) }
                binding.layoutKeyStatus.visibility = if (keyManagerStatus != null) View.VISIBLE else View.GONE

                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                binding.tvLastUpdate.text = "最后更新: ${timeFormat.format(Date())}"

                binding.tvEmpty.visibility = View.GONE
                binding.rvGroups.visibility = View.VISIBLE
            } finally {
                buildingUI.set(false)
                if (uiUpdatePending.compareAndSet(true, false)) {
                    lastBuildTime.set(0)
                    postBuildUI()
                }
            }
        }
    }

    private fun buildKeyManagerStatus(status: KeyManagerStatus) {
        binding.containerKeyItems.removeAllViews()
        val maxPm = status.maxPerMinute
        val maxPmInt = maxPm.toIntOrNull() ?: 35

        if (status.requestCounts.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "暂无Key使用数据"
                setTextColor(ContextCompat.getColor(context, R.color.textMuted))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            binding.containerKeyItems.addView(emptyText)
            return
        }

        for ((keyName, count) in status.requestCounts) {
            val pct = ((count.toFloat() / maxPmInt) * 100).toInt().coerceAtMost(100)
            val displayName = if (keyName.length > 24) {
                keyName.substring(0, 12) + "..." + keyName.substring(keyName.length - 8)
            } else {
                keyName
            }

            val itemLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val nameText = TextView(requireContext()).apply {
                text = displayName
                setTextColor(ContextCompat.getColor(context, R.color.accent))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val countText = TextView(requireContext()).apply {
                text = "$count/$maxPm"
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(12, 0, 8, 0)
            }

            val barBg = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(80, 8).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.surfaceVariant))
            }

            val barFill = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (80 * pct / 100).coerceAtLeast(1),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(ContextCompat.getColor(
                    context,
                    if (pct > 80) R.color.error else if (pct > 50) R.color.warning else R.color.success
                ))
            }

            val barContainer = android.widget.FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(80, 8).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                addView(barBg)
                addView(barFill)
            }

            itemLayout.addView(nameText)
            itemLayout.addView(countText)
            itemLayout.addView(barContainer)
            binding.containerKeyItems.addView(itemLayout)
        }
    }

    private fun runSpeedTest(modelId: String) {
        lifecycleScope.launch {
            Toast.makeText(context, "开始测速: $modelId", Toast.LENGTH_SHORT).show()
            val providerId = findProviderIdForModel(modelId)
            val result = withContext(Dispatchers.IO) { speedTester.testModel(modelId, providerId) }
            if (result.success) {
                RouterState.updateSpeedTestResult(modelId, result.responseTime)
                Toast.makeText(context, "测速完成: ${result.responseTime}ms", Toast.LENGTH_SHORT).show()
            } else {
                RouterState.updateModelError(modelId, result.error ?: "失败")
                Toast.makeText(context, "测速失败: ${result.error}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runAllSpeedTests() {
        val groups = viewModel.groups.value ?: emptyList()
        val allModels = groups.flatMap { it.models }.filter { it.providerId == "nvidia" }
        if (allModels.isEmpty()) {
            Toast.makeText(context, "没有NVIDIA模型可测速", Toast.LENGTH_SHORT).show()
            return
        }

        cancelBatchSpeedTest()

        batchSpeedTestJob = lifecycleScope.launch {
            Toast.makeText(context, "开始批量测速 (${allModels.size}个模型, 5并发)...", Toast.LENGTH_SHORT).show()
            binding.progressBar.visibility = View.VISIBLE

            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val totalCount = allModels.size
            val channel = Channel<ConfigModelItem>(5)

            withContext(Dispatchers.IO) {
                val producers = launch {
                    for (model in allModels) {
                        channel.send(model)
                    }
                    channel.close()
                }

                val workers = (1..5).map {
                    launch {
                        for (model in channel) {
                            if (!isActive) break
                            val providerId = model.providerId
                            val result = speedTester.testModel(model.id, providerId)
                            if (result.success) {
                                RouterState.updateSpeedTestResult(model.id, result.responseTime)
                            } else {
                                RouterState.updateModelError(model.id, result.error ?: "失败")
                            }
                            val done = completedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                if (isActive) {
                                    binding.tvLastUpdate.text = "测速进度: $done/$totalCount"
                                }
                            }
                        }
                    }
                }

                producers.join()
                workers.forEach { it.join() }
            }

            if (isActive) {
                binding.progressBar.visibility = View.GONE
                val results = RouterState.getSpeedTestResults()
                val successCount = results.count { it.value >= 0 }
                Toast.makeText(context, "批量测速完成: $successCount/$totalCount 成功", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cancelBatchSpeedTest() {
        batchSpeedTestJob?.cancel()
        batchSpeedTestJob = null
        if (::binding.isInitialized) {
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun lockModelOnServer(modelId: String, groupName: String) {
        lifecycleScope.launch {
            try {
                RouterState.lockModel(groupName, modelId)
                Toast.makeText(context, "模型已锁定: $modelId ($groupName)", Toast.LENGTH_SHORT).show()
                postBuildUI()
            } catch (e: Exception) {
                Toast.makeText(context, "锁定失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun unlockGroupOnServer(groupName: String) {
        lifecycleScope.launch {
            try {
                RouterState.unlockGroup(groupName)
                Toast.makeText(context, "分组 $groupName 已解锁，恢复自动选择", Toast.LENGTH_SHORT).show()
                postBuildUI()
            } catch (e: Exception) {
                Toast.makeText(context, "解锁失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun unlockAllModels() {
        lifecycleScope.launch {
            try {
                RouterState.unlockAll()
                Toast.makeText(context, "所有分组已解锁，恢复自动选择", Toast.LENGTH_SHORT).show()
                postBuildUI()
            } catch (e: Exception) {
                Toast.makeText(context, "解锁失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAutoSpeedTest() {
        stopAutoSpeedTest()
        autoSpeedTestJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(300000)
            while (isActive) {
                val groups = viewModel.groups.value ?: emptyList()
                val allModels = groups.flatMap { it.models }.filter { it.providerId == "nvidia" }
                if (allModels.isEmpty()) {
                    delay(300000)
                    continue
                }
                try {
                    val channel = Channel<ConfigModelItem>(5)
                    val producers = launch {
                        for (model in allModels) {
                            channel.send(model)
                        }
                        channel.close()
                    }
                    val workers = (1..5).map {
                        launch {
                            for (model in channel) {
                                if (!isActive) break
                                val providerId = model.providerId
                                val result = speedTester.testModel(model.id, providerId)
                                if (result.success) {
                                    RouterState.updateSpeedTestResult(model.id, result.responseTime)
                                } else {
                                    RouterState.updateModelError(model.id, result.error ?: "失败")
                                }
                            }
                        }
                    }
                    producers.join()
                    workers.forEach { it.join() }
                } catch (_: Exception) {}
                delay(300000)
            }
        }
    }

    private fun stopAutoSpeedTest() {
        autoSpeedTestJob?.cancel()
        autoSpeedTestJob = null
    }

    private fun findProviderIdForModel(modelId: String): String {
        val groups = viewModel.groups.value ?: emptyList()
        for (group in groups) {
            val model = group.models.find { it.id == modelId }
            if (model != null) return model.providerId
        }
        return ProviderManager.getProviderIdForModel(modelId)
    }
}