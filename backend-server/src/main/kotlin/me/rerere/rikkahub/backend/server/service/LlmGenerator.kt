package me.rerere.rikkahub.backend.server.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.core.model.MessageRecord
import me.rerere.rikkahub.backend.core.util.arrayValue
import me.rerere.rikkahub.backend.core.util.booleanValue
import me.rerere.rikkahub.backend.core.util.intValue
import me.rerere.rikkahub.backend.core.util.objectValue
import me.rerere.rikkahub.backend.core.util.randomId
import me.rerere.rikkahub.backend.core.util.stringValue
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale

data class AssistantGenerationResult(
    val parts: List<JsonObject>,
    val modelId: String?,
    val usage: JsonElement?,
)

interface LlmGenerator {
    suspend fun generateReply(settings: JsonObject, conversation: ConversationRecord): AssistantGenerationResult
    suspend fun generateTitle(settings: JsonObject, conversation: ConversationRecord): String?

    suspend fun generateReplyStreaming(
        settings: JsonObject,
        conversation: ConversationRecord,
        onPartial: suspend (AssistantGenerationResult) -> Unit,
    ): AssistantGenerationResult {
        val result = generateReply(settings, conversation)
        onPartial(result)
        return result
    }
}

class PortableLlmGenerator(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
    private val dataDir: Path = defaultDataDir(),
    private val inlineImageMaxBytes: Long = DEFAULT_INLINE_IMAGE_MAX_BYTES,
) : LlmGenerator {
    override suspend fun generateReply(settings: JsonObject, conversation: ConversationRecord): AssistantGenerationResult {
        val selection = selectModel(settings = settings, assistantId = conversation.assistantId)

        return when (selection.providerType) {
            "openai" -> {
                // OpenAI needs structured history to correctly continue tool-call flows.
                val openAiMessages = buildOpenAiRequestMessages(conversation = conversation, assistant = selection.assistant)
                if (openAiMessages.isEmpty()) {
                    AssistantGenerationResult(
                        parts = listOf(textPart("Message received")),
                        modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
                        usage = null,
                    )
                } else {
                    generateViaOpenAi(settings, selection, openAiMessages, includeTools = true)
                }
            }

            "claude" -> {
                val messages = buildChatMessages(conversation = conversation, assistant = selection.assistant)
                if (messages.isEmpty()) {
                    AssistantGenerationResult(
                        parts = listOf(textPart("Message received")),
                        modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
                        usage = null,
                    )
                } else {
                    generateViaClaude(selection, messages)
                }
            }

            "google" -> {
                val messages = buildChatMessages(conversation = conversation, assistant = selection.assistant)
                if (messages.isEmpty()) {
                    AssistantGenerationResult(
                        parts = listOf(textPart("Message received")),
                        modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
                        usage = null,
                    )
                } else {
                    generateViaGoogle(selection, messages)
                }
            }

            else -> throw IllegalStateException("Unsupported provider type: ${selection.providerType}")
        }
    }

    override suspend fun generateReplyStreaming(
        settings: JsonObject,
        conversation: ConversationRecord,
        onPartial: suspend (AssistantGenerationResult) -> Unit,
    ): AssistantGenerationResult {
        val selection = selectModel(settings = settings, assistantId = conversation.assistantId)

        return when (selection.providerType) {
            "openai" -> {
                val openAiMessages = buildOpenAiRequestMessages(conversation = conversation, assistant = selection.assistant)
                if (openAiMessages.isEmpty()) {
                    val result = AssistantGenerationResult(
                        parts = listOf(textPart("Message received")),
                        modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
                        usage = null,
                    )
                    onPartial(result)
                    result
                } else {
                    generateViaOpenAiStream(
                        settings = settings,
                        selection = selection,
                        messages = openAiMessages,
                        includeTools = true,
                        onPartial = onPartial,
                    )
                }
            }

            else -> {
                val result = generateReply(settings, conversation)
                onPartial(result)
                result
            }
        }
    }

    override suspend fun generateTitle(settings: JsonObject, conversation: ConversationRecord): String? {
        val preferredTitleModelRef = settings.stringValue("titleModelId")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val selection = selectModel(
            settings = settings,
            assistantId = conversation.assistantId,
            modelRefOverride = preferredTitleModelRef,
        )

        val summaryInput = buildTitleInput(conversation)
        if (summaryInput.isBlank()) return null

        val promptTemplate = settings.stringValue("titlePrompt")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TITLE_PROMPT
        val prompt = applyNamedPlaceholders(
            template = promptTemplate,
            values = mapOf(
                "locale" to Locale.getDefault().displayName,
                "content" to summaryInput,
            ),
        )

        val messages = listOf(ChatMessage(role = "user", content = prompt))

        val result = when (selection.providerType) {
            "openai" -> generateViaOpenAi(
                settings,
                selection,
                messages.map { message ->
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive(message.role),
                            "content" to JsonPrimitive(message.content),
                        )
                    )
                },
                includeTools = false,
            )
            "claude" -> generateViaClaude(selection, messages)
            "google" -> generateViaGoogle(selection, messages)
            else -> return null
        }

        val raw = result.parts.firstOrNull { it.stringValue("type") == "text" }?.stringValue("text")?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw
            .replace(Regex("[\r\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'')
            .take(96)
    }

    private suspend fun generateViaOpenAi(
        settings: JsonObject,
        selection: Selection,
        messages: List<JsonObject>,
        includeTools: Boolean,
    ): AssistantGenerationResult {
        val availableTools = if (includeTools) buildAvailableTools(settings, selection) else emptyList()

        val outboundClient = httpClientWithProxy(httpClient, selection.proxy)

        val requestPayload = JsonObject(
            buildMap {
                put("model", JsonPrimitive(selection.modelRecord.stringValue("modelId") ?: "auto"))
                put(
                    "messages",
                    JsonArray(messages)
                )
                selection.assistant.doubleValue("temperature")?.let { put("temperature", JsonPrimitive(it)) }
                selection.assistant.doubleValue("topP")?.let { put("top_p", JsonPrimitive(it)) }
                selection.assistant.intValue("maxTokens")?.let { put("max_tokens", JsonPrimitive(it)) }
                if (availableTools.isNotEmpty()) {
                    put("tools", JsonArray(availableTools.map { it.toOpenAiToolJson() }))
                    put("tool_choice", JsonPrimitive("auto"))
                }
            }
        ).mergeCustomBodies(selection.customBodies)

        val response = postJson(
            url = joinUrl(
                selection.provider.stringValue("baseUrl") ?: "https://api.openai.com/v1",
                selection.provider.stringValue("chatCompletionsPath") ?: "/chat/completions",
            ),
            headers = buildMap {
                put("Content-Type", "application/json")
                put("Authorization", "Bearer ${selection.apiKey}")
                selection.customHeaders.forEach { (name, value) -> put(name, value) }
            },
            body = requestPayload,
            client = outboundClient,
        )

        val choice = response.arrayValue("choices")?.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Provider returned no choices")
        val message = choice.objectValue("message") ?: JsonObject(emptyMap())
        val contentParts = extractOpenAiContentParts(message)
        val reasoning = message.stringValue("reasoning_content") ?: message.stringValue("reasoning")

        val parts = mutableListOf<JsonObject>()
        if (!reasoning.isNullOrBlank()) {
            parts += reasoningPart(reasoning)
        }
        if (contentParts.isNotEmpty()) {
            parts += contentParts
        }
        parts += extractOpenAiToolParts(message = message, availableTools = availableTools)
        if (parts.isEmpty()) {
            parts += textPart("Model returned empty response")
        }

        return AssistantGenerationResult(
            parts = parts,
            modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
            usage = response["usage"],
        )
    }

    private suspend fun generateViaOpenAiStream(
        settings: JsonObject,
        selection: Selection,
        messages: List<JsonObject>,
        includeTools: Boolean,
        onPartial: suspend (AssistantGenerationResult) -> Unit,
    ): AssistantGenerationResult {
        val availableTools = if (includeTools) buildAvailableTools(settings, selection) else emptyList()

        val outboundClient = httpClientWithProxy(httpClient, selection.proxy)

        val requestPayload = JsonObject(
            buildMap {
                put("model", JsonPrimitive(selection.modelRecord.stringValue("modelId") ?: "auto"))
                put("messages", JsonArray(messages))
                put("stream", JsonPrimitive(true))
                put(
                    "stream_options",
                    JsonObject(
                        mapOf(
                            "include_usage" to JsonPrimitive(true),
                        )
                    )
                )
                selection.assistant.doubleValue("temperature")?.let { put("temperature", JsonPrimitive(it)) }
                selection.assistant.doubleValue("topP")?.let { put("top_p", JsonPrimitive(it)) }
                selection.assistant.intValue("maxTokens")?.let { put("max_tokens", JsonPrimitive(it)) }
                if (availableTools.isNotEmpty()) {
                    put("tools", JsonArray(availableTools.map { it.toOpenAiToolJson() }))
                    put("tool_choice", JsonPrimitive("auto"))
                }
            }
        ).mergeCustomBodies(selection.customBodies)

        val modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId")
        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: JsonElement? = null

        postOpenAiStream(
            url = joinUrl(
                selection.provider.stringValue("baseUrl") ?: "https://api.openai.com/v1",
                selection.provider.stringValue("chatCompletionsPath") ?: "/chat/completions",
            ),
            headers = buildMap {
                put("Content-Type", "application/json")
                put("Authorization", "Bearer ${selection.apiKey}")
                selection.customHeaders.forEach { (name, value) -> put(name, value) }
            },
            body = requestPayload,
            client = outboundClient,
        ) { chunk ->
            chunk["usage"]?.let { usage = it }

            val choice = chunk.arrayValue("choices")?.firstOrNull() as? JsonObject ?: return@postOpenAiStream
            val delta = choice.objectValue("delta") ?: return@postOpenAiStream

            var changed = false

            val textDelta = extractOpenAiDeltaText(delta)
            if (textDelta.isNotEmpty()) {
                textBuilder.append(textDelta)
                changed = true
            }

            val reasoningDelta = extractOpenAiDeltaReasoning(delta)
            if (reasoningDelta.isNotEmpty()) {
                reasoningBuilder.append(reasoningDelta)
                changed = true
            }

            collectOpenAiDeltaToolCalls(delta, toolCalls)

            if (changed) {
                val partial = AssistantGenerationResult(
                    parts = buildStreamingParts(
                        content = textBuilder.toString(),
                        reasoning = reasoningBuilder.toString(),
                    ),
                    modelId = modelId,
                    usage = null,
                )
                onPartial(partial)
            }
        }

        if (textBuilder.isEmpty() && reasoningBuilder.isEmpty() && toolCalls.isEmpty()) {
            return generateViaOpenAi(
                settings = settings,
                selection = selection,
                messages = messages,
                includeTools = includeTools,
            )
        }

        val parts = buildOpenAiFinalParts(
            content = textBuilder.toString(),
            reasoning = reasoningBuilder.toString(),
            toolCalls = toolCalls,
            availableTools = availableTools,
        )

        return AssistantGenerationResult(
            parts = parts,
            modelId = modelId,
            usage = usage,
        )
    }

    private data class StreamingToolCall(
        var id: String? = null,
        val nameBuilder: StringBuilder = StringBuilder(),
        val argumentsBuilder: StringBuilder = StringBuilder(),
    )

    private suspend fun postOpenAiStream(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
        client: HttpClient = httpClient,
        onChunk: suspend (JsonObject) -> Unit,
    ) {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.encodeToString(JsonObject.serializer(), body)))

        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val response = withContext(Dispatchers.IO) {
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }

        if (response.statusCode() !in 200..299) {
            val raw = response.body().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.take(400)
            throw IllegalStateException("Provider request failed (${response.statusCode()}): $raw")
        }

        withContext(Dispatchers.IO) {
            response.body().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break

                    val chunk = (runCatching { AppJson.parseToJsonElement(data) }.getOrNull() as? JsonObject) ?: continue
                    onChunk(chunk)
                }
            }
        }
    }

    private fun extractOpenAiDeltaText(delta: JsonObject): String {
        val content = delta["content"] ?: return ""
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                obj.stringValue("text") ?: obj.objectValue("text")?.stringValue("value")
            }.joinToString("")

            else -> ""
        }
    }

    private fun extractOpenAiDeltaReasoning(delta: JsonObject): String {
        return delta.stringValue("reasoning_content")
            ?: delta.stringValue("reasoning")
            ?: ""
    }

    private fun collectOpenAiDeltaToolCalls(delta: JsonObject, out: MutableMap<Int, StreamingToolCall>) {
        val calls = delta.arrayValue("tool_calls")
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

        calls.forEach { item ->
            val index = item.intValue("index") ?: 0
            val acc = out.getOrPut(index) { StreamingToolCall() }

            item.stringValue("id")?.takeIf { it.isNotBlank() }?.let { acc.id = it }

            val function = item.objectValue("function")
            function?.stringValue("name")?.takeIf { it.isNotEmpty() }?.let { acc.nameBuilder.append(it) }
            function?.stringValue("arguments")?.takeIf { it.isNotEmpty() }?.let { acc.argumentsBuilder.append(it) }
        }
    }

    private fun buildStreamingParts(content: String, reasoning: String): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()
        if (reasoning.isNotBlank()) {
            parts += reasoningPart(reasoning)
        }
        if (content.isNotBlank()) {
            parts += textPart(content)
        }
        return parts
    }

    private fun buildOpenAiFinalParts(
        content: String,
        reasoning: String,
        toolCalls: Map<Int, StreamingToolCall>,
        availableTools: List<AvailableTool>,
    ): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()
        if (reasoning.isNotBlank()) {
            parts += reasoningPart(reasoning)
        }
        if (content.isNotBlank()) {
            appendTextOrImageParts(parts, content.trim())
        }

        val approvalMap = availableTools.associate { it.name to it.needsApproval }
        val toolParts = toolCalls
            .toSortedMap()
            .values
            .mapNotNull { call ->
                val toolName = call.nameBuilder.toString().trim()
                if (toolName.isBlank()) return@mapNotNull null
                val input = call.argumentsBuilder.toString().ifBlank { "{}" }
                val approvalType = if (approvalMap[toolName] == true) "pending" else "auto"

                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("tool"),
                        "toolCallId" to JsonPrimitive(call.id ?: randomId()),
                        "toolName" to JsonPrimitive(toolName),
                        "input" to JsonPrimitive(input),
                        "output" to JsonArray(emptyList()),
                        "approvalState" to JsonObject(mapOf("type" to JsonPrimitive(approvalType))),
                    )
                )
            }

        parts += toolParts

        if (parts.isEmpty()) {
            parts += textPart("Model returned empty response")
        }

        return parts
    }

    private suspend fun generateViaClaude(
        selection: Selection,
        messages: List<ChatMessage>,
    ): AssistantGenerationResult {
        val systemPrompt = selection.assistant.stringValue("systemPrompt")?.takeIf { it.isNotBlank() }
        val payloadMessages = messages
            .filter { it.role != "system" }
            .map { message ->
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive(if (message.role == "assistant") "assistant" else "user"),
                        "content" to JsonPrimitive(message.content),
                    )
                )
            }

        val outboundClient = httpClientWithProxy(httpClient, selection.proxy)

        val requestPayload = JsonObject(
            buildMap {
                put("model", JsonPrimitive(selection.modelRecord.stringValue("modelId") ?: "claude"))
                put("messages", JsonArray(payloadMessages))
                put("max_tokens", JsonPrimitive(selection.assistant.intValue("maxTokens") ?: 1024))
                systemPrompt?.let { put("system", JsonPrimitive(it)) }
                selection.assistant.doubleValue("temperature")?.let { put("temperature", JsonPrimitive(it)) }
                selection.customBodies.forEach { (key, value) -> put(key, value) }
            }
        )

        val response = postJson(
            url = joinUrl(selection.provider.stringValue("baseUrl") ?: "https://api.anthropic.com/v1", "/messages"),
            headers = buildMap {
                put("Content-Type", "application/json")
                put("anthropic-version", "2023-06-01")
                put("x-api-key", selection.apiKey)
                selection.customHeaders.forEach { (name, value) -> put(name, value) }
            },
            body = requestPayload,
            client = outboundClient,
        )

        val content = response.arrayValue("content")
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it.stringValue("type") == "text" }
            ?.mapNotNull { it.stringValue("text")?.takeIf(String::isNotBlank) }
            ?.joinToString("\n")
            .orEmpty()

        return AssistantGenerationResult(
            parts = buildParts(content = content, reasoning = null),
            modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
            usage = response["usage"],
        )
    }

    private suspend fun generateViaGoogle(
        selection: Selection,
        messages: List<ChatMessage>,
    ): AssistantGenerationResult {
        val modelId = selection.modelRecord.stringValue("modelId") ?: "gemini-2.0-flash"
        val encodedModelId = URLEncoder.encode(modelId, StandardCharsets.UTF_8)
        val baseUrl = selection.provider.stringValue("baseUrl") ?: "https://generativelanguage.googleapis.com/v1beta"

        val systemPrompt = selection.assistant.stringValue("systemPrompt")?.takeIf { it.isNotBlank() }

        val outboundClient = httpClientWithProxy(httpClient, selection.proxy)

        val requestPayload = JsonObject(
            buildMap {
                put(
                    "contents",
                    JsonArray(messages.filter { it.role != "system" }.map { message ->
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive(if (message.role == "assistant") "model" else "user"),
                                "parts" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf("text" to JsonPrimitive(message.content))
                                        )
                                    )
                                ),
                            )
                        )
                    })
                )
                systemPrompt?.let {
                    put(
                        "systemInstruction",
                        JsonObject(
                            mapOf(
                                "parts" to JsonArray(
                                    listOf(JsonObject(mapOf("text" to JsonPrimitive(it))))
                                )
                            )
                        )
                    )
                }

                val generationConfig = JsonObject(
                    buildMap {
                        selection.assistant.doubleValue("temperature")?.let { put("temperature", JsonPrimitive(it)) }
                        selection.assistant.doubleValue("topP")?.let { put("topP", JsonPrimitive(it)) }
                        selection.assistant.intValue("maxTokens")?.let { put("maxOutputTokens", JsonPrimitive(it)) }
                    }
                )
                if (generationConfig.isNotEmpty()) {
                    put("generationConfig", generationConfig)
                }
                selection.customBodies.forEach { (key, value) -> put(key, value) }
            }
        )

        val response = postJson(
            url = "${baseUrl.trimEnd('/')}/models/${encodedModelId}:generateContent?key=${selection.apiKey}",
            headers = buildMap {
                put("Content-Type", "application/json")
                selection.customHeaders.forEach { (name, value) -> put(name, value) }
            },
            body = requestPayload,
            client = outboundClient,
        )

        val content = response.arrayValue("candidates")
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.objectValue("content")
            ?.arrayValue("parts")
            ?.mapNotNull { (it as? JsonObject)?.stringValue("text") }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()

        return AssistantGenerationResult(
            parts = buildParts(content = content, reasoning = null),
            modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
            usage = response["usageMetadata"],
        )
    }

    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
        client: HttpClient = httpClient,
    ): JsonObject {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.encodeToString(JsonObject.serializer(), body)))

        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val response = withContext(Dispatchers.IO) {
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            val raw = response.body().take(400)
            throw IllegalStateException("Provider request failed (${response.statusCode()}): $raw")
        }

        return (AppJson.parseToJsonElement(response.body()) as? JsonObject)
            ?: throw IllegalStateException("Provider returned invalid JSON object")
    }

    private fun extractOpenAiContentParts(message: JsonObject): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()

        val contentElement = message["content"]
        when (contentElement) {
            is JsonPrimitive -> appendTextOrImageParts(parts, contentElement.content)
            is JsonArray -> contentElement.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                appendOpenAiContentItemParts(parts, item)
            }
            else -> Unit
        }

        val messageB64 = message.stringValue("b64_json")?.trim().orEmpty()
        if (messageB64.isNotBlank()) {
            parts += imagePart("data:image/png;base64,$messageB64")
        }

        return parts
    }

    private fun appendOpenAiContentItemParts(target: MutableList<JsonObject>, item: JsonObject) {
        val type = item.stringValue("type")?.trim()?.lowercase().orEmpty()

        val b64 = item.stringValue("b64_json")
            ?: item.stringValue("image_base64")
            ?: item.objectValue("image")?.stringValue("b64_json")

        if (!b64.isNullOrBlank()) {
            target += imagePart("data:image/png;base64,${b64.trim()}")
            return
        }

        if (type == "image_url" || type == "output_image" || type == "image") {
            val url = item.objectValue("image_url")?.stringValue("url")
                ?: item.stringValue("url")
                ?: item.objectValue("url")?.stringValue("url")
            if (!url.isNullOrBlank()) {
                target += imagePart(url.trim())
                return
            }
        }

        val text = when (type) {
            "text", "output_text" -> item.stringValue("text")
                ?: item.objectValue("text")?.stringValue("value")
                ?: item.stringValue("content")

            else -> item.stringValue("content")
                ?: item.stringValue("text")
                ?: item.objectValue("text")?.stringValue("value")
        }

        if (!text.isNullOrBlank()) {
            appendTextOrImageParts(target, text)
        }
    }

    private fun appendTextOrImageParts(target: MutableList<JsonObject>, raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return

        val compact = text.replace(Regex("\\s+"), "")
        if (compact.startsWith("data:image/", ignoreCase = true) && compact.contains(";base64,")) {
            target += imagePart(compact)
            return
        }

        val markdownImageMatches = MARKDOWN_IMAGE_REGEX.findAll(text).toList()
        if (markdownImageMatches.isNotEmpty()) {
            var cursor = 0
            markdownImageMatches.forEach { match ->
                val before = text.substring(cursor, match.range.first).trim()
                if (before.isNotBlank()) {
                    target += textPart(before)
                }

                val imageUrl = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (imageUrl.isNotBlank()) {
                    target += imagePart(normalizeImageUrl(imageUrl))
                }

                cursor = match.range.last + 1
            }

            val after = text.substring(cursor).trim()
            if (after.isNotBlank()) {
                target += textPart(after)
            }
            return
        }

        val inlineDataImageMatches = INLINE_DATA_IMAGE_REGEX.findAll(text).toList()
        if (inlineDataImageMatches.isNotEmpty()) {
            var cursor = 0
            inlineDataImageMatches.forEach { match ->
                val before = text.substring(cursor, match.range.first).trim()
                if (before.isNotBlank()) {
                    target += textPart(before)
                }

                val imageUrl = normalizeImageUrl(match.value)
                target += imagePart(imageUrl)
                cursor = match.range.last + 1
            }

            val after = text.substring(cursor).trim()
            if (after.isNotBlank()) {
                target += textPart(after)
            }
            return
        }

        if (compact.length >= 256 && compact.length % 4 == 0 && compact.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            val bytes = runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
            val mime = bytes?.let { detectBase64ImageMime(it) }
            if (bytes != null && !mime.isNullOrBlank()) {
                target += imagePart("data:$mime;base64,$compact")
                return
            }
        }

        target += textPart(text)
    }

    private fun normalizeImageUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("data:image/", ignoreCase = true)) {
            return trimmed
        }

        val commaIndex = trimmed.indexOf(',')
        if (commaIndex <= 0) {
            return trimmed
        }

        val head = trimmed.substring(0, commaIndex + 1)
        val payload = trimmed.substring(commaIndex + 1).replace(Regex("\\s+"), "")
        return head + payload
    }

    private fun detectBase64ImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 6) {
            val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (header == "GIF87a" || header == "GIF89a") {
                return "image/gif"
            }
        }
        if (bytes.size >= 12) {
            val riff = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val webp = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") {
                return "image/webp"
            }
        }
        if (bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) {
            return "image/bmp"
        }
        return null
    }

    private fun buildParts(content: String, reasoning: String?): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()
        if (!reasoning.isNullOrBlank()) {
            parts += reasoningPart(reasoning)
        }

        val text = content.trim()
        if (text.isBlank()) {
            parts += textPart("Model returned empty response")
        } else {
            appendTextOrImageParts(parts, text)
        }

        if (parts.isEmpty()) {
            parts += textPart("Model returned empty response")
        }
        return parts
    }

    private fun reasoningPart(reasoning: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("reasoning"),
            "reasoning" to JsonPrimitive(reasoning),
        )
    )

    private fun extractOpenAiToolParts(message: JsonObject, availableTools: List<AvailableTool>): List<JsonObject> {
        val toolCalls = message.arrayValue("tool_calls")
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        if (toolCalls.isEmpty()) return emptyList()

        val approvalMap = availableTools.associate { it.name to it.needsApproval }
        return toolCalls.mapNotNull { toolCall ->
            val callId = toolCall.stringValue("id")?.takeIf { it.isNotBlank() } ?: randomId()
            val function = toolCall.objectValue("function") ?: return@mapNotNull null
            val toolName = function.stringValue("name")?.trim().orEmpty()
            if (toolName.isBlank()) return@mapNotNull null
            val input = function.stringValue("arguments")?.takeIf { it.isNotBlank() } ?: "{}"
            val approvalType = if (approvalMap[toolName] == true) "pending" else "auto"

            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool"),
                    "toolCallId" to JsonPrimitive(callId),
                    "toolName" to JsonPrimitive(toolName),
                    "input" to JsonPrimitive(input),
                    "output" to JsonArray(emptyList()),
                    "approvalState" to JsonObject(mapOf("type" to JsonPrimitive(approvalType))),
                )
            )
        }
    }

    private fun buildAvailableTools(settings: JsonObject, selection: Selection): List<AvailableTool> {
        val byName = linkedMapOf<String, AvailableTool>()

        fun add(tool: AvailableTool) {
            if (!byName.containsKey(tool.name)) {
                byName[tool.name] = tool
            }
        }

        val modelTools = selection.modelRecord.arrayValue("tools")
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        modelTools.forEach { item ->
            when (item.stringValue("type")?.trim()?.lowercase()) {
                "search" -> add(searchWebTool())
                "url_context", "url-context", "urlcontext" -> add(scrapeWebTool())
            }
        }

        if (settings.booleanValue("enableWebSearch") == true) {
            add(searchWebTool())
            add(scrapeWebTool())
        }

        if (selection.assistant.booleanValue("enableMemory") == true) {
            add(memoryTool(needsApproval = false))
        }

        val localTools = selection.assistant.arrayValue("localTools")
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.lowercase() }
            .orEmpty()
        if ("time_info" in localTools) {
            add(timeInfoTool())
        }
        if ("clipboard" in localTools) {
            add(clipboardTool(needsApproval = true))
        }

        buildMcpTools(settings = settings, assistant = selection.assistant).forEach(::add)

        return byName.values.toList()
    }

    private fun buildMcpTools(settings: JsonObject, assistant: JsonObject): List<AvailableTool> {
        val assistantServerIds = assistant.arrayValue("mcpServers")
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        if (assistantServerIds.isEmpty()) return emptyList()

        val servers = settings.arrayValue("mcpServers")
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

        val out = mutableListOf<AvailableTool>()
        servers.forEach { server ->
            val id = server.stringValue("id")?.trim().orEmpty()
            if (id.isBlank() || id !in assistantServerIds) return@forEach

            val common = server.objectValue("commonOptions") ?: JsonObject(emptyMap())
            if (common.booleanValue("enable") != true) return@forEach

            val tools = common.arrayValue("tools")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()

            tools.forEach { tool ->
                if (tool.booleanValue("enable") == false) return@forEach
                val name = tool.stringValue("name")?.trim().orEmpty()
                if (name.isBlank()) return@forEach

                val parameters = normalizeJsonSchema(tool.objectValue("inputSchema"))

                out += AvailableTool(
                    name = "mcp__" + name,
                    description = tool.stringValue("description")?.trim().orEmpty(),
                    parameters = parameters,
                    needsApproval = tool.booleanValue("needsApproval") == true,
                )
            }
        }

        return out
    }

    private fun normalizeJsonSchema(schema: JsonObject?): JsonObject {
        if (schema == null || schema.isEmpty()) {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )
            )
        }
        if (schema["type"] != null) return schema

        val properties = schema.objectValue("properties") ?: JsonObject(emptyMap())
        val required = schema.arrayValue("required") ?: JsonArray(emptyList())

        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to properties,
                "required" to required,
            )
        )
    }

    private fun searchWebTool(): AvailableTool = AvailableTool(
        name = "search_web",
        description = "Search the web and return concise answer with result items.",
        parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "query" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Search query"),
                            )
                        ),
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("query"))),
            )
        ),
        needsApproval = false,
    )

    private fun scrapeWebTool(): AvailableTool = AvailableTool(
        name = "scrape_web",
        description = "Scrape one or more URLs and return extracted page text.",
        parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "url" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Target URL"),
                            )
                        ),
                        "urls" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "description" to JsonPrimitive("Optional URL list"),
                            )
                        ),
                    )
                ),
            )
        ),
        needsApproval = false,
    )

    private fun timeInfoTool(): AvailableTool = AvailableTool(
        name = "get_time_info",
        description = "Get current local date, time, timezone, and timestamp.",
        parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
            )
        ),
        needsApproval = false,
    )

    private fun clipboardTool(needsApproval: Boolean): AvailableTool = AvailableTool(
        name = "clipboard_tool",
        description = "Read or write plain text from clipboard using action read/write.",
        parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "action" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(listOf(JsonPrimitive("read"), JsonPrimitive("write"))),
                            )
                        ),
                        "text" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                            )
                        ),
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("action"))),
            )
        ),
        needsApproval = needsApproval,
    )

    private fun memoryTool(needsApproval: Boolean): AvailableTool = AvailableTool(
        name = "memory_tool",
        description = "Manage long-term memory records with actions create/edit/delete.",
        parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "action" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(
                                    listOf(JsonPrimitive("create"), JsonPrimitive("edit"), JsonPrimitive("delete"))
                                ),
                            )
                        ),
                        "id" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                        "content" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("action"))),
            )
        ),
        needsApproval = needsApproval,
    )

    private fun buildOpenAiRequestMessages(conversation: ConversationRecord, assistant: JsonObject): List<JsonObject> {
        val allMessages = conversation.messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull()
        }

        val windowSize = assistant.intValue("contextMessageSize") ?: 0
        val scoped = if (windowSize > 0 && allMessages.size > windowSize) {
            allMessages.takeLast(windowSize)
        } else {
            allMessages
        }

        val systemPrompt = assistant.stringValue("systemPrompt")?.trim().orEmpty()
        val turns = mutableListOf<JsonObject>()
        if (systemPrompt.isNotBlank()) {
            turns += JsonObject(
                mapOf(
                    "role" to JsonPrimitive("system"),
                    "content" to JsonPrimitive(systemPrompt),
                )
            )
        }

        scoped.forEach { message ->
            val role = when (message.role.trim().lowercase()) {
                "assistant" -> "assistant"
                "system" -> "system"
                else -> "user"
            }

            if (role != "assistant") {
                val content = message.parts.toOpenAiUserContent(role = role, assistant = assistant)
                if (content != null) {
                    turns += JsonObject(
                        mapOf(
                            "role" to JsonPrimitive(role),
                            "content" to content,
                        )
                    )
                }
                return@forEach
            }

            val toolParts = message.parts.filter { it.stringValue("type")?.lowercase() == "tool" }
            val toolCalls = toolParts.mapNotNull { toolPart ->
                val toolName = toolPart.stringValue("toolName")?.trim().orEmpty()
                if (toolName.isBlank()) return@mapNotNull null
                val callId = toolPart.stringValue("toolCallId")?.takeIf { it.isNotBlank() } ?: randomId()
                val input = toolPart.stringValue("input")?.takeIf { it.isNotBlank() } ?: "{}"

                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(callId),
                        "type" to JsonPrimitive("function"),
                        "function" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(toolName),
                                "arguments" to JsonPrimitive(input),
                            )
                        ),
                    )
                )
            }

            val assistantContent = message.parts.toOpenAiAssistantContent(role = "assistant", assistant = assistant).trim()
            if (assistantContent.isBlank() && toolCalls.isEmpty()) return@forEach

            val assistantMap = linkedMapOf<String, JsonElement>(
                "role" to JsonPrimitive("assistant"),
                "content" to JsonPrimitive(assistantContent),
            )
            if (toolCalls.isNotEmpty()) {
                assistantMap["tool_calls"] = JsonArray(toolCalls)
            }
            turns += JsonObject(assistantMap)

            // Add tool result messages in the same order as tool parts.
            toolParts.forEach { toolPart ->
                val callId = toolPart.stringValue("toolCallId")?.takeIf { it.isNotBlank() } ?: return@forEach
                val outputText = toolPart.extractToolOutputText()
                if (outputText.isBlank()) return@forEach

                turns += JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("tool"),
                        "tool_call_id" to JsonPrimitive(callId),
                        "content" to JsonPrimitive(outputText),
                    )
                )
            }
        }

        return turns
    }

    private fun List<JsonObject>.toOpenAiUserContent(role: String, assistant: JsonObject): JsonElement? {
        val hasImage = any { it.stringValue("type")?.lowercase() == "image" }
        if (!hasImage) {
            val plainText = renderMessageTemplate(toPromptText().trim(), role = role, assistant = assistant).trim()
            return if (plainText.isBlank()) null else JsonPrimitive(plainText)
        }

        val content = mutableListOf<JsonObject>()
        forEach { part ->
            when (part.stringValue("type")?.lowercase()) {
                "text" -> {
                    val text = part.stringValue("text")?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        val rendered = renderMessageTemplate(text, role = role, assistant = assistant).trim()
                        if (rendered.isNotBlank()) {
                            content += openAiTextContent(rendered)
                        }
                    }
                }

                "image" -> {
                    val resolved = resolveImageUrlForOpenAi(part.stringValue("url").orEmpty())
                    if (resolved != null) {
                        content += openAiImageContent(resolved)
                    } else {
                        content += openAiTextContent("[image]")
                    }
                }

                "document" -> {
                    val name = part.stringValue("fileName") ?: "document"
                    content += openAiTextContent("[document: $name]")
                }

                "audio" -> content += openAiTextContent("[audio]")
                "video" -> content += openAiTextContent("[video]")

                "tool" -> {
                    val toolName = part.stringValue("toolName") ?: "tool"
                    val input = part.stringValue("input").orEmpty()
                    val outputText = part.arrayValue("output")
                        ?.mapNotNull { (it as? JsonObject)?.stringValue("text") }
                        ?.filter { it.isNotBlank() }
                        ?.joinToString("\n")
                        .orEmpty()
                    if (input.isNotBlank()) {
                        content += openAiTextContent("[tool: $toolName] $input")
                    } else {
                        content += openAiTextContent("[tool: $toolName]")
                    }
                    if (outputText.isNotBlank()) {
                        content += openAiTextContent("[tool_result: $toolName] $outputText")
                    }
                }

                "reasoning" -> Unit
                else -> Unit
            }
        }

        return if (content.isEmpty()) null else JsonArray(content)
    }

    private fun openAiTextContent(text: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("text"),
            "text" to JsonPrimitive(text),
        )
    )

    private fun openAiImageContent(url: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("image_url"),
            "image_url" to JsonObject(
                mapOf(
                    "url" to JsonPrimitive(url),
                )
            ),
        )
    )

    private fun resolveImageUrlForOpenAi(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("data:", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        val relativePath = extractRelativeFilePath(trimmed) ?: return null
        val normalizedDataDir = dataDir.toAbsolutePath().normalize()
        val filePath = normalizedDataDir.resolve(relativePath).normalize()
        if (!filePath.startsWith(normalizedDataDir) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
            return null
        }

        val size = runCatching { Files.size(filePath) }.getOrNull() ?: return null
        if (size <= 0L || size > inlineImageMaxBytes) {
            return null
        }

        val bytes = runCatching { Files.readAllBytes(filePath) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val mime = detectImageMimeType(filePath, relativePath)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "data:$mime;base64,$encoded"
    }

    private fun extractRelativeFilePath(rawUrl: String): String? {
        val noFragment = rawUrl.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')

        val resolved = when {
            noQuery.startsWith("file://", ignoreCase = true) -> {
                val marker = "/files/"
                val index = noQuery.indexOf(marker, ignoreCase = true)
                if (index < 0) return null
                noQuery.substring(index + marker.length)
            }

            noQuery.startsWith("/api/files/path/", ignoreCase = true) -> noQuery.removePrefix("/api/files/path/")
            noQuery.startsWith("api/files/path/", ignoreCase = true) -> noQuery.removePrefix("api/files/path/")
            else -> noQuery.trimStart('/')
        }

        val decoded = runCatching { URLDecoder.decode(resolved, StandardCharsets.UTF_8) }
            .getOrDefault(resolved)
            .replace('\\', '/')
            .trimStart('/')

        if (decoded.isBlank() || decoded.contains("..")) {
            return null
        }

        return decoded
    }

    private fun detectImageMimeType(path: Path, relativePath: String): String {
        val fromPath = runCatching { Files.probeContentType(path) }.getOrNull()
            ?.trim()
            ?.takeIf { it.startsWith("image/") }
        if (fromPath != null) return fromPath

        return when (relativePath.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "image/jpeg"
        }
    }

    private fun List<JsonObject>.toOpenAiAssistantContent(role: String, assistant: JsonObject): String {
        val pieces = mutableListOf<String>()
        forEach { part ->
            when (part.stringValue("type")?.lowercase()) {
                "text" -> part.stringValue("text")?.takeIf { it.isNotBlank() }?.let(pieces::add)
                "document" -> {
                    val name = part.stringValue("fileName") ?: "document"
                    pieces += "[document: $name]"
                }

                "image" -> pieces += "[image]"
                "audio" -> pieces += "[audio]"
                "video" -> pieces += "[video]"

                // Do not embed tool annotations here; OpenAI expects structured tool calls and tool results.
                "tool", "reasoning" -> Unit
                else -> Unit
            }
        }
        return pieces.joinToString("\n")
    }

    private fun JsonObject.extractToolOutputText(): String {
        return this.arrayValue("output")
            ?.mapNotNull { (it as? JsonObject)?.stringValue("text") }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()
    }
    private fun buildChatMessages(conversation: ConversationRecord, assistant: JsonObject): List<ChatMessage> {
        val allMessages = conversation.messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull()
        }

        val windowSize = assistant.intValue("contextMessageSize") ?: 0
        val scoped = if (windowSize > 0 && allMessages.size > windowSize) {
            allMessages.takeLast(windowSize)
        } else {
            allMessages
        }

        val systemPrompt = assistant.stringValue("systemPrompt")?.trim().orEmpty()
        val turns = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            turns += ChatMessage(role = "system", content = systemPrompt)
        }

        scoped.forEach { message ->
            val normalizedRole = when (message.role.trim().lowercase()) {
                "assistant" -> "assistant"
                "system" -> "system"
                "tool" -> "user"
                else -> "user"
            }
            val text = renderMessageTemplate(message.parts.toPromptText().trim(), role = normalizedRole, assistant = assistant).trim()
            if (text.isBlank()) return@forEach
            turns += ChatMessage(role = normalizedRole, content = text)
        }

        return turns
    }

    private fun buildTitleInput(conversation: ConversationRecord): String {
        val lines = conversation.messageNodes
            .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull() }
            .takeLast(12)
            .mapNotNull { message ->
                val text = message.parts.toPromptText().trim()
                if (text.isBlank()) return@mapNotNull null
                val role = message.role.trim().lowercase().ifBlank { "user" }
                "${role}: ${text}"
            }
        return lines.joinToString("\n")
    }

    private fun List<JsonObject>.toPromptText(): String {
        val pieces = mutableListOf<String>()
        forEach { part ->
            when (part.stringValue("type")?.lowercase()) {
                "text" -> part.stringValue("text")?.takeIf { it.isNotBlank() }?.let(pieces::add)
                "document" -> {
                    val name = part.stringValue("fileName") ?: "document"
                    pieces += "[document: $name]"
                }

                "image" -> pieces += "[image]"
                "audio" -> pieces += "[audio]"
                "video" -> pieces += "[video]"
                "tool" -> {
                    val toolName = part.stringValue("toolName") ?: "tool"
                    val input = part.stringValue("input").orEmpty()
                    val outputText = part.arrayValue("output")
                        ?.mapNotNull { (it as? JsonObject)?.stringValue("text") }
                        ?.filter { it.isNotBlank() }
                        ?.joinToString("\n")
                        .orEmpty()
                    if (input.isNotBlank()) {
                        pieces += "[tool: $toolName] $input"
                    } else {
                        pieces += "[tool: $toolName]"
                    }
                    if (outputText.isNotBlank()) {
                        pieces += "[tool_result: $toolName] $outputText"
                    }
                }

                else -> Unit
            }
        }
        return pieces.joinToString("\n")
    }

    private fun renderMessageTemplate(source: String, role: String, assistant: JsonObject): String {
        if (source.isBlank()) return source

        val template = assistant.stringValue("messageTemplate") ?: "{{ message }}"
        if (template.isBlank()) return ""

        val now = Instant.now().atZone(ZoneId.systemDefault())
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(now)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(now)

        return template
            .replaceTemplateToken("message", source)
            .replaceTemplateToken("role", role)
            .replaceTemplateToken("time", time)
            .replaceTemplateToken("date", date)
    }

    private fun String.replaceTemplateToken(name: String, value: String): String {
        val escaped = Regex.escapeReplacement(value)
        val mustache = Regex("\\{\\{\\s*" + Regex.escape(name) + "\\s*\\}\\}", RegexOption.IGNORE_CASE)
        val legacy = Regex("\\{\\s*" + Regex.escape(name) + "\\s*\\}", RegexOption.IGNORE_CASE)
        return this.replace(mustache, escaped).replace(legacy, escaped)
    }

    
    private fun applyNamedPlaceholders(template: String, values: Map<String, String>): String {
        var result = template
        values.forEach { (key, value) ->
            val escaped = Regex.escapeReplacement(value)
            val token = Regex("\\{\\s*" + Regex.escape(key) + "\\s*\\}", RegexOption.IGNORE_CASE)
            result = result.replace(token, escaped)
        }
        return result
    }

    private fun imagePart(url: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("image"),
            "url" to JsonPrimitive(url),
        )
    )

    private fun textPart(text: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("text"),
            "text" to JsonPrimitive(text),
        )
    )

    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    private data class Selection(
        val providerType: String,
        val provider: JsonObject,
        val modelRecord: JsonObject,
        val assistant: JsonObject,
        val apiKey: String,
        val customHeaders: List<Pair<String, String>>,
        val customBodies: List<Pair<String, JsonElement>>,
        val proxy: ProviderProxyConfig?,
    )

    private data class AvailableTool(
        val name: String,
        val description: String,
        val parameters: JsonObject,
        val needsApproval: Boolean,
    ) {
        fun toOpenAiToolJson(): JsonObject = JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "description" to JsonPrimitive(description),
                        "parameters" to parameters,
                    )
                ),
            )
        )
    }

    private fun selectModel(settings: JsonObject, assistantId: String, modelRefOverride: String? = null): Selection {
        val assistants = settings.arrayValue("assistants")?.mapNotNull { it as? JsonObject } ?: emptyList()
        val assistant = assistants.firstOrNull { it.stringValue("id") == assistantId } ?: JsonObject(emptyMap())

        val modelRef = modelRefOverride
            ?.takeIf { it.isNotBlank() }
            ?: assistant.stringValue("chatModelId")
                ?.takeIf { it.isNotBlank() }
            ?: settings.stringValue("chatModelId")
            ?: "auto"

        val providers = settings.arrayValue("providers")
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it.booleanValue("enabled") != false }
            ?: emptyList()

        val candidates = providers.flatMap { provider ->
            val models = provider.arrayValue("models")?.mapNotNull { it as? JsonObject } ?: emptyList()
            models.map { model -> provider to model }
        }

        val picked = if (modelRef == "auto") {
            candidates.firstOrNull { (provider, model) ->
                val providerType = provider.stringValue("type")?.lowercase() ?: "openai"
                val apiKey = provider.stringValue("apiKey").orEmpty()
                apiKey.isNotBlank() && providerType in setOf("openai", "claude", "google") &&
                    (model.stringValue("modelId")?.lowercase() != "auto")
            } ?: candidates.firstOrNull { (provider, _) -> provider.stringValue("apiKey").orEmpty().isNotBlank() }
        } else {
            candidates.firstOrNull { (_, model) ->
                model.stringValue("id") == modelRef || model.stringValue("modelId") == modelRef
            } ?: candidates.firstOrNull { (provider, _) -> provider.stringValue("apiKey").orEmpty().isNotBlank() }
        } ?: throw IllegalStateException("No available model/provider configured")

        val provider = picked.first
        val model = picked.second
        val providerType = provider.stringValue("type")?.lowercase() ?: "openai"
        val apiKey = provider.stringValue("apiKey").orEmpty()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Provider API key is empty")
        }

        val customHeaders = parseHeaders(assistant) + parseHeaders(model)
        val customBodies = parseBodies(assistant) + parseBodies(model)
        val proxy = parseProviderProxy(provider)

        return Selection(
            providerType = providerType,
            provider = provider,
            modelRecord = model,
            assistant = assistant,
            apiKey = apiKey,
            customHeaders = customHeaders,
            customBodies = customBodies,
            proxy = proxy,
        )
    }

    private fun parseHeaders(json: JsonObject): List<Pair<String, String>> {
        return json.arrayValue("customHeaders")
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { header ->
                val name = header.stringValue("name")?.trim().orEmpty()
                val value = header.stringValue("value")?.trim().orEmpty()
                if (name.isBlank() || value.isBlank()) null else name to value
            }
            ?: emptyList()
    }

    private fun parseBodies(json: JsonObject): List<Pair<String, JsonElement>> {
        return json.arrayValue("customBodies")
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { body ->
                val key = body.stringValue("key")?.trim().orEmpty()
                val value = body["value"]
                if (key.isBlank() || value == null) null else key to value
            }
            ?: emptyList()
    }

    private fun JsonObject.mergeCustomBodies(customBodies: List<Pair<String, JsonElement>>): JsonObject {
        if (customBodies.isEmpty()) return this
        return JsonObject(this.toMutableMap().apply {
            customBodies.forEach { (key, value) ->
                this[key] = value
            }
        })
    }

    private fun JsonObject.doubleValue(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.toDoubleOrNull()
    }

    private fun joinUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val suffix = if (path.startsWith('/')) path else "/$path"
        return base + suffix
    }
}
private const val DEFAULT_INLINE_IMAGE_MAX_BYTES = 8L * 1024L * 1024L
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)")
private val INLINE_DATA_IMAGE_REGEX = Regex("data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=\\s]+")

private val DEFAULT_TITLE_PROMPT = """
    I will give you some dialogue content in the <content> block.
    You need to summarize the conversation between user and assistant into a short title.
    1. The title language should be consistent with the user's primary language
    2. Do not use punctuation or other special symbols
    3. Reply directly with the title
    4. Summarize using {locale} language
    5. The title should not exceed 10 characters

    <content>
    {content}
    </content>
""".trimIndent()

private fun defaultDataDir(): Path {
    val fromEnv = System.getenv("DATA_DIR")?.trim().orEmpty()
    if (fromEnv.isNotBlank()) {
        return Paths.get(fromEnv).toAbsolutePath().normalize()
    }
    return Paths.get("data").toAbsolutePath().normalize()
}
















