package com.example.modelrouter.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

object ProtocolConverter {

    private val gson = Gson()

    fun anthropicToOpenAIRequest(anthropicBody: JsonObject): JsonObject {
        val openaiBody = JsonObject()

        openaiBody.addProperty("model", anthropicBody.s("model") ?: "unknown")

        val maxTokens = anthropicBody.s("max_tokens")?.toIntOrNull()
            ?: anthropicBody.s("max_completion_tokens")?.toIntOrNull()
            ?: 4096
        openaiBody.addProperty("max_tokens", maxTokens)

        val stream = anthropicBody.b("stream") ?: false
        openaiBody.addProperty("stream", stream)

        val temperature = anthropicBody.n("temperature")?.asDouble
        if (temperature != null) {
            openaiBody.addProperty("temperature", temperature.coerceIn(0.0, 1.0))
        }

        val topP = anthropicBody.n("top_p")?.asDouble
        if (topP != null) {
            openaiBody.addProperty("top_p", topP)
        }

        val stopSequences = anthropicBody.a("stop_sequences")
        if (stopSequences != null && stopSequences.size() > 0) {
            openaiBody.add("stop", stopSequences)
        }

        val metadata = anthropicBody.o("metadata")
        val userId = metadata?.s("user_id")
        if (!userId.isNullOrEmpty()) {
            openaiBody.addProperty("user", userId)
        }

        val system = anthropicBody.get("system")
        val systemText = extractSystemText(system)

        val messages = JsonArray()
        if (systemText.isNotEmpty()) {
            messages.add(msg("system", systemText))
        }

        val anthropicMessages = anthropicBody.a("messages") ?: JsonArray()
        for (msg in anthropicMessages) {
            if (!msg.isJsonObject) continue
            convertAnthropicMessage(msg.asJsonObject, messages)
        }
        openaiBody.add("messages", messages)

        convertAnthropicTools(anthropicBody, openaiBody)

        convertAnthropicToolChoice(anthropicBody, openaiBody)

        // Anthropic thinking → Agnes chat_template_kwargs.enable_thinking
        val thinking = anthropicBody.o("thinking")
        if (thinking != null) {
            val thinkingType = thinking.s("type")
            if (thinkingType == "enabled" || thinkingType == "adaptive") {
                openaiBody.add("chat_template_kwargs", JsonObject().apply {
                    addProperty("enable_thinking", true)
                })
            }
        }

        return openaiBody
    }

    private fun extractSystemText(system: com.google.gson.JsonElement?): String {
        if (system == null || system.isJsonNull) return ""
        if (system.isJsonPrimitive) return system.asString
        if (system.isJsonArray) {
            val parts = mutableListOf<String>()
            for (item in system.asJsonArray) {
                if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    if (obj.s("type") == "text") {
                        parts.add(obj.s("text") ?: "")
                    }
                } else if (item.isJsonPrimitive) {
                    parts.add(item.asString)
                }
            }
            return parts.joinToString("\n")
        }
        return ""
    }

    private fun convertAnthropicMessage(msgObj: JsonObject, messages: JsonArray) {
        val role = msgObj.s("role") ?: "user"
        val content = msgObj.get("content")

        if (content != null && content.isJsonArray) {
            val textParts = mutableListOf<String>()
            val toolResults = mutableListOf<JsonObject>()
            val toolUses = mutableListOf<JsonObject>()
            val richParts = mutableListOf<JsonObject>()

            for (item in content.asJsonArray) {
                if (!item.isJsonObject) continue
                val itemObj = item.asJsonObject
                val itemType = itemObj.s("type") ?: ""

                when (itemType) {
                    "text" -> {
                        textParts.add(itemObj.s("text") ?: "")
                    }
                    "image" -> {
                        val source = itemObj.o("source") ?: continue
                        val srcType = source.s("type") ?: ""
                        val mediaType = source.s("media_type") ?: "image/png"
                        val data = source.s("data") ?: ""
                        val url = source.s("url") ?: ""
                        if (srcType == "base64" && data.isNotEmpty()) {
                            richParts.add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", "data:$mediaType;base64,$data")
                                })
                            })
                        } else if (srcType == "url" && url.isNotEmpty()) {
                            richParts.add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", url)
                                })
                            })
                        }
                    }
                    "document" -> {
                        // 文档块不再静默丢弃：转为 OpenAI file 输入，无法转换时退化为文本占位
                        val docPart = convertAnthropicDocument(itemObj)
                        if (docPart != null) richParts.add(docPart)
                        else textParts.add("[document: ${itemObj.s("title") ?: "未知文档"}]")
                    }
                    "tool_result" -> {
                        val toolUseId = itemObj.s("tool_use_id") ?: ""
                        val isError = itemObj.b("is_error") ?: false
                        val rawContent = itemObj.get("content")
                        val contentStr = extractContentString(rawContent)
                        toolResults.add(JsonObject().apply {
                            addProperty("tool_call_id", toolUseId)
                            addProperty("content", if (isError && contentStr.isNotEmpty()) "[Tool Error] $contentStr" else contentStr)
                        })
                    }
                    "tool_use", "server_tool_use" -> {
                        val rawId = itemObj.s("id") ?: ""
                        toolUses.add(JsonObject().apply {
                            addProperty("id", rawId.ifEmpty { "toolu_gen_${System.currentTimeMillis()}_${toolUses.size}" })
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", itemObj.s("name") ?: "")
                                addProperty("arguments", gson.toJson(itemObj.get("input") ?: JsonObject()))
                            })
                        })
                    }
                    "thinking", "redacted_thinking" -> {
                    }
                    else -> {
                        // 未知类型（web_search_tool_result 等）不再静默丢弃：有文本字段按文本透传，否则保留 JSON
                        val t = itemObj.s("text") ?: itemObj.s("thinking")
                        if (t != null) textParts.add(t)
                        else textParts.add(gson.toJson(itemObj))
                    }
                }
            }

            when {
                role == "assistant" && toolUses.isNotEmpty() -> {
                    val m = JsonObject()
                    m.addProperty("role", role)
                    m.addProperty("content", textParts.joinToString("\n"))
                    m.add("tool_calls", JsonArray().also { a -> toolUses.forEach { a.add(it) } })
                    messages.add(m)
                }
                role == "user" && toolResults.isNotEmpty() -> {
                    for (tr in toolResults) {
                        val tcId = tr.s("tool_call_id") ?: ""
                        val tcContent = tr.s("content") ?: ""
                        messages.add(msg("tool", tcId, tcContent))
                    }
                    if (textParts.isNotEmpty() || richParts.isNotEmpty()) {
                        addUserContentMessage(messages, textParts, richParts)
                    }
                }
                role == "user" && richParts.isNotEmpty() -> {
                    addUserContentMessage(messages, textParts, richParts)
                }
                else -> {
                    val text = textParts.joinToString("\n")
                    if (text.isNotEmpty()) {
                        messages.add(msg(role, text))
                    }
                }
            }
        } else {
            val contentStr = content?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            messages.add(msg(role, contentStr))
        }
    }

    private fun addUserContentMessage(messages: JsonArray, textParts: List<String>, richParts: List<JsonObject>) {
        if (richParts.isNotEmpty()) {
            val userContent = JsonArray()
            textParts.forEach { tp ->
                userContent.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", tp)
                })
            }
            richParts.forEach { ip -> userContent.add(ip) }
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        } else {
            messages.add(msg("user", textParts.joinToString("\n")))
        }
    }

    /** Anthropic document 块 → OpenAI file 输入；无法转换时返回 null */
    private fun convertAnthropicDocument(itemObj: JsonObject): JsonObject? {
        val source = itemObj.o("source") ?: return null
        val srcType = source.s("type") ?: ""
        val mediaType = source.s("media_type") ?: "application/pdf"
        val data = source.s("data") ?: ""
        val url = source.s("url") ?: ""
        if (srcType == "base64" && data.isNotEmpty()) {
            val ext = when (mediaType) {
                "application/pdf" -> "pdf"
                "text/plain" -> "txt"
                "text/csv" -> "csv"
                else -> mediaType.substringAfter('/', "bin")
            }
            return JsonObject().apply {
                addProperty("type", "file")
                add("file", JsonObject().apply {
                    addProperty("filename", itemObj.s("title") ?: "document.$ext")
                    addProperty("file_data", "data:$mediaType;base64,$data")
                })
            }
        }
        if (srcType == "url" && url.isNotEmpty()) {
            // OpenAI chat 不支持 URL 文件，退化为文本占位以保留上下文
            return JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "[document: $url]")
            }
        }
        return null
    }

    private fun convertAnthropicTools(anthropicBody: JsonObject, openaiBody: JsonObject) {
        val tools = anthropicBody.a("tools") ?: return
        val openaiTools = JsonArray()
        for (tool in tools) {
            if (!tool.isJsonObject) continue
            val t = tool.asJsonObject
            openaiTools.add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", t.s("name") ?: "")
                    addProperty("description", t.s("description") ?: "")
                    val inputSchema = t.get("input_schema") ?: t.get("parameters") ?: JsonObject()
                    add("parameters", inputSchema)
                })
            })
        }
        if (openaiTools.size() > 0) {
            openaiBody.add("tools", openaiTools)
        }
    }

    private fun convertAnthropicToolChoice(anthropicBody: JsonObject, openaiBody: JsonObject) {
        val toolChoice = anthropicBody.get("tool_choice") ?: return
        if (toolChoice.isJsonPrimitive) {
            val str = toolChoice.asString
            openaiBody.addProperty("tool_choice", when (str) {
                "any" -> "required"
                else -> str
            })
        } else if (toolChoice.isJsonObject) {
            val tc = toolChoice.asJsonObject
            val tcType = tc.s("type")
            when (tcType) {
                "auto" -> openaiBody.addProperty("tool_choice", "auto")
                "any" -> openaiBody.addProperty("tool_choice", "required")
                "none" -> openaiBody.addProperty("tool_choice", "none")
                "tool" -> {
                    openaiBody.add("tool_choice", JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tc.s("name") ?: "")
                        })
                    })
                }
                else -> openaiBody.addProperty("tool_choice", tcType ?: "auto")
            }
        }
    }

    fun openAIToAnthropicResponse(openaiResponse: JsonObject, requestModel: String): JsonObject {
        val choices = openaiResponse.a("choices") ?: JsonArray()
        val choice = if (choices.size() > 0) choices[0].asJsonObject else null
        val message = choice?.o("message")
        val finishReason = choice?.s("finish_reason") ?: "stop"

        var outputContent = ""
        val toolCalls = mutableListOf<JsonObject>()
        var reasoningContent: String? = null

        if (message != null) {
            outputContent = message.s("content") ?: ""
            val rc = message.get("reasoning_content")
            if (rc != null && rc.isJsonPrimitive) {
                reasoningContent = rc.asString
            }
            val tcs = message.a("tool_calls")
            if (tcs != null) {
                for (tc in tcs) {
                    if (tc.isJsonObject) toolCalls.add(tc.asJsonObject)
                }
            }
        }

        val responseContent = JsonArray()

        if (!reasoningContent.isNullOrEmpty()) {
            responseContent.add(JsonObject().apply {
                addProperty("type", "thinking")
                addProperty("thinking", reasoningContent)
                // Anthropic 协议要求 thinking 块带 signature 字段，缺失会被严格校验的客户端拒收
                addProperty("signature", "")
            })
        }

        val cleaned = cleanToolCallTags(outputContent)
        if (cleaned.isNotEmpty()) {
            responseContent.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", cleaned)
            })
        }

        for ((tcIdx, tc) in toolCalls.withIndex()) {
            val rawToolId = tc.s("id") ?: ""
            responseContent.add(JsonObject().apply {
                addProperty("type", "tool_use")
                // 空 id 会被 Anthropic 客户端拒收，兜底生成
                addProperty("id", rawToolId.ifEmpty { "toolu_gen_${System.currentTimeMillis()}_$tcIdx" })
                addProperty("name", tc.o("function")?.s("name") ?: "")
                add("input", try {
                    JsonParser.parseString(tc.o("function")?.s("arguments") ?: "{}")
                } catch (_: Exception) {
                    JsonObject()
                })
            })
        }

        val usage = openaiResponse.o("usage")
        val inputTokens = usage?.n("prompt_tokens")?.asInt ?: 0
        val outputTokens = usage?.n("completion_tokens")?.asInt ?: 0
        val cacheCreation = usage?.o("prompt_tokens_details")?.n("cached_tokens")?.asInt ?: 0

        val response = JsonObject()
        response.addProperty("id", openaiResponse.s("id") ?: "msg_${System.currentTimeMillis()}")
        response.addProperty("type", "message")
        response.addProperty("role", "assistant")
        response.add("content", responseContent)
        response.addProperty("model", openaiResponse.s("model") ?: requestModel)
        response.addProperty("stop_reason", mapFinishReasonToStopReason(finishReason, toolCalls.isNotEmpty()))
        response.add("stop_sequence", JsonNull.INSTANCE)
        response.add("usage", JsonObject().apply {
            addProperty("input_tokens", inputTokens)
            addProperty("output_tokens", outputTokens)
            if (cacheCreation > 0) {
                addProperty("cache_creation_input_tokens", 0)
                addProperty("cache_read_input_tokens", cacheCreation)
            }
        })

        return response
    }

    fun mapFinishReasonToStopReason(finishReason: String, hasToolCalls: Boolean): String {
        return when {
            hasToolCalls -> "tool_use"
            finishReason == "length" -> "max_tokens"
            finishReason == "tool_calls" -> "tool_use"
            finishReason == "content_filter" -> "end_turn"
            else -> "end_turn"
        }
    }

    fun mapStopReasonToFinishReason(stopReason: String?): String {
        return when (stopReason) {
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            "end_turn" -> "stop"
            "stop_sequence" -> "stop"
            else -> "stop"
        }
    }

    private val TC_TAG_RE = Regex("</?(?:function|tool|param|parameter)[^>]*/?>", RegexOption.IGNORE_CASE)
    private val TC_INCOMPLETE_TAG = Regex("</?(?:function|tool|param|parameter)\\b[^<]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val TC_RESIDUAL = Regex("(</(?:function|tool|param|parameter)>){2,}", RegexOption.IGNORE_CASE)

    fun cleanToolCallTags(text: String): String {
        if (text.isEmpty()) return ""
        var cleaned = TC_RESIDUAL.replace(text, "")
        cleaned = TC_TAG_RE.replace(cleaned, "")
        cleaned = TC_INCOMPLETE_TAG.replace(cleaned, "")
        return cleaned.trim()
    }

    /**
     * 流式标签清洗器：解决 tool_call 标签跨 delta 边界（如 "</func" + "tion>"）时
     * 按分片清洗清不掉、标签字面量漏进正文的问题。
     * push() 返回可安全输出的部分（可能为空串），流结束时调用 flush() 输出残留。
     */
    class StreamingTagCleaner {
        private val buffer = StringBuilder()

        // 标签拼写列表：尾部若为其中某个的前缀，则暂存等待下一个 delta
        private val tagSpellings = listOf(
            "<function", "</function>", "<tool", "</tool>",
            "<param", "</param>", "<parameter", "<" + "/parameter>"
        )

        fun push(text: String): String {
            if (text.isEmpty()) return ""
            buffer.append(text)
            return drain(final = false)
        }

        fun flush(): String = drain(final = true)

        private fun drain(final: Boolean): String {
            var content = buffer.toString()
            content = TC_RESIDUAL.replace(content, "")
            content = TC_TAG_RE.replace(content, "")
            if (final) {
                content = TC_INCOMPLETE_TAG.replace(content, "")
                buffer.setLength(0)
                return content
            }
            val holdIdx = incompleteTailStart(content)
            val emit = if (holdIdx >= 0) content.substring(0, holdIdx) else content
            buffer.setLength(0)
            if (holdIdx >= 0) buffer.append(content.substring(holdIdx))
            return emit
        }

        /** 返回尾部可能为未完成标签的起始下标，无则 -1 */
        private fun incompleteTailStart(content: String): Int {
            val lastLt = content.lastIndexOf('<')
            if (lastLt < 0) return -1
            val tail = content.substring(lastLt)
            val lower = tail.lowercase()
            // 1) 尾部本身就是未完成的标签（如未闭合的 function/tool 标签）
            if (TC_INCOMPLETE_TAG.containsMatchIn(tail)) return lastLt
            // 2) 尾部是某标签拼写的前缀（如 "</func"、"<to"）
            if (tagSpellings.any { it.startsWith(lower) }) return lastLt
            return -1
        }
    }

    private fun extractContentString(rawContent: com.google.gson.JsonElement?): String {
        if (rawContent == null || rawContent.isJsonNull) return ""
        if (rawContent.isJsonPrimitive) return rawContent.asString
        if (rawContent.isJsonArray) {
            val parts = mutableListOf<String>()
            for (ci in rawContent.asJsonArray) {
                if (ci.isJsonObject) {
                    val ciObj = ci.asJsonObject
                    val type = ciObj.s("type")
                    if (type == "text") {
                        parts.add(ciObj.s("text") ?: "")
                    } else if (type == "thinking") {
                        parts.add(ciObj.s("thinking") ?: "")
                    } else {
                        parts.add(gson.toJson(ci))
                    }
                } else if (ci.isJsonPrimitive) {
                    parts.add(ci.asString)
                }
            }
            return parts.joinToString("\n")
        }
        return gson.toJson(rawContent)
    }

    private fun msg(role: String, content: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }

    private fun msg(role: String, toolCallId: String, content: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("tool_call_id", toolCallId)
            addProperty("content", content)
        }
    }

    private fun JsonObject.s(key: String): String? {
        val v = get(key) ?: return null
        return if (v.isJsonPrimitive) v.asString else null
    }

    private fun JsonObject.b(key: String): Boolean? {
        val v = get(key) ?: return null
        return if (v.isJsonPrimitive) v.asBoolean else null
    }

    private fun JsonObject.n(key: String): JsonPrimitive? {
        val v = get(key) ?: return null
        return if (v.isJsonPrimitive && (v.asString.toDoubleOrNull() != null)) v.asJsonPrimitive else null
    }

    private fun JsonObject.o(key: String): JsonObject? {
        val v = get(key) ?: return null
        return if (v.isJsonObject) v.asJsonObject else null
    }

    private fun JsonObject.a(key: String): JsonArray? {
        val v = get(key) ?: return null
        return if (v.isJsonArray) v.asJsonArray else null
    }
}
