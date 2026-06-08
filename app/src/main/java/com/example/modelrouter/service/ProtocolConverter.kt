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
            val imageParts = mutableListOf<JsonObject>()

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
                            imageParts.add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", "data:$mediaType;base64,$data")
                                })
                            })
                        } else if (srcType == "url" && url.isNotEmpty()) {
                            imageParts.add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", url)
                                })
                            })
                        }
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
                    "tool_use" -> {
                        toolUses.add(JsonObject().apply {
                            addProperty("id", itemObj.s("id") ?: "")
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", itemObj.s("name") ?: "")
                                addProperty("arguments", gson.toJson(itemObj.get("input") ?: JsonObject()))
                            })
                        })
                    }
                    "thinking" -> {
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
                    if (textParts.isNotEmpty() || imageParts.isNotEmpty()) {
                        addUserContentMessage(messages, textParts, imageParts)
                    }
                }
                role == "user" && imageParts.isNotEmpty() -> {
                    addUserContentMessage(messages, textParts, imageParts)
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

    private fun addUserContentMessage(messages: JsonArray, textParts: List<String>, imageParts: List<JsonObject>) {
        if (imageParts.isNotEmpty()) {
            val userContent = JsonArray()
            textParts.forEach { tp ->
                userContent.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", tp)
                })
            }
            imageParts.forEach { ip -> userContent.add(ip) }
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        } else {
            messages.add(msg("user", textParts.joinToString("\n")))
        }
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
            })
        }

        val cleaned = cleanToolCallTags(outputContent)
        if (cleaned.isNotEmpty()) {
            responseContent.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", cleaned)
            })
        }

        for (tc in toolCalls) {
            responseContent.add(JsonObject().apply {
                addProperty("type", "tool_use")
                addProperty("id", tc.s("id") ?: "")
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
