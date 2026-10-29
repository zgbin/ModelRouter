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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        // 块间空闲容忍时间：模型推理中的思考停顿可能远超配置的 timeout，
        // TTFT（首字节）约束改由 ttftWatchdogExecutor 单独保证
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 非流式并行请求客户端缓存（按读超时复用，避免每请求 new 一个 OkHttpClient）
    private val parallelClientCache = ConcurrentHashMap<Long, OkHttpClient>()

    private fun getParallelClient(readTimeoutMs: Long): OkHttpClient {
        return parallelClientCache.getOrPut(readTimeoutMs) {
            OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * TTFT 看门狗：模型配置的 timeout 内未收到首字节则取消调用。
     * OkHttp 的 readTimeout 无法区分"首字节未到"与"块间思考停顿"，
     * 因此流式请求用宽松的块间超时，TTFT 由看门狗保证。
     */
    private val ttftWatchdogExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "ttft-watchdog").apply { isDaemon = true }
    }

    /**
     * 请求耐心值（首字节期限）：已知慢的模型（新鲜被动延迟 EWMA 高）放宽到 2 倍 EWMA。
     * 全线慢时避免把"慢但会来"的响应在最后关头取消，陷入取消-换模型的死循环；
     * 无延迟数据的模型仍按配置超时严格限制。
     */
    private fun modelPatienceMs(modelId: String): Long {
        val timeoutMs = configManager.getModelTimeout(modelId).coerceAtLeast(1) * 1000L
        val ewmaMs = RouterState.getRealLatency(modelId) ?: 0L
        return maxOf(timeoutMs, ewmaMs * 2)
    }

    /**
     * 建立流式调用并挂 TTFT 看门狗。
     * @param firstByteReceived 由流包装在收到首字节时置 true
     */
    private fun newStreamCall(request: Request, modelId: String, firstByteReceived: java.util.concurrent.atomic.AtomicBoolean): okhttp3.Call {
        val call = streamClient.newCall(request)
        val patienceMs = modelPatienceMs(modelId)
        ttftWatchdogExecutor.schedule({
            if (!firstByteReceived.get()) {
                Log.w(TAG, "TTFT watchdog: no first byte from $modelId within ${patienceMs}ms, cancelling call")
                call.cancel()
            }
        }, patienceMs, TimeUnit.MILLISECONDS)
        return call
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

    // 健康检测专用执行器（指数退避检测出错模型）
    private val healthCheckExecutor: ExecutorService = ThreadPoolExecutor(
        2,
        16,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(128),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    // 慢响应并行请求执行器
    private val parallelRequestExecutor: ExecutorService = ThreadPoolExecutor(
        4,
        32,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    companion object {
        private const val TAG = "ModelRouterServer"
        private const val SLOW_RESPONSE_TIMEOUT_MS = 30_000L
        private const val MAX_PARALLEL_ATTEMPTS = 5
        private const val GROUP_ALL_SLOW_MS = 10_000L
    }

    init {
        setAsyncRunner(object : AsyncRunner {
            override fun exec(code: NanoHTTPD.ClientHandler) {
                executor.execute(code)
            }

            override fun closed(code: NanoHTTPD.ClientHandler) {}

            override fun closeAll() {
                executor.shutdownNow()
                streamPipeExecutor.shutdownNow()
                healthCheckExecutor.shutdownNow()
                parallelRequestExecutor.shutdownNow()
            }
        })
    }

    private val keyManager = ApiKeyManager
    private val configManager = ConfigManager
    private val providerManager = ProviderManager
    private val speedTester = SpeedTester(client, keyManager)
    private val speedTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        try {
            healthCheckExecutor.shutdownNow()
        } catch (_: Exception) {}
        try {
            parallelRequestExecutor.shutdownNow()
        } catch (_: Exception) {}
        try {
            ttftWatchdogExecutor.shutdownNow()
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
                uri == "/api/lock_model" && method == Method.POST -> handleLockModelApi(requestBody ?: "{}")
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
        } catch (e: com.google.gson.JsonParseException) {
            jsonError("bad_request", "Invalid JSON body: ${e.message}")
        } catch (e: IllegalStateException) {
            jsonError("bad_request", "Invalid JSON body: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat completion", e)
            jsonError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleNonStreamCompletion(jsonBody: JsonObject, groupName: String?): Response {
        val group = groupName ?: configManager.getGroupByPort(listeningPort)

        // 首次获取模型，使用慢响应并行处理机制
        val firstModelId = getModelToUse(jsonBody, group)
        if (firstModelId == null) return jsonError("api_error", "No model available")

        // 使用慢响应并行处理：30秒未返回则切换下一个模型，最多5次
        return handleSlowResponseWithParallel(jsonBody, group, firstModelId)
    }

    private fun handleStreamCompletion(jsonBody: JsonObject, groupName: String?): Response {
        val group = groupName ?: configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()
        val requestStartTime = System.currentTimeMillis()
        // 换模型旋转总预算：最多等 2 倍首试模型的耐心值，避免全线慢/全灭时每个模型都卡满超时，
        // 客户端等数倍超时才收到错误
        var rotateDeadlineMs = Long.MAX_VALUE

        for (modelRetry in 0..2) {
            if (System.currentTimeMillis() > rotateDeadlineMs) {
                Log.w(TAG, "Stream rotation budget exhausted after ${System.currentTimeMillis() - requestStartTime}ms")
                return jsonError("timeout_error", "所有候选模型均响应缓慢或超时，请稍后重试")
            }
            val modelId = if (modelRetry == 0) getModelToUse(jsonBody, group)
                          else configManager.selectFastestModel(group, triedModelIds)
            if (modelId == null) return jsonError("api_error",
                if (modelRetry == 0) "No model available" else "No alternative model available")
            if (modelId in triedModelIds) return jsonError("api_error", "No alternative model available")
            triedModelIds.add(modelId)
            if (modelRetry == 0) rotateDeadlineMs = requestStartTime + 2 * modelPatienceMs(modelId)

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
                val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)

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

                    val requestStartMs = System.currentTimeMillis()
                    val call = newStreamCall(request, modelId, firstByteReceived)
                    try {
                        val response = call.execute()
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            if (response.code == 429) {
                                if (providerManager.isEarly429(providerId, currentApiKey)) {
                                    // Early 429：解锁分组 + 切换模型 + 启动健康检测
                                    Log.w(TAG, "Stream early 429 on model $modelId, unlocking and switching model")
                                    StatsManager.recordCall(modelId, false)
                                    handleModelErrorAndSwitch(modelId, group, "early_429", "429 限流")
                                    switchModel = true
                                    break
                                }
                                // 普通429：保持锁定 + 切换API KEY重试
                                if (attempt <= maxRetries) {
                                    val newKey = providerManager.reserveNextKey(providerId, currentApiKey)
                                    if (newKey.isNotEmpty() && newKey != currentApiKey) {
                                        currentApiKey = newKey
                                        Log.w(TAG, "Stream normal 429, keeping lock, retry $attempt with different key")
                                        continue
                                    }
                                    val delay = (500L * attempt)
                                    if (totalSleepMs + delay > maxTotalSleepMs) {
                                        Log.w(TAG, "Stream 429, total sleep limit reached, switching model")
                                        // 达到重试上限，解锁 + 切换模型 + 健康检测
                                        handleModelErrorAndSwitch(modelId, group, "429_limit", "429 限流重试耗尽")
                                        switchModel = true
                                        break
                                    }
                                    totalSleepMs += delay
                                    Log.w(TAG, "Stream 429, retry $attempt after ${delay}ms (same key)")
                                    Thread.sleep(delay)
                                    continue
                                }
                                // 重试次数用完，解锁 + 切换模型 + 健康检测
                                handleModelErrorAndSwitch(modelId, group, "429_limit", "429 限流重试耗尽")
                                switchModel = true
                                break
                            }
                            if (response.code == 400) {
                                // 400 是客户端请求错误（如不支持的参数/tool格式），不应下线模型
                                Log.w(TAG, "Stream 400 error from $providerId: ${errorBody.take(300)}")
                                try {
                                    val reqBody = JsonParser.parseString(bodyStr).asJsonObject
                                    val sentParams = reqBody.keySet().filter { it != "messages" }
                                    Log.w(TAG, "Stream request params to $providerId: $sentParams")
                                } catch (_: Exception) {}
                                StatsManager.recordCall(modelId, false)
                                // 直接返回错误给客户端，不切换模型
                                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", errorBody)
                            } else {
                                Log.w(TAG, "Stream upstream error HTTP ${response.code}: ${errorBody.take(200)}")
                                StatsManager.recordCall(modelId, false)
                                // 5xx/其他错误：解锁分组 + 切换模型 + 启动健康检测
                                handleModelErrorAndSwitch(modelId, group, "http_${response.code}", "HTTP ${response.code}")
                                switchModel = true
                                break
                            }
                        }
                        val responseBody = response.body
                        if (responseBody == null) {
                            // 空响应：解锁分组 + 切换模型 + 启动健康检测
                            StatsManager.recordCall(modelId, false)
                            handleModelErrorAndSwitch(modelId, group, "empty_response", "空响应")
                            switchModel = true
                            break
                        }
                        val bodyStream = responseBody.byteStream()
                        // 成功统计在流正常结束后记账（TTFTInputStream.onComplete），避免断流也算成功
                        modelAcquired = false
                        val ttftInputStream = TTFTInputStream(
                            delegate = bodyStream,
                            modelId = modelId,
                            firstByteReceived = firstByteReceived,
                            requestStartMs = requestStartMs,
                            errorPayloadBuilder = { e ->
                                // 上游中途断流：注入 error 事件 + [DONE]，客户端可感知失败而不是静默截断
                                buildOpenAiErrorSse(e)
                            },
                            onComplete = { success -> StatsManager.recordCall(modelId, success) }
                        )
                        return newChunkedResponse(Response.Status.OK, "text/event-stream", ttftInputStream)
                    } catch (e: Exception) {
                        // 看门狗取消（TTFT 超时）与 readTimeout 统一按超时处理
                        val isTimeout = e is java.net.SocketTimeoutException ||
                            e.message?.contains("cancel", ignoreCase = true) == true
                        if (isTimeout) {
                            Log.w(TAG, "Stream timeout on model $modelId, switching to next model")
                            try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                            handleModelErrorAndSwitch(modelId, group, "timeout", "超时")
                        } else {
                            Log.w(TAG, "Stream connection error on model $modelId, switching to next model", e)
                            try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                            handleModelErrorAndSwitch(modelId, group, "connection_error", "连接失败")
                        }
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

        return jsonError("timeout_error", "所有候选模型均请求失败或超时，请稍后重试")
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
        } catch (e: com.google.gson.JsonParseException) {
            anthropicError("invalid_request_error", "Invalid JSON body: ${e.message}")
        } catch (e: IllegalStateException) {
            anthropicError("invalid_request_error", "Invalid JSON body: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Anthropic error", e)
            anthropicError("api_error", e.message ?: "unknown")
        }
    }

    private fun handleAnthropicNonStream(chatBody: JsonObject, model: String): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()
        val requestStartTime = System.currentTimeMillis()
        // 换模型旋转总预算：与流式路径一致，避免每个模型都卡满读超时
        var rotateDeadlineMs = Long.MAX_VALUE

        for (modelRetry in 0..2) {
            if (System.currentTimeMillis() > rotateDeadlineMs) {
                Log.w(TAG, "Anthropic non-stream rotation budget exhausted after ${System.currentTimeMillis() - requestStartTime}ms")
                return anthropicError("timeout_error", "所有候选模型均响应缓慢或超时，请稍后重试")
            }
            var modelId: String? = null
            var modelAcquired = false
            try {
                modelId = if (modelRetry == 0) getModelToUse(chatBody, null)
                          else configManager.selectFastestModel(group, triedModelIds)
                if (modelId == null) return anthropicError("api_error",
                    if (modelRetry == 0) "No model available" else "No alternative model available")
                if (modelId in triedModelIds) return anthropicError("api_error", "No alternative model available")
                triedModelIds.add(modelId)
                if (modelRetry == 0) rotateDeadlineMs = requestStartTime + 2 * modelPatienceMs(modelId)

                RouterState.acquireModel(modelId)
                modelAcquired = true
                chatBody.addProperty("model", modelId)
                val providerId = getProviderIdForModel(modelId)
                val apiKey = providerManager.getNextKey(providerId)
                if (apiKey.isEmpty()) return anthropicError("authentication_error", "No API key available for provider $providerId")

                val sanitizedChatBody = sanitizeRequestBody(chatBody)
                val chatResponse = forwardToProvider(sanitizedChatBody.toString(), apiKey, providerId)

                var isEarlyRateLimit = false
                var isNormalRateLimit = false
                try {
                    val checkParsed = JsonParser.parseString(chatResponse).asJsonObject
                    val checkError = checkParsed.get("error")
                    if (checkError != null && checkError.isJsonObject) {
                        val errorType = checkError.asJsonObject.get("type")?.asString
                        if (errorType == "early_rate_limit") isEarlyRateLimit = true
                        if (errorType == "rate_limit_error") isNormalRateLimit = true
                    }
                } catch (_: Exception) {}

                // 普通429：保持锁定 + 切换API KEY重试（forwardToProvider内部已处理）
                if (isNormalRateLimit) {
                    Log.w(TAG, "Anthropic non-stream normal 429 on model $modelId, keeping lock")
                    StatsManager.recordCall(modelId, false)
                    // 必须按 Anthropic 错误格式 + HTTP 429 返回，之前返回 200 + OpenAI 格式错误会让客户端无法识别
                    val errMsg = try {
                        JsonParser.parseString(chatResponse).asJsonObject
                            .getAsJsonObject("error")?.get("message")?.asString
                    } catch (_: Exception) { null }
                    return anthropicError("rate_limit_error", errMsg ?: "Rate limited after retries")
                }

                // Early 429：解锁分组 + 切换模型 + 启动健康检测
                if (isEarlyRateLimit) {
                    Log.w(TAG, "Anthropic non-stream early 429 on model $modelId, unlocking and switching model")
                    StatsManager.recordCall(modelId, false)
                    handleModelErrorAndSwitch(modelId, group, "early_429", "429 限流")
                    continue
                }

                val responseObj = try {
                    JsonParser.parseString(chatResponse).asJsonObject
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse upstream response as JSON", e)
                    StatsManager.recordCall(modelId, false)
                    // JSON解析失败：解锁分组 + 切换模型 + 启动健康检测
                    handleModelErrorAndSwitch(modelId, group, "parse_error", "响应解析失败")
                    continue
                }

                if (responseObj.has("error")) {
                    // 其他错误：解锁分组 + 切换模型 + 启动健康检测
                    Log.w(TAG, "Anthropic non-stream error on model $modelId, unlocking and switching model")
                    handleModelErrorAndSwitch(modelId, group, "api_error", "API错误")
                    StatsManager.recordCall(modelId, false)
                    continue
                }

                val choices = responseObj.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                if (choices == null || choices.size() == 0) {
                    StatsManager.recordCall(modelId, false)
                    // 无choices：解锁分组 + 切换模型 + 启动健康检测
                    handleModelErrorAndSwitch(modelId, group, "no_choices", "无choices返回")
                    continue
                }

                val anthropicResponse = ProtocolConverter.openAIToAnthropicResponse(responseObj, model)
                StatsManager.recordCall(modelId, true)
                return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(anthropicResponse))
            } catch (e: Exception) {
                Log.w(TAG, "Anthropic non-stream error on model $modelId, switching to next model")
                try { modelId?.let { StatsManager.recordCall(it, false) } } catch (_: Exception) {}
                if (modelId != null) {
                    handleModelErrorAndSwitch(modelId, group, "exception", "异常: ${e.message}")
                }
                continue
            } finally {
                if (modelAcquired) modelId?.let { RouterState.releaseModel(it) }
            }
        }
        return anthropicError("overloaded_error", "所有候选模型均请求失败或超时，请稍后重试")
    }

    private fun handleAnthropicStream(chatBody: JsonObject, model: String, @Suppress("UNUSED_PARAMETER") originalBody: JsonObject): Response {
        val group = configManager.getGroupByPort(listeningPort)
        val triedModelIds = mutableSetOf<String>()
        val requestStartTime = System.currentTimeMillis()
        // 换模型旋转总预算：与 OpenAI 流式路径一致，避免每个模型都卡满超时
        var rotateDeadlineMs = Long.MAX_VALUE

        for (modelRetry in 0..2) {
            if (System.currentTimeMillis() > rotateDeadlineMs) {
                Log.w(TAG, "Anthropic stream rotation budget exhausted after ${System.currentTimeMillis() - requestStartTime}ms")
                return anthropicError("timeout_error", "所有候选模型均响应缓慢或超时，请稍后重试")
            }
            val modelId = if (modelRetry == 0) getModelToUse(chatBody, null)
                          else configManager.selectFastestModel(group, triedModelIds)
            if (modelId == null) return anthropicError("api_error",
                if (modelRetry == 0) "No model available" else "No alternative model available")
            if (modelId in triedModelIds) return anthropicError("api_error", "No alternative model available")
            triedModelIds.add(modelId)
            if (modelRetry == 0) rotateDeadlineMs = requestStartTime + 2 * modelPatienceMs(modelId)

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
                val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)

                while (true) {
                    attempt++
                    val bodyStr = sanitizeRequestBody(chatBody).toString()
                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/chat/completions")
                        .header("Authorization", "Bearer $currentApiKey")
                        .header("Content-Type", "application/json")
                        .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val requestStartMs = System.currentTimeMillis()
                    val call = newStreamCall(request, modelId, firstByteReceived)
                    try {
                        val response = call.execute()
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            if (response.code == 429) {
                                if (providerManager.isEarly429(providerId, currentApiKey)) {
                                    // Early 429：解锁分组 + 切换模型 + 启动健康检测
                                    Log.w(TAG, "Anthropic stream early 429 on model $modelId, unlocking and switching model")
                                    StatsManager.recordCall(modelId, false)
                                    handleModelErrorAndSwitch(modelId, group, "early_429", "429 限流")
                                    switchModel = true
                                    break
                                }
                                // 普通429：保持锁定 + 切换API KEY重试
                                if (attempt <= maxRetries) {
                                    val newKey = providerManager.reserveNextKey(providerId, currentApiKey)
                                    if (newKey.isNotEmpty() && newKey != currentApiKey) {
                                        currentApiKey = newKey
                                        Log.w(TAG, "Anthropic stream normal 429, keeping lock, retry $attempt with different key")
                                        continue
                                    }
                                    val delay = (500L * attempt)
                                    if (totalSleepMs + delay > maxTotalSleepMs) {
                                        Log.w(TAG, "Anthropic stream 429, total sleep limit reached, switching model")
                                        // 达到重试上限，解锁 + 切换模型 + 健康检测
                                        handleModelErrorAndSwitch(modelId, group, "429_limit", "429 限流重试耗尽")
                                        switchModel = true
                                        break
                                    }
                                    totalSleepMs += delay
                                    Log.w(TAG, "Anthropic stream 429, retry $attempt after ${delay}ms (same key)")
                                    Thread.sleep(delay)
                                    continue
                                }
                                // 重试次数用完，解锁 + 切换模型 + 健康检测
                                handleModelErrorAndSwitch(modelId, group, "429_limit", "429 限流重试耗尽")
                                switchModel = true
                                break
                            }
                            if (response.code == 400) {
                                // 400 是客户端请求错误，不应下线模型，直接返回错误给客户端
                                Log.w(TAG, "Anthropic stream 400 from $providerId: ${errorBody.take(300)}")
                                try {
                                    val reqBody = JsonParser.parseString(bodyStr).asJsonObject
                                    val sentParams = reqBody.keySet().filter { it != "messages" }
                                    Log.w(TAG, "Anthropic stream params to $providerId: $sentParams")
                                } catch (_: Exception) {}
                                StatsManager.recordCall(modelId, false)
                                return anthropicError("invalid_request_error", "Upstream 400: ${errorBody.take(500)}")
                            } else {
                                Log.w(TAG, "Anthropic stream upstream error HTTP ${response.code}: ${errorBody.take(200)}")
                                StatsManager.recordCall(modelId, false)
                                // 5xx/其他错误：解锁分组 + 切换模型 + 启动健康检测
                                handleModelErrorAndSwitch(modelId, group, "http_${response.code}", "HTTP ${response.code}")
                                switchModel = true
                                break
                            }
                        }

                        val responseBody = response.body
                        if (responseBody == null) {
                            // 空响应：解锁分组 + 切换模型 + 启动健康检测
                            StatsManager.recordCall(modelId, false)
                            handleModelErrorAndSwitch(modelId, group, "empty_response", "空响应")
                            switchModel = true
                            break
                        }
                        val inputStream = responseBody.byteStream()

                        // 成功统计在流结束后由 pipe 任务记账，避免断流也算成功
                        modelAcquired = false

                        val messageId = "msg_${System.currentTimeMillis()}"
                        val pipeIn = PipedInputStream(65536)
                        val pipeOut = PipedOutputStream(pipeIn)

                        // TTFT 记录 + 看门狗首字节标记；错误事件由 writeAnthropicStreamEvents 写出
                        val trackedInput = TTFTInputStream(
                            delegate = inputStream,
                            modelId = modelId,
                            firstByteReceived = firstByteReceived,
                            requestStartMs = requestStartMs
                        )

                        try {
                            streamPipeExecutor.execute {
                                var streamOk = false
                                try {
                                    writeAnthropicStreamEvents(messageId, model, trackedInput, pipeOut)
                                    streamOk = true
                                } catch (e: Exception) {
                                    Log.e(TAG, "Anthropic stream pipe error", e)
                                } finally {
                                    StatsManager.recordCall(modelId, streamOk)
                                    try { pipeOut.close() } catch (_: Exception) {}
                                    // trackedInput.close() 内部会 releaseModel，勿重复释放
                                    try { trackedInput.close() } catch (_: Exception) {}
                                    try { responseBody.close() } catch (_: Exception) {}
                                }
                            }
                        } catch (e: java.util.concurrent.RejectedExecutionException) {
                            Log.e(TAG, "Executor overloaded, rejecting stream pipe task", e)
                            try { pipeOut.close() } catch (_: Exception) {}
                            try { pipeIn.close() } catch (_: Exception) {}
                            try { trackedInput.close() } catch (_: Exception) {}
                            try { responseBody.close() } catch (_: Exception) {}
                            modelAcquired = false
                            return anthropicError("overloaded_error", "Server under heavy load, please retry")
                        }

                        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
                    } catch (e: Exception) {
                        // 看门狗取消（TTFT 超时）与 readTimeout 统一按超时处理
                        val isTimeout = e is java.net.SocketTimeoutException ||
                            e.message?.contains("cancel", ignoreCase = true) == true
                        if (isTimeout) {
                            Log.w(TAG, "Anthropic stream timeout on model $modelId, switching to next model")
                            try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                            handleModelErrorAndSwitch(modelId, group, "timeout", "超时")
                        } else {
                            Log.w(TAG, "Anthropic stream connection error on model $modelId, switching to next model", e)
                            try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
                            handleModelErrorAndSwitch(modelId, group, "connection_error", "连接失败")
                        }
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

        return anthropicError("overloaded_error", "所有候选模型均请求失败或超时，请稍后重试")
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
            // 流式标签清洗：处理跨 delta 边界的 tool_call 标签（如 "</func" + "tion>"）
            val tagCleaner = ProtocolConverter.StreamingTagCleaner()
            // tool_calls index 推断：上游缺失 index 时按 id 归并、id 也缺失时续接上一个
            val idToTcIndex = mutableMapOf<String, Int>()
            var lastTcIndex: Int? = null
            var nextTcSeq = 0
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
                            val cleaned = tagCleaner.push(content)
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
                                // 推断 tool call 序号：优先上游 index；缺失时按 id 归并，id 也缺失时续接上一个
                                val rawIndex = td.get("index")?.takeIf { it.isJsonPrimitive }?.asInt
                                val tcId = td.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                                val tcIndex = when {
                                    rawIndex != null -> rawIndex
                                    tcId.isNotEmpty() -> idToTcIndex.getOrPut(tcId) { nextTcSeq++ }
                                    else -> lastTcIndex ?: 0
                                }
                                if (tcId.isNotEmpty()) idToTcIndex.putIfAbsent(tcId, tcIndex)
                                lastTcIndex = tcIndex
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
                                    // 空 id 会被 Anthropic 客户端拒收，兜底生成
                                    val effectiveId = tcId.ifEmpty { "toolu_gen_${System.currentTimeMillis()}_$tcIndex" }
                                    toolCallBlocks[tcIndex] = ToolCallState(effectiveId, tcName)
                                    toolCallIndexMap[tcIndex] = blockIndex
                                    currentToolIndex = blockIndex
                                    val toolBlockStartData = JsonObject().apply {
                                        addProperty("type", "content_block_start")
                                        addProperty("index", blockIndex)
                                        add("content_block", JsonObject().apply {
                                            addProperty("type", "tool_use")
                                            addProperty("id", effectiveId)
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

            // 输出流式清洗器暂存的尾部文本（跨 delta 标签清洗的残留）
            val tailText = tagCleaner.flush()
            if (tailText.isNotEmpty()) {
                if (thinkingBlockStarted) {
                    val thinkingStop = JsonObject().apply {
                        addProperty("type", "content_block_stop")
                        addProperty("index", thinkingBlockIndex)
                    }
                    writer.write("event: content_block_stop\ndata: ${gson.toJson(thinkingStop)}\n\n")
                    thinkingBlockStarted = false
                }
                if (!textBlockStarted) {
                    textBlockIndex = contentBlockIndex
                    contentBlockIndex++
                    textBlockStarted = true
                    val tailBlockStart = JsonObject().apply {
                        addProperty("type", "content_block_start")
                        addProperty("index", textBlockIndex)
                        add("content_block", JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "")
                        })
                    }
                    writer.write("event: content_block_start\ndata: ${gson.toJson(tailBlockStart)}\n\n")
                }
                val tailDelta = JsonObject().apply {
                    addProperty("type", "content_block_delta")
                    addProperty("index", textBlockIndex)
                    add("delta", JsonObject().apply {
                        addProperty("type", "text_delta")
                        addProperty("text", tailText)
                    })
                }
                writer.write("event: content_block_delta\ndata: ${gson.toJson(tailDelta)}\n\n")
                writer.flush()
            }

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

    /**
     * 启动指数退避健康检测任务，持续检测出错模型是否恢复
     * 恢复后停止检测，如果之前是锁定状态则恢复锁定
     */
    private fun startHealthCheckWithBackoff(modelId: String, groupName: String, wasLocked: Boolean, errorType: String) {
        // 使用 putIfAbsent 原子启动，避免并发重复启动健康检测
        val healthInfo = RouterState.startHealthCheck(modelId, groupName, wasLocked, errorType)
        if (healthInfo == null) {
            Log.d(TAG, "Health check already running for model $modelId, skip")
            return
        }
        Log.i(TAG, "Starting health check for model $modelId (wasLocked=$wasLocked, error=$errorType)")

        healthCheckExecutor.execute {
            var backoffMs = 5000L  // 初始退避5秒
            val maxBackoffMs = 300_000L  // 最大退避5分钟

            while (healthInfo.running) {
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    break
                }

                if (!healthInfo.running) break

                try {
                    val providerId = getProviderIdForModel(modelId)
                    val provider = providerManager.getProvider(providerId)
                    val baseUrl = provider?.baseUrl ?: Constants.DEFAULT_BASE_URL
                    // 探测请求不计入限流计数，避免污染 isEarly429 的判断依据
                    val apiKey = providerManager.getKeyWithoutCounting(providerId)

                    if (apiKey.isEmpty()) {
                        Log.w(TAG, "Health check for $modelId: no API key, retry later")
                        backoffMs = minOf(backoffMs * 2, maxBackoffMs)
                        continue
                    }

                    // 发送一个简单的健康检测请求
                    val healthCheckBody = JsonObject().apply {
                        addProperty("model", modelId)
                        add("messages", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", "hi")
                            })
                        })
                        addProperty("max_tokens", 5)
                        addProperty("stream", false)
                    }

                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(healthCheckBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val response = healthCheckClient.newCall(request).execute()
                    val isSuccess = response.isSuccessful
                    response.body?.close()

                    if (isSuccess) {
                        Log.i(TAG, "Health check PASSED for model $modelId, marking as recovered")
                        RouterState.finishHealthCheck(modelId)
                        break
                    } else {
                        Log.w(TAG, "Health check FAILED for model $modelId, HTTP ${response.code}, next backoff ${backoffMs * 2}ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Health check error for model $modelId: ${e.message}, next backoff ${backoffMs * 2}ms")
                }

                // 指数退避
                backoffMs = minOf(backoffMs * 2, maxBackoffMs)
            }
        }
    }

    /**
     * 处理非锁定错误：解锁分组 + 切换模型 + 启动健康检测
     * @return true 表示已处理并应切换模型，false 表示不应切换
     */
    private fun handleModelErrorAndSwitch(modelId: String, group: String, errorType: String, errorMsg: String): Boolean {
        // 429 策略性错误立即标记；其余错误（超时/连接/5xx等）连续失败达到阈值才判定模型异常，
        // 避免单次抖动就解锁用户锁定的模型、触发健康检测来回切换
        val isPolicyError = errorType == "early_429" || errorType == "429_limit"
        if (!isPolicyError && !RouterState.recordFailure(modelId)) {
            Log.w(TAG, "Transient error on $modelId ($errorType: $errorMsg), switching model for this request only")
            return true
        }
        val wasLocked = RouterState.getLockedModel(group) == modelId
        RouterState.updateModelError(modelId, errorMsg)
        if (wasLocked) {
            RouterState.unlockGroup(group)
        }
        // 启动指数退避健康检测
        startHealthCheckWithBackoff(modelId, group, wasLocked, errorType)
        return true
    }

    /**
     * 慢响应并行请求处理：超过30秒未返回则将请求同步发送给下一个大模型
     * 最多重复5次，只要有一个成功返回就停止，只把第一个成功的返回给调用者
     */
    private data class ParallelRequestResult(
        val success: Boolean,
        val response: String?,
        val modelId: String,
        val error: String? = null
    )

    /**
     * 执行单个模型请求（读超时取模型配置的 timeout），用于并行请求。
     * inFlightCalls 记录在途调用，供成功后取消其余请求省资源。
     */
    private fun executeModelRequest(
        body: JsonObject,
        modelId: String,
        inFlightCalls: ConcurrentHashMap<String, okhttp3.Call>
    ): ParallelRequestResult {
        var activeCall: okhttp3.Call? = null
        return try {
            val providerId = getProviderIdForModel(modelId)
            val apiKey = providerManager.getNextKey(providerId)
            if (apiKey.isEmpty()) {
                return ParallelRequestResult(false, null, modelId, "No API key for provider $providerId")
            }

            val baseUrl = providerManager.getProvider(providerId)?.baseUrl ?: Constants.DEFAULT_BASE_URL
            val requestBody = body.deepCopy()
            requestBody.addProperty("model", modelId)
            requestBody.remove("group")
            val sanitizedBody = sanitizeRequestBody(requestBody)

            // 与流式路径一致：读超时使用模型配置的 timeout（毫秒），兜底 10 秒
            val readTimeoutMs = (configManager.getModelTimeout(modelId) * 1000L).coerceAtLeast(10_000L)
            val parallelClient = getParallelClient(readTimeoutMs)

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(sanitizedBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            RouterState.acquireModel(modelId)
            val call = parallelClient.newCall(request)
            activeCall = call
            inFlightCalls[modelId] = call
            try {
                val response = call.execute()
                val responseBody = response.body?.string()
                if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                    // 验证响应是否包含choices
                    try {
                        val parsed = JsonParser.parseString(responseBody).asJsonObject
                        if (!parsed.has("error") && parsed.has("choices")) {
                            StatsManager.recordCall(modelId, true)
                            return ParallelRequestResult(true, responseBody, modelId)
                        }
                    } catch (_: Exception) {}
                    StatsManager.recordCall(modelId, false)
                    return ParallelRequestResult(false, responseBody, modelId, "Invalid response format")
                }
                StatsManager.recordCall(modelId, false)
                // 400 是客户端错误，不应标记模型错误，特殊标记让上层判断
                if (response.code == 400) {
                    return ParallelRequestResult(false, responseBody, modelId, "CLIENT_ERROR_400")
                }
                return ParallelRequestResult(false, responseBody, modelId, "HTTP ${response.code}")
            } finally {
                inFlightCalls.remove(modelId, call)
                RouterState.releaseModel(modelId)
            }
        } catch (e: Exception) {
            // 对冲竞速中被主动取消（已有其他模型成功返回）不算失败，避免污染错误统计
            if (activeCall?.isCanceled() != true) {
                try { StatsManager.recordCall(modelId, false) } catch (_: Exception) {}
            }
            return ParallelRequestResult(false, null, modelId, e.message ?: "unknown")
        }
    }

    /**
     * 慢响应处理：30秒未返回则并行发送给下一个模型，最多5次，取首个成功结果
     */
    private fun handleSlowResponseWithParallel(body: JsonObject, group: String, firstModelId: String): Response {
        val triedModelIds = mutableSetOf(firstModelId)
        val successFound = java.util.concurrent.atomic.AtomicBoolean(false)
        val firstSuccess = AtomicReference<ParallelRequestResult?>(null)
        val allFutures = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<ParallelRequestResult>>()
        val inFlightCalls = ConcurrentHashMap<String, okhttp3.Call>()
        val startTime = System.currentTimeMillis()
        // 整体截止时间：防止 hedge 链条 + 逐个等待导致最坏数分钟才回话
        val deadlineMs = startTime + SLOW_RESPONSE_TIMEOUT_MS * MAX_PARALLEL_ATTEMPTS

        fun cancelRemaining() {
            for ((_, call) in inFlightCalls) {
                try { call.cancel() } catch (_: Exception) {}
            }
        }

        fun submit(modelId: String): CompletableFuture<ParallelRequestResult> {
            val future = CompletableFuture.supplyAsync(
                {
                    val result = executeModelRequest(body, modelId, inFlightCalls)
                    if (result.success && successFound.compareAndSet(false, true)) {
                        firstSuccess.set(result)
                        // 已有结果：取消其余在途请求，避免白烧 token 和连接
                        cancelRemaining()
                    }
                    result
                },
                parallelRequestExecutor
            )
            allFutures.add(future)
            return future
        }

        submit(firstModelId)

        for (attempt in 1 until MAX_PARALLEL_ATTEMPTS) {
            if (successFound.get()) break
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) break

            // 等待任一请求返回（不限于最新提交的），最多 30 秒；
            // 全部已结束（如首个模型快速失败）时不放弃，直接补位下一个模型
            val pending = allFutures.filter { !it.isDone }.toTypedArray()
            if (pending.isNotEmpty()) {
                try {
                    CompletableFuture.anyOf(*pending)
                        .get(minOf(SLOW_RESPONSE_TIMEOUT_MS, remaining), TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    Log.w(TAG, "Slow response: no model returned in ${SLOW_RESPONSE_TIMEOUT_MS}ms, starting next model (attempt $attempt)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for parallel responses", e)
                }
            }

            if (successFound.get()) break

            // 选择下一个模型（排除已试过的）
            val nextModel = configManager.selectFastestModel(group, triedModelIds)
            if (nextModel == null) {
                Log.w(TAG, "No more alternative models for parallel request")
                break
            }
            triedModelIds.add(nextModel)

            // 启动下一个模型的请求
            submit(nextModel)
        }

        // 在整体截止时间内等待剩余请求自然结束（任一成功会在 supply 内直接记录）
        for (future in allFutures) {
            if (firstSuccess.get() != null) break
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) break
            try { future.get(remaining, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }

        // 返回第一个成功的结果
        val successResult = firstSuccess.get()
        if (successResult != null) {
            cancelRemaining()
            Log.i(TAG, "Parallel request succeeded with model ${successResult.modelId}")
            return newFixedLengthResponse(Response.Status.OK, "application/json", successResult.response)
        }
        // 全部失败/超时：取消剩余调用，别再烧 token
        cancelRemaining()

        // 所有请求都失败了，收集错误信息并对失败模型启动健康检测
        val errors = allFutures.mapNotNull { future ->
            try { future.getNow(ParallelRequestResult(false, null, "", "not completed")) }
            catch (_: Exception) { null }
        }

        // 对失败的模型启动健康检测（去重），但跳过 400 客户端错误（不是模型问题）
        val processedModels = mutableSetOf<String>()
        for (error in errors) {
            if (error.modelId.isNotEmpty() && error.modelId !in processedModels) {
                processedModels.add(error.modelId)
                // 400 是客户端错误，不应标记模型错误或启动健康检测
                if (error.error == "CLIENT_ERROR_400") {
                    continue
                }
                val wasLocked = RouterState.getLockedModel(group) == error.modelId
                if (wasLocked) {
                    RouterState.unlockGroup(group)
                }
                startHealthCheckWithBackoff(error.modelId, group, wasLocked, "parallel_error")
                RouterState.updateModelError(error.modelId, error.error ?: "请求失败")
            }
        }

        val errorMsg = errors.firstOrNull { !it.error.isNullOrEmpty() }?.error ?: "All models failed"
        Log.w(TAG, "All parallel requests failed, tried models: $triedModelIds, errors: ${errors.map { "${it.modelId}: ${it.error}" }}")
        return jsonError("api_error", "All parallel requests failed: $errorMsg")
    }

    private val modelsClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    // 健康检测专用客户端（共享一个实例，避免每次检测 new 一个 OkHttpClient 白建线程池）
    private val healthCheckClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
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
            // 全线慢标记：组内所有有新鲜延迟数据的模型都慢，多为本机网络或上游整体拥堵
            val latencies = g.models.filter { it.enabled }.mapNotNull { RouterState.getRealLatency(it.id) }
            val allSlow = latencies.isNotEmpty() && latencies.all { it > GROUP_ALL_SLOW_MS }
            mapOf(
                "name" to g.name,
                "port" to g.port,
                "enabled" to g.enabled,
                "all_slow" to allSlow,
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
                            "real_latency_ms" to RouterState.getRealLatency(m.id),
                            "degraded" to RouterState.isDegraded(m.id),
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

    private fun handleLockModelApi(body: String): Response {
        return try {
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
                        val newKey = providerManager.reserveNextKey(providerId, currentApiKey)
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
        "chat_template_kwargs", "repetition_penalty",
        "reasoning_effort"
    )

    private fun sanitizeRequestBody(body: JsonObject): JsonObject {
        val keysToRemove = body.keySet().filter { it !in OPENAI_STANDARD_PARAMS }
        val cleaned = if (keysToRemove.isEmpty()) body else (body.deepCopy() ?: body)
        for (key in keysToRemove) {
            Log.d(TAG, "Sanitizing non-standard param: $key")
            cleaned.remove(key)
        }
        sanitizeMessageReasoningFields(cleaned)
        return cleaned
    }

    /**
     * 消息级清洗：上游 serde 把 reasoning 视为 reasoning_content 的别名，
     * 一条消息同时带两个字段会报 "duplicate field reasoning_content" (HTTP 400)。
     * 客户端（如 Hermes）回放历史时经常两者并存，此处合并为单一 reasoning_content。
     */
    private fun sanitizeMessageReasoningFields(body: JsonObject) {
        val messages = body.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (item in messages) {
            if (!item.isJsonObject) continue
            val msg = item.asJsonObject
            val reasoning = msg.get("reasoning") ?: continue
            if (!msg.has("reasoning_content")) continue
            val rc = msg.get("reasoning_content")
            val rcEmpty = rc == null || !rc.isJsonPrimitive || rc.asString.isEmpty()
            if (rcEmpty && reasoning.isJsonPrimitive) {
                msg.add("reasoning_content", reasoning)
            }
            msg.remove("reasoning")
        }
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
        // 按错误类型给出正确的 HTTP 状态码，之前一律 500 导致客户端无法正确重试
        val status = when (type) {
            "bad_request" -> Response.Status.BAD_REQUEST
            "auth_error" -> Response.Status.UNAUTHORIZED
            "not_found_error" -> Response.Status.NOT_FOUND
            "rate_limit_error" -> Response.Status.TOO_MANY_REQUESTS
            "server_overloaded", "overloaded_error" -> Response.Status.SERVICE_UNAVAILABLE
            "timeout_error", "connection_error", "upstream_error" -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json",
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
            "rate_limit_error" -> Response.Status.TOO_MANY_REQUESTS
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

    /** OpenAI SSE 错误负载：上游中途断流时注入，客户端可感知失败而不是静默截断 */
    private fun buildOpenAiErrorSse(e: Exception): ByteArray {
        val msg = (e.message ?: "stream error")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        val json = "{\"error\":{\"message\":\"stream interrupted: $msg\",\"type\":\"api_error\"}}"
        return "data: $json\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
    }

    private data class ToolCallState(var id: String, var name: String)

    /**
     * 流式包装：
     * 1. 记录 TTFT（独立指标，不污染 selectFastestModel 的响应时间排序）并采集被动测速延迟样本
     * 2. 供 TTFT 看门狗检查首字节是否到达
     * 3. errorPayloadBuilder 非空时，上游中途出错注入错误负载（OpenAI 路径）；为空则抛出（Anthropic 路径由上层写 error 事件）
     * 4. 流结束后回调 onComplete 记账（成功仅在流正常结束后统计，断流不算成功）
     */
    private class TTFTInputStream(
        private val delegate: InputStream,
        private val modelId: String,
        private val firstByteReceived: java.util.concurrent.atomic.AtomicBoolean,
        private val requestStartMs: Long = System.currentTimeMillis(),
        private val errorPayloadBuilder: ((Exception) -> ByteArray)? = null,
        private val onComplete: ((Boolean) -> Unit)? = null
    ) : InputStream() {
        private var closed = false
        private var completed = false
        private var streamError = false
        private var ttftRecorded = false
        private var errorPayload: ByteArray? = null
        private var errorPayloadPos = 0

        private fun recordTTFTIfNeeded() {
            if (!ttftRecorded) {
                ttftRecorded = true
                firstByteReceived.set(true)
                val ttft = System.currentTimeMillis() - requestStartMs
                RouterState.updateTTFT(modelId, ttft)
                // 被动测速：真实请求的端到端首字节延迟作为选模排序依据
                RouterState.recordLatency(modelId, ttft)
            }
        }

        private fun complete(success: Boolean) {
            if (completed) return
            completed = true
            try { onComplete?.invoke(success) } catch (_: Exception) {}
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            errorPayload?.let { payload ->
                if (errorPayloadPos >= payload.size) {
                    // 错误负载已输出完毕，确保释放资源
                    close()
                    return -1
                }
                val n = minOf(len, payload.size - errorPayloadPos)
                System.arraycopy(payload, errorPayloadPos, b, off, n)
                errorPayloadPos += n
                return n
            }
            return try {
                val n = delegate.read(b, off, len)
                if (n > 0) recordTTFTIfNeeded()
                if (n == -1) {
                    complete(success = !streamError)
                    close()
                }
                n
            } catch (e: Exception) {
                streamError = true
                val builder = errorPayloadBuilder
                if (builder == null) {
                    complete(success = false)
                    close()
                    throw e
                }
                Log.w(TAG, "Stream interrupted on $modelId, injecting error event: ${e.message}")
                complete(success = false)
                val payload = builder(e)
                errorPayload = payload
                errorPayloadPos = 0
                val n = minOf(len, payload.size)
                System.arraycopy(payload, 0, b, off, n)
                errorPayloadPos = n
                n
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            complete(success = !streamError)
            try { delegate.close() } catch (_: Exception) {}
            RouterState.releaseModel(modelId)
        }

        override fun available(): Int = try { delegate.available() } catch (_: Exception) { 0 }

        protected fun finalize() {
            if (!closed) {
                close()
            }
        }
    }
}
