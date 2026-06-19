package com.example.modelrouter.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.example.modelrouter.R
import com.example.modelrouter.databinding.ActivityMainBinding
import com.example.modelrouter.service.ApiKeyManager
import com.example.modelrouter.service.ConfigBackupManager
import com.example.modelrouter.service.ConfigManager
import com.example.modelrouter.service.ModelRouterServer
import com.example.modelrouter.service.ProviderManager
import com.example.modelrouter.service.RouterService
import com.example.modelrouter.service.RouterState
import com.example.modelrouter.service.SpeedTester
import com.example.modelrouter.ui.fragments.ApiKeysFragment
import com.example.modelrouter.ui.fragments.ConfigFragment
import com.example.modelrouter.ui.fragments.DashboardFragment
import com.example.modelrouter.ui.fragments.ModelsFragment
import com.example.modelrouter.viewmodels.ModelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val servers = mutableListOf<ModelRouterServer>()
    private val startedPorts = mutableSetOf<Int>()

    private val modelsFragment by lazy { ModelsFragment() }
    private val dashboardFragment by lazy { DashboardFragment() }
    private val configFragment by lazy { ConfigFragment() }
    private val apiKeysFragment by lazy { ApiKeysFragment() }

    private var activeFragment: Fragment = modelsFragment

    companion object {
        private const val TAG = "MainActivity"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied, foreground service may be killed")
            Toast.makeText(this, "未授予通知权限，服务可能被系统杀掉，请在设置中开启", Toast.LENGTH_LONG).show()
        }
        startForegroundService()
    }

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.modelrouter.ACTION_RESTART_SERVERS") {
                Log.i(TAG, "Received restart servers broadcast")
                restartServersIfNeeded()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)

        ApiKeyManager.init(applicationContext)
        ConfigBackupManager.init(applicationContext)
        ConfigBackupManager.restoreIfNeeded()
        ConfigManager.init(applicationContext)
        ProviderManager.init(applicationContext)
        ConfigBackupManager.ensureBackup()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        startServers()
        observeGroupsForPorts()
        runStartupSpeedTests()

        if (savedInstanceState == null) {
            setupFragments()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_models -> modelsFragment
                R.id.nav_dashboard -> dashboardFragment
                R.id.nav_config -> configFragment
                R.id.nav_api_keys -> apiKeysFragment
                else -> modelsFragment
            }
            switchFragment(fragment)
            true
        }

        registerRestartReceiver()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> {
                    startForegroundService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(this, "需要通知权限来保持后台服务运行", Toast.LENGTH_LONG).show()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startForegroundService()
        }
    }

    private fun startForegroundService() {
        RouterService.start(this)
    }

    private fun registerRestartReceiver() {
        val filter = IntentFilter("com.example.modelrouter.ACTION_RESTART_SERVERS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(restartReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(restartReceiver, filter)
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)
            try {
                stopAllServers()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun runStartupSpeedTests() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val groups = ConfigManager.getAllGroups().filter { it.enabled }
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val tester = SpeedTester(client, ApiKeyManager)
                val allModels = groups.flatMap { g -> g.models.filter { it.enabled }.map { it to g.name } }
                for ((model, _) in allModels) {
                    try {
                        val result = tester.testModel(model.id, model.providerId)
                        if (result.success) {
                            RouterState.updateSpeedTestResult(model.id, result.responseTime)
                        } else {
                            RouterState.updateModelError(model.id, result.error ?: "失败")
                        }
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "Startup speed tests completed for ${allModels.size} models")
            } catch (e: Exception) {
                Log.e(TAG, "Startup speed tests failed", e)
            }
        }
    }

    private val viewModel: ModelViewModel by viewModels()

    private fun observeGroupsForPorts() {
        viewModel.groups.observe(this) { groups ->
            val ports = groups
                .filter { it.enabled }
                .map { it.port }
                .distinct()
                .ifEmpty { listOf(8190) }
            val portSet = ports.toSet()
            if (portSet != startedPorts) {
                lifecycleScope.launch(Dispatchers.IO) {
                    restartServers(ports)
                }
            }
        }
    }

    private fun startServers() {
        val groups = ConfigManager.getAllGroups()
        Log.i(TAG, "ConfigManager groups: ${groups.size} groups, enabled=${groups.count{it.enabled}}")
        val ports = groups
            .filter { it.enabled }
            .map { it.port }
            .distinct()
            .ifEmpty { listOf(8190) }
        Log.i(TAG, "Ports to start: $ports")
        startOnPorts(ports)
    }

    @Synchronized
    private fun startOnPorts(ports: List<Int>) {
        stopAllServers()
        val ip = getLocalIpAddress() ?: "unknown"
        val sb = StringBuilder("服务已启动:")
        for (port in ports) {
            try {
                val srv = ModelRouterServer(port)
                srv.start()
                servers.add(srv)
                startedPorts.add(port)
                sb.append("\n  http://$ip:$port")
                Log.i(TAG, "Server started on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port", e)
            }
        }
        runOnUiThread {
            Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show()
        }
    }

    @Synchronized
    private fun restartServers(ports: List<Int>) {
        stopAllServers()
        startOnPorts(ports)
    }

    @Synchronized
    private fun restartServersIfNeeded() {
        if (servers.isEmpty() || servers.all { !it.isAlive }) {
            Log.i(TAG, "Servers not running, restarting...")
            startServers()
        }
    }

    @Synchronized
    private fun stopAllServers() {
        for (srv in servers) {
            try { srv.stop() } catch (_: Exception) {}
        }
        servers.clear()
        startedPorts.clear()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return null
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, apiKeysFragment, "api_keys").hide(apiKeysFragment)
            add(R.id.fragment_container, configFragment, "config").hide(configFragment)
            add(R.id.fragment_container, dashboardFragment, "dashboard").hide(dashboardFragment)
            add(R.id.fragment_container, modelsFragment, "models")
        }.commit()
        activeFragment = modelsFragment
    }

    private fun switchFragment(target: Fragment) {
        if (target == activeFragment) return
        supportFragmentManager.beginTransaction().apply {
            hide(activeFragment)
            show(target)
        }.commit()
        activeFragment = target
    }

    override fun onResume() {
        super.onResume()
        if (!RouterService.isRunning) {
            Log.i(TAG, "RouterService not running, restarting...")
            RouterService.start(this)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            restartServersIfNeeded()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(restartReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
