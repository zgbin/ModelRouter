package com.example.modelrouter.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.modelrouter.utils.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 配置备份管理器
 *
 * 将所有配置（分组、Provider、API Key）备份到外部存储，
 * 卸载 app 后不会被删除，重装时自动恢复。
 *
 * 备份位置: /sdcard/Android/media/com.example.modelrouter/config_backup/
 *
 * 调用关系：
 * - 由 ConfigManager/ProviderManager/ApiKeyManager 在保存配置时调用 backup
 * - 由 MainActivity 在启动时调用 restoreIfNeeded
 */
object ConfigBackupManager {

    private const val TAG = "ConfigBackupManager"
    private const val BACKUP_DIR = "config_backup"
    private const val BACKUP_VERSION = 1

    private lateinit var appContext: Context
    private val gson = Gson()

    // 备份文件名
    private const val FILE_GROUPS = "groups.json"
    private const val FILE_PROVIDERS = "providers.json"
    private const val FILE_API_KEYS = "api_keys.json"
    private const val FILE_META = "backup_meta.json"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 在所有 Manager 初始化完成后调用，确保配置被备份
     */
    fun ensureBackup() {
        backupAll()
    }

    private fun getBackupDir(): File {
        // 使用 getExternalMediaDirs()，返回 /sdcard/Android/media/<package>/
        // 不需要存储权限，且卸载后不会被删除
        val mediaDir = appContext.externalMediaDirs.firstOrNull()
            ?: File("/sdcard/Android/media/${appContext.packageName}")
        return File(mediaDir, BACKUP_DIR).also { it.mkdirs() }
    }

    // ===== 备份 =====

    /**
     * 备份分组配置
     */
    fun backupGroups(groupsJson: String) {
        writeBackup(FILE_GROUPS, groupsJson)
        updateMeta()
    }

    /**
     * 备份 Provider 配置
     */
    fun backupProviders(providersJson: String) {
        writeBackup(FILE_PROVIDERS, providersJson)
        updateMeta()
    }

    /**
     * 备份 API Key 配置
     */
    fun backupApiKeys(keysJson: String) {
        writeBackup(FILE_API_KEYS, keysJson)
        updateMeta()
    }

    /**
     * 一键备份所有配置
     */
    fun backupAll() {
        try {
            // 备份分组
            val groupsPrefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val groupsJson = groupsPrefs.getString("saved_groups", null)
            if (groupsJson != null) {
                writeBackup(FILE_GROUPS, groupsJson)
            }

            // 备份 Provider
            val providerPrefs = appContext.getSharedPreferences("provider_manager", Context.MODE_PRIVATE)
            val providersJson = providerPrefs.getString("providers_json", null)
            if (providersJson != null) {
                writeBackup(FILE_PROVIDERS, providersJson)
            }

            // 备份 API Key
            val keyPrefs = appContext.getSharedPreferences("api_key_manager", Context.MODE_PRIVATE)
            val keysMap = mutableMapOf<String, Any?>()
            keysMap["speed_test_key"] = keyPrefs.getString("speed_test_key", null)
            keysMap["work_keys_json"] = keyPrefs.getString("work_keys_json", null)
            keysMap["max_per_minute"] = keyPrefs.getInt("max_per_minute", 40)
            keysMap["switch_threshold"] = keyPrefs.getInt("switch_threshold", 35)
            writeBackup(FILE_API_KEYS, gson.toJson(keysMap))

            updateMeta()
            Log.i(TAG, "Full backup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Full backup failed", e)
        }
    }

    private fun writeBackup(filename: String, json: String) {
        try {
            val file = File(getBackupDir(), filename)
            file.writeText(json)
            Log.d(TAG, "Backed up $filename (${json.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup $filename", e)
        }
    }

    private fun updateMeta() {
        try {
            val meta = mapOf(
                "version" to BACKUP_VERSION,
                "timestamp" to System.currentTimeMillis(),
                "package" to appContext.packageName
            )
            writeBackup(FILE_META, gson.toJson(meta))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update backup meta", e)
        }
    }

    /**
     * 检查是否有可恢复的备份，且当前配置为空（首次安装或被清除）
     * 如果有备份且当前配置为空，自动恢复
     */
    fun restoreIfNeeded() {
        try {
            val backupDir = getBackupDir()
            if (!backupDir.exists()) {
                Log.d(TAG, "No backup directory found")
                return
            }

            val groupsPrefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val providerPrefs = appContext.getSharedPreferences("provider_manager", Context.MODE_PRIVATE)
            val keyPrefs = appContext.getSharedPreferences("api_key_manager", Context.MODE_PRIVATE)

            val needGroups = !groupsPrefs.contains("saved_groups")
            val needProviders = !providerPrefs.contains("providers_json")
            val needKeys = !keyPrefs.contains("work_keys_json")

            if (!needGroups && !needProviders && !needKeys) {
                Log.d(TAG, "All configs exist, skip restore")
                return
            }

            Log.i(TAG, "Restoring from backup: groups=$needGroups providers=$needProviders keys=$needKeys")
            restoreFromBackup(needGroups, needProviders, needKeys)
        } catch (e: Exception) {
            Log.e(TAG, "Restore check failed", e)
        }
    }

    private fun restoreFromBackup(restoreGroups: Boolean, restoreProviders: Boolean, restoreKeys: Boolean) {
        val backupDir = getBackupDir()
        var restored = 0

        // 恢复分组配置
        if (restoreGroups) {
            val groupsFile = File(backupDir, FILE_GROUPS)
            if (groupsFile.exists()) {
                val json = groupsFile.readText()
                if (json.isNotBlank()) {
                    appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString("saved_groups", json).apply()
                    restored++
                    Log.i(TAG, "Restored groups config")
                }
            }
        }

        // 恢复 Provider 配置
        if (restoreProviders) {
            val providersFile = File(backupDir, FILE_PROVIDERS)
            if (providersFile.exists()) {
                val json = providersFile.readText()
                if (json.isNotBlank()) {
                    appContext.getSharedPreferences("provider_manager", Context.MODE_PRIVATE)
                        .edit().putString("providers_json", json).apply()
                    restored++
                    Log.i(TAG, "Restored providers config")
                }
            }
        }

        // 恢复 API Key 配置
        if (restoreKeys) {
            val keysFile = File(backupDir, FILE_API_KEYS)
            if (keysFile.exists()) {
            try {
                val json = keysFile.readText()
                val type = object : TypeToken<Map<String, Any?>>() {}.type
                val keysMap: Map<String, Any?> = gson.fromJson(json, type)
                val editor = appContext.getSharedPreferences("api_key_manager", Context.MODE_PRIVATE).edit()

                (keysMap["speed_test_key"] as? String)?.let { editor.putString("speed_test_key", it) }
                (keysMap["work_keys_json"] as? String)?.let { editor.putString("work_keys_json", it) }
                (keysMap["max_per_minute"] as? Number)?.toInt()?.let { editor.putInt("max_per_minute", it) }
                (keysMap["switch_threshold"] as? Number)?.toInt()?.let { editor.putInt("switch_threshold", it) }

                editor.apply()
                restored++
                Log.i(TAG, "Restored API keys config")
            } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore API keys", e)
                }
            }
        }

        Log.i(TAG, "Restore completed: $restored configs restored")
    }

    /**
     * 检查备份是否存在
     */
    fun hasBackup(): Boolean {
        val backupDir = getBackupDir()
        return backupDir.exists() && File(backupDir, FILE_META).exists()
    }

    /**
     * 获取备份时间戳，0 表示无备份
     */
    fun getBackupTimestamp(): Long {
        try {
            val metaFile = File(getBackupDir(), FILE_META)
            if (!metaFile.exists()) return 0
            val json = metaFile.readText()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val meta: Map<String, Any> = gson.fromJson(json, type)
            return (meta["timestamp"] as? Double)?.toLong() ?: 0L
        } catch (_: Exception) {
            return 0L
        }
    }
}
