package com.example.modelrouter.service

import android.util.Log
import com.example.modelrouter.utils.Constants
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.AsyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelRouterServer(port: Int = 8190) : NanoHTTPD(port) {

    private val gson = Gson()

    private val connectionPool = okhttp3.ConnectionPool(20, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val streamClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 为指定模型创建带 TTFT 超时的流式客户端
     * readTimeout 设为模型配置的 timeout（秒），用于控制首字节到达前的超时
     * 一旦流式数据开始返回，OkHttp 的 readTimeout 会在每次 read 成功后重置
     */
    private fun createStreamClientForModel(modelId: String): OkHttpClient {
        val timeoutSec = configManager.getModelTimeout(modelId).toLong()
        return streamClient.newBuilder()
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
    }

    private val executor: ExecutorService = ThreadPoolExecutor(
        8,
        128,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(512),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val streamPipeExecutor: ExecutorService = ThreadPoolExecutor(
        4,
        64,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    init {
        setAsyncRunner(object : AsyncRunner {
            override fun exec(code: NanoHTTPD.ClientHandler) {
                executor.execute(code)
            }

            override fun closed(code: NanoHTTPD.ClientHandler) {}

            override fun closeAll() {
                executor.shutdownNow()
                streamPipeExecutor.shutdownNow()
            }
        })
    }

    private val keyManager = ApiKeyManager
    private val configManager = ConfigManager
    private val providerManager = ProviderManager
    private val speedTester = SpeedTester(client, keyManager)
    private val speedTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "ModelRouterServer"
    }

    override fun stop() {
        try {
            speedTestScope.cancel()
        } catch (_: Exception) {}
        try {
            client.dispatcher.cancelAll()
        } catch (_: Exception) {}
        try {
            streamClient.dispatcher.cancelAll()
        } catch (_: Exception) {}
        try {
            connectionPool.evictAll()
        } catch (_: Exception) {}
        try {
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: Exception) {}
        try {
            streamPipeExecutor.shutdown()
            if (!streamPipeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                streamPipeExecutor.shutdownNow()
            }
        } catch (_: Exception) {}
        super.stop()
        Log.i(TAG, "ModelRouterServer on port $listeningPort stopped and resources released")
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            val method = session.method

            Log.d(TAG, "Request: $method $uri")

            val requestBody = if (method == Method.POST) readBody(session) else null

            when {
                uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletion(requestBody ?: "{}")
                uri == "/v1/messages" && method == Method.POST -> handleAnthropicMessages(requestBody ?: "{}")
                uri == "/v1/models" && method == Method.GET -> handleModels()
                uri == "/api/status" && method == Method.GET -> handleStatus()
                uri == "/api/speed_test" && method == Method.POST -> handleSpeedTest(requestBody ?: "{}")
                uri == "/api/lock" && method == Method.POST -> handleLock(requestBody ?: "{}")
                uri == "/api/unlock" && method == Method.POST -> handleUnlock()
                uri == "/api/config" && method == Method.GET -> handleGetConfig()
                uri == "/api/stats" && method == Method.GET -> handleGetStats()
                uri == "/api/dashboard" && method == Method.GET -> handleDashboardApi()
                uri == "/api/reload" && method == Method.POST -> handleReload()
                uri == "/api/lock_model" && method == Method.POST -> handleLockModelApi(session)
                uri == "/api/unlock_model" && method == Method.POST -> handleUnlockModelApi()
                uri == "/api/lock_status" && method == Method.GET -> handleLockStatusApi()
                uri == "/health" && method == Method.GET -> handleHealth()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    gson.toJson(mapOf("error" to "Not found")))
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "Server overloaded, rejecting request")
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to "Server overloaded, please retry", "type" to "server_overloaded"))))
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in serve()", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                gson.toJson(mapOf("error" to mapOf("message" to "Internal server error: ${e.message}", "type" to "server_error"))))
        }
    }

    private fun handleChatCompletion(body: String): Response {
        return try {
            val jsonBody = JsonParser.parseString(body).asJsonObject
            val isStream = jsonBody.get("stream")?.asBoolean ?: false
            val groupName = jsonBody.get("group")?.asString

            if (isStream) {
                handleStreamCompletion(jsonBody, groupName)
            } else {
                handleNonStreamCompletion(jsonBody, groupName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat completion", e)
            jsonError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleNonStreamCompletion(jsonBody: JsonObject, groupName: String?): Response {
        val group = groupName ?: configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()

        for (modelRetry in 0..2) {
            var modelId: String? = null
            var modelAcquired = false
            try {
                modelId = if (modelRetry == 0) getModelToUse(jsonBody, group)
                          else configManager.selectFastestModel(group)
                if (modelId == null) return jsonError("api_error", "No model available")
                if (modelId in triedModelIds) return jsonError("api_error", "No alternative model available")
                triedModelIds.add(modelId)

                RouterState.acquireModel(modelId)
                modelAcquired = true
                jsonBody.addProperty("model", modelId)
                jsonBody.remove("group")

                val providerId = getProviderIdForModel(modelId)
                val apiKey = providerManager.getNextKey(providerId)
                if (apiKey.isEmpty()) return jsonError("auth_error", "No API key available for provider $providerId")

                val sanitizedBody = sanitizeRequestBody(jsonBody)
                val response = forwardToProvider(sanitizedBody.toString(), apiKey, providerId)

                var isEarlyRateLimit = false
                try {
                    val parsed = JsonParser.parseString(response).asJsonObject
                    val error = parsed.get("error")
                    if (error != null && error.isJsonObject) {
                        val errorType = error.asJsonObject.get("type")?.asString
                        if (errorType == "early_rate_limit") isEarlyRateLimit = true
                    }
                    if (!isEarlyRateLimit) {
                        if (parsed.has("error")) {
                            StatsManager.recordCall(modelId, false)
                            val errorMsg = parsed.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.asString ?: "unknown"
                            val errorType = parsed.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.get("type")?.asString ?: ""
                            RouterState.updateModelError(modelId, "上游错误: $errorMsg")
                            if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                            Log.w(TAG, "Non-stream upstream error on model $modelId ($errorType), switching model")
                            continue
                        }
                        if (!parsed.has("choices")) {
                            StatsManager.recordCall(modelId, false)
                            return jsonError("api_error", "Invalid response format from upstream: missing choices")
                        }
                    }
                } catch (_: Exception) {}

                if (isEarlyRateLimit) {
                    RouterState.updateModelError(modelId, "429 限流")
                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                    Log.w(TAG, "Early 429 on model $modelId, switching model (retry ${modelRetry + 1})")
                    continue
                }

                StatsManager.recordCall(modelId, true)
                return newFixedLengthResponse(Response.Status.OK, "application/json", response)
            } catch (e: Exception) {
                Log.w(TAG, "Non-stream completion error on model $modelId, switching to next model")
                try { modelId?.let { StatsManager.recordCall(it, false) } } catch (_: Exception) {}
                if (modelId != null) {
                    RouterState.updateModelError(modelId, "异常: ${e.message}")
                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                }
                continue
            } finally {
                if (modelAcquired) modelId?.let { RouterState.releaseModel(it) }
            }
        }
        return jsonError("api_error", "No model available after early rate limit")
    }

    private fun handleStreamCompletion(jsonBody: JsonObject, groupName: String?): Response {
        val group = groupName ?: configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()

        for (modelRetry in 0..2) {
            val modelId = if (modelRetry == 0) getModelToUse(jsonBody, group)
                          else configManager.selectFastestModel(group)
            if (modelId == null) return jsonError("api_error", "No model available")
            if (modelId in triedModelIds) return jsonError("api_error", "No alternative model available")
            triedModelIds.add(modelId)

            var modelAcquired = false
            var switchModel = false
            try {
                RouterState.acquireModel(modelId)
                modelAcquired = true
                jsonBody.addProperty("model", modelId)
                jsonBody.addProperty("stream", true)
                jsonBody.remove("group")

                val providerId = getProviderIdForModel(modelId)
                val apiKey = providerManager.getNextKey(providerId)
                if (apiKey.isEmpty()) {
                    return jsonError("auth_error", "No API key available for provider $providerId")
                }

                val baseUrl = providerManager.getProvider(providerId)?.baseUrl ?: Constants.DEFAULT_BASE_URL

                var currentApiKey = apiKey
                var attempt = 0
                val maxRetries = 2
                var totalSleepMs = 0L
                val maxTotalSleepMs = 5000L

                while (true) {
                    attempt++
                    val bodyStr = sanitizeRequestBody(jsonBody).toString()
                    val requestBody = bodyStr
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/chat/completions")
                        .header("Authorization", "Bearer $currentApiKey")
                        .header("Content-Type", "application/json")
                        .post(requestBody)
                        .build()

                    try {
                        val response = createStreamClientForModel(modelId).newCall(request).execute()
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            if (response.code == 429) {
                                if (providerManager.isEarly429(providerId, currentApiKey)) {
                                    RouterState.updateModelError(modelId, "429 限流")
                                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                                    Log.w(TAG, "Stream early 429 on model $modelId, switching model")
                                    switchModel = true
                                    break
                                }
                                if (attempt <= maxRetries) {
                                    val newKey = providerManager.peekNextKey(providerId, currentApiKey)
                                    if (newKey.isNotEmpty() && newKey != currentApiKey) {
                                        currentApiKey = newKey
                                        Log.w(TAG, "Stream 429, retry $attempt with different key")
                                        continue
                                    }
                                    val delay = (500L * attempt)
                                    if (totalSleepMs + delay > maxTotalSleepMs) {
                                        Log.w(TAG, "Stream 429, total sleep limit reached, giving up")
                                        break
                                    }
                                    totalSleepMs += delay
                                    Log.w(TAG, "Stream 429, retry $attempt after ${delay}ms (same key)")
                                    Thread.sleep(delay)
                                    continue
                                }
                            }
                            if (response.code == 400) {
                                Log.w(TAG, "Stream 400 error from $providerId: ${errorBody.take(300)}")
                                try {
                                    val reqBody = JsonParser.parseString(bodyStr).asJsonObject
                                    val sentParams = reqBody.keySet().filter { it != "messages" }
                                    Log.w(TAG, "Stream request params to $providerId: $sentParams")
                                } catch (_: Exception) {}
                            } else {
                                Log.w(TAG, "Stream upstream error HTTP ${response.code}: ${errorBody.take(200)}")
                            }
                            StatsManager.recordCall(modelId, false)
                            RouterState.updateModelError(modelId, "HTTP ${response.code}")
                            if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                            switchModel = true
                            break
                        }
                        val responseBody = response.body
                        if (responseBody == null) {
                            return jsonError("api_error", "Empty stream response from upstream")
                        }
                        val bodyStream = responseBody.byteStream()
                        StatsManager.recordCall(modelId, true)
                        modelAcquired = false
                        val ttftInputStream = TTFTInputStream(bodyStream, modelId)
                        return newChunkedResponse(Response.Status.OK, "text/event-stream", ttftInputStream)
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Stream timeout on model $modelId, switching to next model")
                        try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                        RouterState.updateModelError(modelId, "超时")
                        if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                        switchModel = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Stream connection error on model $modelId, switching to next model")
                        try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                        RouterState.updateModelError(modelId, "连接失败")
                        if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                        switchModel = true
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream outer error", e)
                return jsonError("api_error", e.message ?: "unknown")
            } finally {
                if (modelAcquired) RouterState.releaseModel(modelId)
            }

            if (switchModel) continue
        }

        return jsonError("api_error", "No model available after early rate limit")
    }

    private fun handleAnthropicMessages(body: String): Response {
        return try {
            val jsonBody = JsonParser.parseString(body).asJsonObject
            val isStream = jsonBody.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val model = jsonBody.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: "unknown"

            val chatBody = ProtocolConverter.anthropicToOpenAIRequest(jsonBody)

            if (isStream) {
                handleAnthropicStream(chatBody, model, jsonBody)
            } else {
                handleAnthropicNonStream(chatBody, model)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Anthropic error", e)
            anthropicError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleAnthropicNonStream(chatBody: JsonObject, model: String): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()

        for (modelRetry in 0..2) {
            var modelId: String? = null
            var modelAcquired = false
            try {
                modelId = if (modelRetry == 0) getModelToUse(chatBody, null)
                          else configManager.selectFastestModel(group)
                if (modelId == null) return anthropicError("api_error", "No model available")
                if (modelId in triedModelIds) return anthropicError("api_error", "No alternative model available")
                triedModelIds.add(modelId)

                RouterState.acquireModel(modelId)
                modelAcquired = true
                chatBody.addProperty("model", modelId)
                val providerId = getProviderIdForModel(modelId)
                val apiKey = providerManager.getNextKey(providerId)
                if (apiKey.isEmpty()) return anthropicError("authentication_error", "No API key available for provider $providerId")

                val sanitizedChatBody = sanitizeRequestBody(chatBody)
                val chatResponse = forwardToProvider(sanitizedChatBody.toString(), apiKey, providerId)

                var isEarlyRateLimit = false
                try {
                    val checkParsed = JsonParser.parseString(chatResponse).asJsonObject
                    val checkError = checkParsed.get("error")
                    if (checkError != null && checkError.isJsonObject) {
                        val errorType = checkError.asJsonObject.get("type")?.asString
                        if (errorType == "early_rate_limit") isEarlyRateLimit = true
                    }
                } catch (_: Exception) {}

                if (isEarlyRateLimit) {
                    RouterState.updateModelError(modelId, "429 限流")
                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                    Log.w(TAG, "Anthropic non-stream early 429 on model $modelId, switching model")
                    continue
                }

                val responseObj = try {
                    JsonParser.parseString(chatResponse).asJsonObject
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse upstream response as JSON", e)
                    return anthropicError("api_error", "Invalid response from upstream: not valid JSON")
                }

                if (responseObj.has("error")) {
                    StatsManager.recordCall(modelId, false)
                    val errorMsg = responseObj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.asString ?: "unknown"
                    RouterState.updateModelError(modelId, "上游错误: $errorMsg")
                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                    Log.w(TAG, "Anthropic non-stream upstream error on model $modelId, switching model")
                    continue
                }

                val choices = responseObj.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                if (choices == null || choices.size() == 0) {
                    StatsManager.recordCall(modelId, false)
                    return anthropicError("api_error", "No choices in upstream response")
                }

                val anthropicResponse = ProtocolConverter.openAIToAnthropicResponse(responseObj, model)
                StatsManager.recordCall(modelId, true)
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(anthropicResponse))
            } catch (e: Exception) {
                Log.w(TAG, "Anthropic non-stream error on model $modelId, switching to next model")
                try { modelId?.let { StatsManager.recordCall(it, false) } } catch (_: Exception) {}
                if (modelId != null) {
                    RouterState.updateModelError(modelId, "异常: ${e.message}")
                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                }
                continue
            } finally {
                if (modelAcquired) modelId?.let { RouterState.releaseModel(it) }
            }
        }
        return anthropicError("api_error", "No model available after early rate limit")
    }

    private fun handleAnthropicStream(chatBody: JsonObject, model: String, @Suppress("UNUSED_PARAMETER") originalBody: JsonObject): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()

        for (modelRetry in 0..2) {
            val modelId = if (modelRetry == 0) getModelToUse(chatBody, null)
                          else configManager.selectFastestModel(group)
            if (modelId == null) return anthropicError("api_error", "No model available")
            if (modelId in triedModelIds) return anthropicError("api_error", "No alternative model available")
            triedModelIds.add(modelId)

            var modelAcquired = false
            var switchModel = false
            try {
                RouterState.acquireModel(modelId)
                modelAcquired = true
                chatBody.addProperty("model", modelId)
                chatBody.addProperty("stream", true)

                val providerId = getProviderIdForModel(modelId)
                val apiKey = providerManager.getNextKey(providerId)
                if (apiKey.isEmpty()) {
                    return anthropicError("authentication_error", "No API key available for provider $providerId")
                }

                val baseUrl = providerManager.getProvider(providerId)?.baseUrl ?: Constants.DEFAULT_BASE_URL

                var currentApiKey = apiKey
                var attempt = 0
                val maxRetries = 2
                var totalSleepMs = 0L
                val maxTotalSleepMs = 5000L

                while (true) {
                    attempt++
                    val bodyStr = sanitizeRequestBody(chatBody).toString()
                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/chat/completions")
                        .header("Authorization", "Bearer $currentApiKey")
                        .header("Content-Type", "application/json")
                        .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    try {
                        val response = createStreamClientForModel(modelId).newCall(request).execute()
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            if (response.code == 429) {
                                if (providerManager.isEarly429(providerId, currentApiKey)) {
                                    RouterState.updateModelError(modelId, "429 限流")
                                    if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                                    Log.w(TAG, "Anthropic stream early 429 on model $modelId, switching model")
                                    switchModel = true
                                    break
                                }
                                if (attempt <= maxRetries) {
                                    val newKey = providerManager.peekNextKey(providerId, currentApiKey)
                                    if (newKey.isNotEmpty() && newKey != currentApiKey) {
                                        currentApiKey = newKey
                                        Log.w(TAG, "Anthropic stream 429, retry $attempt with different key")
                                        continue
                                    }
                                    val delay = (500L * attempt)
                                    if (totalSleepMs + delay > maxTotalSleepMs) {
                                        Log.w(TAG, "Anthropic stream 429, total sleep limit reached, giving up")
                                        break
                                    }
                                    totalSleepMs += delay
                                    Log.w(TAG, "Anthropic stream 429, retry $attempt after ${delay}ms (same key)")
                                    Thread.sleep(delay)
                                    continue
                                }
                            }
                            if (response.code == 400) {
                                Log.w(TAG, "Anthropic stream 400 from $providerId: ${errorBody.take(300)}")
                                try {
                                    val reqBody = JsonParser.parseString(bodyStr).asJsonObject
                                    val sentParams = reqBody.keySet().filter { it != "messages" }
                                    Log.w(TAG, "Anthropic stream params to $providerId: $sentParams")
                                } catch (_: Exception) {}
                            } else {
                                Log.w(TAG, "Anthropic stream upstream error HTTP ${response.code}: ${errorBody.take(200)}")
                            }
                            StatsManager.recordCall(modelId, false)
                            RouterState.updateModelError(modelId, "HTTP ${response.code}")
                            if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                            switchModel = true
                            break
                        }

                        val responseBody = response.body
                        if (responseBody == null) {
                            return anthropicError("api_error", "Empty stream response from upstream")
                        }
                        val inputStream = responseBody.byteStream()

                        StatsManager.recordCall(modelId, true)
                        modelAcquired = false

                        val messageId = "msg_${System.currentTimeMillis()}"
                        val pipeIn = PipedInputStream(65536)
                        val pipeOut = PipedOutputStream(pipeIn)

                        try {
                            streamPipeExecutor.execute {
                                try {
                                    writeAnthropicStreamEvents(messageId, model, inputStream, pipeOut)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Anthropic stream pipe error", e)
                                } finally {
                                    try { pipeOut.close() } catch (_: Exception) {}
                                    try { inputStream.close() } catch (_: Exception) {}
                                    try { responseBody.close() } catch (_: Exception) {}
                                    RouterState.releaseModel(modelId)
                                }
                            }
                        } catch (e: java.util.concurrent.RejectedExecutionException) {
                            Log.e(TAG, "Executor overloaded, rejecting stream pipe task", e)
                            try { pipeOut.close() } catch (_: Exception) {}
                            try { pipeIn.close() } catch (_: Exception) {}
                            try { inputStream.close() } catch (_: Exception) {}
                            try { responseBody.close() } catch (_: Exception) {}
                            RouterState.releaseModel(modelId)
                            modelAcquired = false
                            return anthropicError("overloaded_error", "Server under heavy load, please retry")
                        }

                        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Anthropic stream timeout on model $modelId, switching to next model")
                        try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                        RouterState.updateModelError(modelId, "超时")
                        if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                        switchModel = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Anthropic stream connection error on model $modelId, switching to next model")
                        try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                        RouterState.updateModelError(modelId, "连接失败")
                        if (RouterState.getLockedModel(group) == modelId) RouterState.unlockGroup(group)
                        switchModel = true
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Anthropic stream outer error", e)
                return anthropicError("api_error", e.message ?: "unknown")
            } finally {
                if (modelAcquired) RouterState.releaseModel(modelId)
            }

            if (switchModel) continue
        }

        return anthropicError("api_error", "No model available after early rate limit")
    }

    private fun writeAnthropicStreamEvents(messageId: String, model: String, inputStream: InputStream, outputStream: PipedOutputStream) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)

        val msgStartData = JsonObject().apply {
            addProperty("type", "message_start")
            add("message", JsonObject().apply {
                addProperty("id", messageId)
                addProperty("type", "message")
                addProperty("role", "assistant")
                add("content", JsonArray())
                addProperty("model", model)
                add("stop_reason", JsonNull.INSTANCE)
                add("stop_sequence", JsonNull.INSTANCE)
                add("usage", JsonObject().apply {
                    addProperty("input_tokens", 0)
                    addProperty("output_tokens", 0)
                })
            })
        }
        writer.write("event: message_start\ndata: ${gson.toJson(msgStartData)}\n\n")
        writer.write("event: ping\ndata: {\"type\":\"ping\"}\n\n")
        writer.flush()

        try {
            val reader = inputStream.bufferedReader()
            var line: String?
            var outputTokens = 0
            var inputTokens = 0
            val toolCallBlocks = mutableMapOf<Int, ToolCallState>()
            val toolCallIndexMap = mutableMapOf<Int, Int>()
            var currentToolIndex = 0
            var hasToolCalls = false
            var textBlockStarted = false
            var thinkingBlockStarted = false
            var lastFinishReason: String? = null
            var contentBlockIndex = 0
            var textBlockIndex = -1
            var thinkingBlockIndex = -1

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val jsonStr = l.substring(6).trim()
                if (jsonStr == "[DONE]") continue

                try {
                    val chunk = JsonParser.parseString(jsonStr).asJsonObject
                    val usage = chunk.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (usage != null) {
                        inputTokens = usage.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: inputTokens
                    }
                    val choices = chunk.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                    if (choices != null && choices.size() > 0) {
                        val choiceObj = choices[0].asJsonObject
                        val finishReason = choiceObj.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
                        if (finishReason != null && finishReason != "null") {
                            lastFinishReason = finishReason
                        }
                        val delta = choiceObj.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue

                        val reasoningContent = delta.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        val content = delta.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        val toolCallsDelta = delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                        if (reasoningContent.isNotEmpty() || content.isNotEmpty() || toolCallsDelta != null) {
                            Log.d(TAG, "Stream delta: reasoning=${reasoningContent.take(30)}, content=${content.take(30)}, tools=${toolCallsDelta != null}")
                        }

                        if (reasoningContent.isNotEmpty()) {
                            if (!thinkingBlockStarted) {
                                if (textBlockStarted) {
                                    val textBlockStopData = JsonObject().apply {
                                        addProperty("type", "content_block_stop")
                                        addProperty("index", textBlockIndex)
                                    }
                                    writer.write("event: content_block_stop\ndata: ${gson.toJson(textBlockStopData)}\n\n")
                                    textBlockStarted = false
                                }
                                thinkingBlockIndex = contentBlockIndex
                                contentBlockIndex++
                                thinkingBlockStarted = true
                                val blockStartData = JsonObject().apply {
                                    addProperty("type", "content_block_start")
                                    addProperty("index", thinkingBlockIndex)
                                    add("content_block", JsonObject().apply {
                                        addProperty("type", "thinking")
                                        addProperty("thinking", "")
                                    })
                                }
                                writer.write("event: content_block_start\ndata: ${gson.toJson(blockStartData)}\n\n")
                            }
                            outputTokens++
                            val blockDeltaData = JsonObject().apply {
                                addProperty("type", "content_block_delta")
                                addProperty("index", thinkingBlockIndex)
                                add("delta", JsonObject().apply {
                                    addProperty("type", "thinking_delta")
                                    addProperty("thinking", reasoningContent)
                                })
                            }
                            writer.write("event: content_block_delta\ndata: ${gson.toJson(blockDeltaData)}\n\n")
                            writer.flush()
                        }

                        if (content.isNotEmpty()) {
                            if (thinkingBlockStarted) {
                                val thinkingBlockStopData = JsonObject().apply {
                                    addProperty("type", "content_block_stop")
                                    addProperty("index", thinkingBlockIndex)
                                }
                                writer.write("event: content_block_stop\ndata: ${gson.toJson(thinkingBlockStopData)}\n\n")
                                thinkingBlockStarted = false
                            }
                            val cleaned = ProtocolConverter.cleanToolCallTags(content)
                            if (cleaned.isNotEmpty()) {
                                if (!textBlockStarted) {
                                    textBlockIndex = contentBlockIndex
                                    contentBlockIndex++
                                    textBlockStarted = true
                                    val blockStartData = JsonObject().apply {
                                        addProperty("type", "content_block_start")
                                        addProperty("index", textBlockIndex)
                                        add("content_block", JsonObject().apply {
                                            addProperty("type", "text")
                                            addProperty("text", "")
                                        })
                                    }
                                    writer.write("event: content_block_start\ndata: ${gson.toJson(blockStartData)}\n\n")
                                }
                                outputTokens++
                                val blockDeltaData = JsonObject().apply {
                                    addProperty("type", "content_block_delta")
                                    addProperty("index", textBlockIndex)
                                    add("delta", JsonObject().apply {
                                        addProperty("type", "text_delta")
                                        addProperty("text", cleaned)
                                    })
                                }
                                writer.write("event: content_block_delta\ndata: ${gson.toJson(blockDeltaData)}\n\n")
                                writer.flush()
                            }
                        }

                        if (toolCallsDelta != null) {
                            for (tcDelta in toolCallsDelta) {
                                if (!tcDelta.isJsonObject) continue
                                val td = tcDelta.asJsonObject
                                hasToolCalls = true
                                val tcIndex = td.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                                val tcId = td.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                                val func = td.get("function")?.takeIf { it.isJsonObject }?.asJsonObject
                                val tcName = func?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                                val tcArgs = func?.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString ?: ""

                                if (tcIndex !in toolCallBlocks) {
                                    if (textBlockStarted) {
                                        val textBlockStopData = JsonObject().apply {
                                            addProperty("type", "content_block_stop")
                                            addProperty("index", textBlockIndex)
                                        }
                                        writer.write("event: content_block_stop\ndata: ${gson.toJson(textBlockStopData)}\n\n")
                                        textBlockStarted = false
                                    }

                                    val blockIndex = contentBlockIndex
                                    contentBlockIndex++
                                    toolCallBlocks[tcIndex] = ToolCallState(tcId, tcName)
                                    toolCallIndexMap[tcIndex] = blockIndex
                                    currentToolIndex = blockIndex
                                    val toolBlockStartData = JsonObject().apply {
                                        addProperty("type", "content_block_start")
                                        addProperty("index", blockIndex)
                                        add("content_block", JsonObject().apply {
                                            addProperty("type", "tool_use")
                                            addProperty("id", tcId)
                                            addProperty("name", tcName)
                                            add("input", JsonObject())
                                        })
                                    }
                                    writer.write("event: content_block_start\ndata: ${gson.toJson(toolBlockStartData)}\n\n")

                                    val initDeltaData = JsonObject().apply {
                                        addProperty("type", "content_block_delta")
                                        addProperty("index", blockIndex)
                                        add("delta", JsonObject().apply {
                                            addProperty("type", "input_json_delta")
                                            addProperty("partial_json", "")
                                        })
                                    }
                                    writer.write("event: content_block_delta\ndata: ${gson.toJson(initDeltaData)}\n\n")
                                    writer.flush()
                                }

                                if (tcId.isNotEmpty() && toolCallBlocks[tcIndex]?.id?.isEmpty() == true) {
                                    toolCallBlocks[tcIndex]?.id = tcId
                                }
                                if (tcName.isNotEmpty() && toolCallBlocks[tcIndex]?.name?.isEmpty() == true) {
                                    toolCallBlocks[tcIndex]?.name = tcName
                                }

                                if (tcArgs.isNotEmpty()) {
                                    val blockIndex = toolCallIndexMap[tcIndex] ?: currentToolIndex
                                    val argsDeltaData = JsonObject().apply {
                                        addProperty("type", "content_block_delta")
                                        addProperty("index", blockIndex)
                                        add("delta", JsonObject().apply {
                                            addProperty("type", "input_json_delta")
                                            addProperty("partial_json", tcArgs)
                                        })
                                    }
                                    writer.write("event: content_block_delta\ndata: ${gson.toJson(argsDeltaData)}\n\n")
                                    writer.flush()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Stream chunk parse error: ${e.message}")
                }
            }
            reader.close()

            if (thinkingBlockStarted) {
                val thinkingBlockStopData = JsonObject().apply {
                    addProperty("type", "content_block_stop")
                    addProperty("index", thinkingBlockIndex)
                }
                writer.write("event: content_block_stop\ndata: ${gson.toJson(thinkingBlockStopData)}\n\n")
            }
            if (textBlockStarted) {
                val textBlockStopData = JsonObject().apply {
                    addProperty("type", "content_block_stop")
                    addProperty("index", textBlockIndex)
                }
                writer.write("event: content_block_stop\ndata: ${gson.toJson(textBlockStopData)}\n\n")
            }
            for ((_, idx) in toolCallIndexMap) {
                val toolBlockStopData = JsonObject().apply {
                    addProperty("type", "content_block_stop")
                    addProperty("index", idx)
                }
                writer.write("event: content_block_stop\ndata: ${gson.toJson(toolBlockStopData)}\n\n")
            }

            val stopReason = ProtocolConverter.mapFinishReasonToStopReason(lastFinishReason ?: "stop", hasToolCalls)
            val msgDeltaData = JsonObject().apply {
                addProperty("type", "message_delta")
                add("delta", JsonObject().apply {
                    addProperty("stop_reason", stopReason)
                    add("stop_sequence", JsonNull.INSTANCE)
                })
                add("usage", JsonObject().apply {
                    addProperty("output_tokens", outputTokens)
                })
            }
            writer.write("event: message_delta\ndata: ${gson.toJson(msgDeltaData)}\n\n")
            writer.write("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
            writer.flush()

        } catch (e: Exception) {
            Log.e(TAG, "Stream write error", e)
            try {
                val errMsg = (e.message ?: "stream error").replace("\\", "\\\\").replace("\"", "\\\"")
                writer.write("event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"$errMsg\",\"type\":\"stream_error\"}}\n\n")
                writer.flush()
            } catch (_: Exception) {}
        }
    }

    private fun getModelToUse(@Suppress("UNUSED_PARAMETER") jsonBody: JsonObject, groupName: String?): String? {
        val group = groupName ?: configManager.getGroupByPort(listeningPort)
        val lockedModel = RouterState.getLockedModel(group)
        if (lockedModel != null) return lockedModel
        return configManager.selectFastestModel(group)
    }

    private val modelsClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun handleModels(): Response {
        return try {
            val enabledProviders = providerManager.getEnabledProviders()
            if (enabledProviders.isEmpty()) {
                return jsonError("upstream_error", "No enabled providers")
            }

            val allModels = java.util.concurrent.ConcurrentLinkedQueue<Map<String, Any>>()
            val latch = java.util.concurrent.CountDownLatch(enabledProviders.size)
            val fetchErrors = java.util.concurrent.atomic.AtomicInteger(0)

            for (provider in enabledProviders) {
                val apiKey = if (provider.apiKeys.isNotEmpty()) provider.apiKeys.first() else ""
                if (apiKey.isEmpty()) {
                    latch.countDown()
                    fetchErrors.incrementAndGet()
                    continue
                }

                executor.execute {
                    try {
                        val request = Request.Builder()
                            .url("${provider.baseUrl.trimEnd('/')}/models")
                            .header("Authorization", "Bearer $apiKey")
                            .get()
                            .build()

                        val response = modelsClient.newCall(request).execute()
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            try {
                                val json = JsonParser.parseString(body).asJsonObject
                                val dataArray = json.get("data")?.asJsonArray
                                if (dataArray != null) {
                                    for (item in dataArray) {
                                        val obj = item.asJsonObject
                                        val id = obj.get("id")?.asString ?: continue
                                        allModels.add(mapOf(
                                            "id" to id,
                                            "object" to "model",
                                            "owned_by" to (obj.get("owned_by")?.asString ?: provider.name),
                                            "provider" to provider.id,
                                            "provider_name" to provider.name
                                        ))
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch models from provider ${provider.id}: ${e.message}")
                        fetchErrors.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(15, TimeUnit.SECONDS)

            if (allModels.isEmpty()) {
                return jsonError("upstream_error", "No models available from any provider")
            }

            val result = mapOf(
                "object" to "list",
                "data" to allModels.toList()
            )
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(result))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching models", e)
            jsonError("connection_error", "Failed to fetch models: ${e.message}")
        }
    }

    private fun handleSpeedTest(body: String): Response {
        return try {
            val jsonBody = JsonParser.parseString(body).asJsonObject
            val modelId = jsonBody.get("model")?.asString
                ?: return jsonError("bad_request", "Model ID required")

            speedTestScope.launch {
                val providerId = getProviderIdForModel(modelId)
                val result = speedTester.testModel(modelId, providerId)
                if (result.success) {
                    RouterState.updateSpeedTestResult(modelId, result.responseTime)
                } else {
                    RouterState.updateModelError(modelId, result.error ?: "失败")
                }
            }

            jsonOk(mapOf("status" to "started", "model" to modelId))
        } catch (e: Exception) {
            jsonError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleLock(body: String): Response {
        return try {
            val jsonBody = JsonParser.parseString(body).asJsonObject
            val modelId = jsonBody.get("model")?.asString
                ?: return jsonError("bad_request", "Model ID required")
            val groupName = jsonBody.get("group")?.asString ?: configManager.getGroupByPort(listeningPort)

            RouterState.lockModel(groupName, modelId)
            jsonOk(mapOf("locked" to true, "model" to modelId, "group" to groupName))
        } catch (e: Exception) {
            jsonError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleUnlock(): Response {
        val groupName = configManager.getGroupByPort(listeningPort)
        RouterState.unlockGroup(groupName)
        return jsonOk(mapOf("locked" to false, "group" to groupName))
    }

    private fun handleStatus(): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val lockedModel = RouterState.getLockedModel(group)
        return jsonOk(mapOf(
            "status" to "running",
            "locked" to (lockedModel != null),
            "locked_model" to lockedModel,
            "group" to group,
            "port" to listeningPort
        ))
    }

    private fun handleHealth(): Response {
        return jsonOk(mapOf("status" to "healthy", "timestamp" to System.currentTimeMillis()))
    }

    private fun handleGetConfig(): Response {
        return jsonOk(configManager.getConfig())
    }

    private fun handleGetStats(): Response {
        return jsonOk(StatsManager.getStats())
    }

    private fun handleDashboardApi(): Response {
        val groups = configManager.getAllGroups()
        val lockedModels = RouterState.getLockedModels()
        val speedResults = RouterState.getSpeedTestResults()
        val availability = RouterState.getModelAvailability()
        val modelStats = StatsManager.getModelStats()
        val groupsData = groups.map { g ->
            val lockedModel = lockedModels[g.name]
            val currentModel = lockedModel ?: configManager.selectFastestModel(g.name)
            mapOf(
                "name" to g.name,
                "port" to g.port,
                "enabled" to g.enabled,
                "models" to g.models.filter { it.enabled }.map { m ->
                    val rt = speedResults[m.id]
                    val isAvailable = availability[m.id] ?: true
                    val providerInfo = providerManager.getProvider(m.providerId)
                    mapOf(
                        "id" to m.id,
                        "name" to m.name,
                        "provider" to m.providerId,
                        "provider_name" to (providerInfo?.name ?: m.providerId),
                        "status" to mapOf(
                            "is_healthy" to isAvailable,
                            "avg_response_time" to rt,
                            "total_requests" to (modelStats[m.id] ?: 0)
                        )
                    )
                },
                "current_model" to (currentModel ?: ""),
                "locked_model" to (lockedModel ?: "")
            )
        }

        val groupStatsMap = mutableMapOf<String, Map<String, Int>>()
        for (g in groups) {
            val stats = g.models.filter { it.enabled }.associate { m ->
                m.id to (modelStats[m.id] ?: 0)
            }
            groupStatsMap[g.name] = stats
        }

        val lockStatusList = lockedModels.entries.map { (group, modelId) ->
            mapOf("group" to group, "model_id" to modelId, "locked" to true)
        }

        val providersData = providerManager.getAllProviders().map { p ->
            mapOf(
                "id" to p.id,
                "name" to p.name,
                "base_url" to p.baseUrl,
                "rate_limit_type" to p.rateLimitType.name,
                "rate_limit_value" to p.rateLimitValue,
                "api_key_count" to p.apiKeys.size,
                "model_count" to p.models.size,
                "enabled" to p.enabled,
                "request_counts" to providerManager.getRequestCounts(p.id)
            )
        }

        return jsonOk(mapOf(
            "groups" to groupsData,
            "api_call_stats" to mapOf(
                "total_calls" to StatsManager.getTotalCalls(),
                "total_errors" to StatsManager.getTotalErrors(),
                "group_stats" to groupStatsMap
            ),
            "lock_status" to mapOf(
                "locked_models" to lockedModels,
                "locks" to lockStatusList
            ),
            "group_current_model" to groups.associate { g ->
                g.name to (lockedModels[g.name] ?: configManager.selectFastestModel(g.name) ?: "")
            },
            "providers" to providersData
        ))
    }

    private fun handleReload(): Response {
        configManager.reload()
        keyManager.reloadKeys()
        providerManager.reload()
        RouterState.unlockAll()
        return jsonOk(mapOf("success" to true, "message" to "配置重载成功"))
    }

    private fun handleLockModelApi(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            val jsonBody = JsonParser.parseString(body).asJsonObject
            val group = jsonBody.get("group")?.asString ?: configManager.getGroupByPort(listeningPort)
            val modelId = jsonBody.get("model_id")?.asString ?: ""

            if (modelId.isEmpty()) return jsonError("bad_request", "model_id required")

            RouterState.lockModel(group, modelId)
            jsonOk(mapOf("success" to true, "locked" to mapOf("locked" to true, "group" to group, "model_id" to modelId)))
        } catch (e: Exception) {
            jsonError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleUnlockModelApi(): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val old = RouterState.getLockedModel(group)
        RouterState.unlockGroup(group)
        return jsonOk(mapOf("success" to true, "unlocked" to old, "group" to group))
    }

    private fun handleLockStatusApi(): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val lockedModel = RouterState.getLockedModel(group)
        return jsonOk(mapOf("locked" to (lockedModel != null), "model_id" to (lockedModel ?: ""), "group" to group))
    }

    private fun getProviderIdForModel(modelId: String): String {
        val groups = configManager.getAllGroups()
        for (group in groups) {
            val model = group.models.find { it.id == modelId }
            if (model != null) {
                return model.providerId
            }
        }
        return providerManager.getProviderIdForModel(modelId)
    }

    private fun forwardToProvider(body: String, apiKey: String, providerId: String): String {
        val provider = providerManager.getProvider(providerId)
        val baseUrl = provider?.baseUrl ?: Constants.DEFAULT_BASE_URL

        if (apiKey.isEmpty()) {
            return gson.toJson(mapOf(
                "error" to mapOf("message" to "No API key available", "type" to "auth_error")
            ))
        }

        val maxRetries = 3
        var attempt = 0
        var currentApiKey = apiKey
        var totalSleepMs = 0L
        val maxTotalSleepMs = 8000L

        while (true) {
            attempt++
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = body.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $currentApiKey")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.code == 429) {
                    if (providerManager.isEarly429(providerId, currentApiKey)) {
                        Log.w(TAG, "Early 429 (key count < limit), model-level rate limit")
                        return gson.toJson(mapOf(
                            "error" to mapOf("message" to "Model rate limited (early 429)", "type" to "early_rate_limit")
                        ))
                    }
                    if (attempt <= maxRetries) {
                        val newKey = providerManager.peekNextKey(providerId, currentApiKey)
                        if (newKey.isNotEmpty() && newKey != currentApiKey) {
                            currentApiKey = newKey
                            Log.w(TAG, "Rate limited (429), retry $attempt/$maxRetries with different key")
                        } else {
                            val baseDelay = (500L * (1L shl (attempt - 1)))
                            val jitter = (Math.random() * baseDelay * 0.5).toLong()
                            val delay = baseDelay + jitter
                            if (totalSleepMs + delay > maxTotalSleepMs) {
                                Log.w(TAG, "Rate limited (429), total sleep limit reached, giving up")
                                return gson.toJson(mapOf(
                                    "error" to mapOf("message" to "Rate limited after retries", "type" to "rate_limit_error")
                                ))
                            }
                            totalSleepMs += delay
                            Log.w(TAG, "Rate limited (429), retry $attempt/$maxRetries after ${delay}ms (same key)")
                            Thread.sleep(delay)
                        }
                        continue
                    }
                    return gson.toJson(mapOf(
                        "error" to mapOf("message" to "Rate limited after $maxRetries retries", "type" to "rate_limit_error")
                    ))
                }

                if (responseBody.isNullOrEmpty()) {
                    return gson.toJson(mapOf(
                        "error" to mapOf("message" to "Empty response from upstream (HTTP ${response.code})", "type" to "upstream_error")
                    ))
                }

                if (!response.isSuccessful) {
                    if (response.code == 400) {
                        Log.w(TAG, "Upstream 400 error from $providerId: ${responseBody.take(300)}")
                        try {
                            val reqBody = JsonParser.parseString(body).asJsonObject
                            val sentParams = reqBody.keySet().filter { it != "messages" }
                            Log.w(TAG, "Request params sent to $providerId: $sentParams")
                        } catch (_: Exception) {}
                    } else {
                        Log.w(TAG, "Upstream error HTTP ${response.code}: ${responseBody.take(200)}")
                    }
                    try {
                        JsonParser.parseString(responseBody)
                        return responseBody
                    } catch (_: Exception) {}
                    return gson.toJson(mapOf(
                        "error" to mapOf("message" to "Upstream error HTTP ${response.code}: ${responseBody.take(100)}", "type" to "upstream_error")
                    ))
                }

                return responseBody
            } catch (e: Exception) {
                if (attempt <= maxRetries && e is java.net.SocketTimeoutException) {
                    val baseDelay = (500L * (1L shl (attempt - 1)))
                    val jitter = (Math.random() * baseDelay * 0.5).toLong()
                    val delay = baseDelay + jitter
                    if (totalSleepMs + delay > maxTotalSleepMs) {
                        Log.w(TAG, "Timeout retry, total sleep limit reached, giving up")
                        return gson.toJson(mapOf(
                            "error" to mapOf("message" to "Connection timeout after retries", "type" to "timeout_error")
                        ))
                    }
                    totalSleepMs += delay
                    Log.w(TAG, "Timeout on attempt $attempt/$maxRetries, retrying after ${delay}ms")
                    Thread.sleep(delay)
                    continue
                }
                Log.e(TAG, "Forward to provider $providerId failed", e)
                return gson.toJson(mapOf(
                    "error" to mapOf("message" to "Connection failed: ${e.message}", "type" to "connection_error")
                ))
            }
        }
    }

    private val OPENAI_STANDARD_PARAMS = setOf(
        "model", "messages", "max_tokens", "max_completion_tokens",
        "temperature", "top_p", "n", "stream", "stream_options",
        "stop", "presence_penalty", "frequency_penalty",
        "logit_bias", "logprobs", "top_logprobs",
        "user", "tools", "tool_choice", "parallel_tool_calls",
        "response_format", "seed", "service_tier",
        "chat_template_kwargs", "repetition_penalty"
    )

    private fun sanitizeRequestBody(body: JsonObject): JsonObject {
        val keysToRemove = body.keySet().filter { it !in OPENAI_STANDARD_PARAMS }
        if (keysToRemove.isEmpty()) return body
        val cleaned = body.deepCopy() ?: body
        for (key in keysToRemove) {
            Log.d(TAG, "Sanitizing non-standard param: $key")
            cleaned.remove(key)
        }
        return cleaned
    }

    private fun cleanToolCallTags(text: String): String {
        return ProtocolConverter.cleanToolCallTags(text)
    }

    private fun buildJsonMsg(role: String, content: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }

    private fun buildJsonMsg(role: String, toolCallId: String, content: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("tool_call_id", toolCallId)
            addProperty("content", content)
        }
    }

    private fun readBody(session: IHTTPSession): String {
        try {
            val contentLength = session.headers.get("content-length")?.toLongOrNull() ?: -1L
            if (contentLength == 0L) return "{}"

            if (contentLength > 0 && contentLength <= 2 * 1024 * 1024) {
                val bytes = ByteArray(contentLength.toInt())
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = session.inputStream.read(bytes, totalRead, (contentLength - totalRead).toInt())
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead == contentLength.toInt()) {
                    return String(bytes, Charsets.UTF_8)
                }
                if (totalRead > 0) {
                    return String(bytes, 0, totalRead, Charsets.UTF_8)
                }
            }

            val files = HashMap<String, String>()
            session.parseBody(files)
            return files["postData"] ?: "{}"
        } catch (e: Exception) {
            Log.e(TAG, "Error reading body", e)
            return "{}"
        }
    }

    private fun jsonOk(data: Any): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(data))
    }

    private fun jsonError(type: String, message: String): Response {
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
            gson.toJson(mapOf("error" to mapOf("message" to message, "type" to type))))
    }

    /**
     * Anthropic 格式错误响应
     * 格式: {"type": "error", "error": {"type": "...", "message": "..."}}
     * HTTP 状态码根据错误类型映射
     */
    private fun anthropicError(type: String, message: String): Response {
        val status = when (type) {
            "authentication_error" -> Response.Status.UNAUTHORIZED
            "permission_error" -> Response.Status.FORBIDDEN
            "not_found_error" -> Response.Status.NOT_FOUND
            "rate_limit_error" -> Response.Status.INTERNAL_ERROR
            "invalid_request_error" -> Response.Status.BAD_REQUEST
            "overloaded_error" -> Response.Status.SERVICE_UNAVAILABLE
            "timeout_error" -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json",
            gson.toJson(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to message))))
    }

    /**
     * 将内部错误类型映射为 Anthropic 错误类型
     */
    private fun mapToAnthropicErrorType(internalType: String): String {
        return when (internalType) {
            "auth_error" -> "authentication_error"
            "upstream_error" -> "api_error"
            "connection_error" -> "timeout_error"
            "server_overloaded" -> "overloaded_error"
            "bad_request" -> "invalid_request_error"
            else -> "api_error"
        }
    }

    private data class ToolCallState(var id: String, var name: String)

    private class TTFTInputStream(
        private val delegate: InputStream,
        private val modelId: String
    ) : InputStream() {
        private var closed = false
        private var firstByteReceived = false
        private val startTime = System.currentTimeMillis()

        private fun recordTTFTIfNeeded() {
            if (!firstByteReceived) {
                firstByteReceived = true
                val ttft = System.currentTimeMillis() - startTime
                RouterState.updateSpeedTestResult(modelId, ttft)
            }
        }

        override fun read(): Int {
            return try {
                val b = delegate.read()
                if (b != -1) recordTTFTIfNeeded()
                if (b == -1) close()
                b
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return try {
                val n = delegate.read(b, off, len)
                if (n > 0) recordTTFTIfNeeded()
                if (n == -1) close()
                n
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try { delegate.close() } catch (_: Exception) {}
            RouterState.releaseModel(modelId)
        }

        override fun available(): Int = try { delegate.available() } catch (_: Exception) { 0 }

        protected fun finalize() {
            if (!closed) {
                closed = true
                try { delegate.close() } catch (_: Exception) {}
                RouterState.releaseModel(modelId)
            }
        }
    }
}
