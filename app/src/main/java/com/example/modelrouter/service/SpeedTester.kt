package com.example.modelrouter.service

import android.util.Log
import com.example.modelrouter.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SpeedTester(
    private val client: OkHttpClient,
    private val keyManager: ApiKeyManager
) {
    companion object {
        private const val TAG = "SpeedTester"
        private const val TIMEOUT_MS = 120_000L
    }

    suspend fun testModel(modelId: String, providerId: String = "nvidia"): SpeedTestResult = withContext(Dispatchers.IO) {
        val provider = ProviderManager.getProvider(providerId)
        val baseUrl = provider?.baseUrl ?: Constants.DEFAULT_BASE_URL
        val apiKey = if (provider != null && provider.apiKeys.isNotEmpty()) {
            ProviderManager.getSpeedTestKey(providerId)
        } else {
            keyManager.getSpeedTestKey()
        }

        val startTime = System.currentTimeMillis()
        try {
            val testBody = """
                {
                    "model": "$modelId",
                    "messages": [{"role": "user", "content": "Hi"}],
                    "max_tokens": 5,
                    "stream": true
                }
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = testBody.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorCode = response.code
                response.body?.close()
                val errorLabel = when (errorCode) {
                    429 -> "429 限流"
                    404 -> "404 未找到"
                    401 -> "401 认证失败"
                    403 -> "403 禁止访问"
                    500 -> "500 服务器错误"
                    502 -> "502 网关错误"
                    503 -> "503 服务不可用"
                    else -> "HTTP $errorCode"
                }
                return@withContext SpeedTestResult(
                    modelId = modelId,
                    responseTime = System.currentTimeMillis() - startTime,
                    success = false,
                    error = errorLabel,
                    timestamp = System.currentTimeMillis()
                )
            }

            val responseBody = response.body
            val stream = responseBody?.byteStream()
            if (stream == null) {
                responseBody?.close()
                return@withContext SpeedTestResult(
                    modelId = modelId,
                    responseTime = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "空响应",
                    timestamp = System.currentTimeMillis()
                )
            }

            val buffer = ByteArray(512)
            val bytesRead: Int
            val ttft: Long
            try {
                bytesRead = stream.read(buffer)
                ttft = System.currentTimeMillis() - startTime
            } finally {
                try { stream.close() } catch (_: Exception) {}
                try { responseBody.close() } catch (_: Exception) {}
            }

            if (bytesRead <= 0) {
                SpeedTestResult(
                    modelId = modelId,
                    responseTime = ttft,
                    success = false,
                    error = "空流",
                    timestamp = System.currentTimeMillis()
                )
            } else if (ttft <= TIMEOUT_MS) {
                SpeedTestResult(
                    modelId = modelId,
                    responseTime = ttft,
                    success = true,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                SpeedTestResult(
                    modelId = modelId,
                    responseTime = ttft,
                    success = false,
                    error = "超时>${TIMEOUT_MS / 1000}s",
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            SpeedTestResult(
                modelId = modelId,
                responseTime = System.currentTimeMillis() - startTime,
                success = false,
                error = "超时",
                timestamp = System.currentTimeMillis()
            )
        } catch (e: java.net.ConnectException) {
            SpeedTestResult(
                modelId = modelId,
                responseTime = System.currentTimeMillis() - startTime,
                success = false,
                error = "连接失败",
                timestamp = System.currentTimeMillis()
            )
        } catch (e: java.net.UnknownHostException) {
            SpeedTestResult(
                modelId = modelId,
                responseTime = System.currentTimeMillis() - startTime,
                success = false,
                error = "DNS解析失败",
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Speed test failed for $modelId", e)
            SpeedTestResult(
                modelId = modelId,
                responseTime = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message?.take(30) ?: "未知错误",
                timestamp = System.currentTimeMillis()
            )
        }
    }
}

data class SpeedTestResult(
    val modelId: String,
    val responseTime: Long,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long
)
