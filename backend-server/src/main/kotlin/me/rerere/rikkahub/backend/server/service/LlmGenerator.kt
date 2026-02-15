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
import me.rerere.rikkahub.backend.core.util.stringValue
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class AssistantGenerationResult(
    val parts: List<JsonObject>,
    val modelId: String?,
    val usage: JsonElement?,
)

interface LlmGenerator {
    suspend fun generateReply(settings: JsonObject, conversation: ConversationRecord): AssistantGenerationResult
    suspend fun generateTitle(settings: JsonObject, conversation: ConversationRecord): String?
}

class PortableLlmGenerator(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : LlmGenerator {
    override suspend fun generateReply(settings: JsonObject, conversation: ConversationRecord): AssistantGenerationResult {
        val selection = selectModel(settings = settings, assistantId = conversation.assistantId)
        val messages = buildChatMessages(conversation = conversation, assistant = selection.assistant)
        if (messages.isEmpty()) {
            return AssistantGenerationResult(
                parts = listOf(textPart("Message received")),
                modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
                usage = null,
            )
        }

        return when (selection.providerType) {
            "openai" -> generateViaOpenAi(selection, messages)
            "claude" -> generateViaClaude(selection, messages)
            "google" -> generateViaGoogle(selection, messages)
            else -> throw IllegalStateException("Unsupported provider type: ${selection.providerType}")
        }
    }

    override suspend fun generateTitle(settings: JsonObject, conversation: ConversationRecord): String? {
        val selection = selectModel(settings = settings, assistantId = conversation.assistantId)
        val summaryInput = buildTitleInput(conversation)
        if (summaryInput.isBlank()) return null

        val prompt = "Generate a concise conversation title (max 12 words). Reply with title only."
        val messages = listOf(
            ChatMessage(role = "system", content = prompt),
            ChatMessage(role = "user", content = summaryInput),
        )

        val result = when (selection.providerType) {
            "openai" -> generateViaOpenAi(selection, messages)
            "claude" -> generateViaClaude(selection, messages)
            "google" -> generateViaGoogle(selection, messages)
            else -> return null
        }

        val raw = result.parts.firstOrNull { it.stringValue("type") == "text" }?.stringValue("text")?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '`')
            .take(96)
    }

    private suspend fun generateViaOpenAi(
        selection: Selection,
        messages: List<ChatMessage>,
    ): AssistantGenerationResult {
        val requestPayload = JsonObject(
            buildMap {
                put("model", JsonPrimitive(selection.modelRecord.stringValue("modelId") ?: "auto"))
                put(
                    "messages",
                    JsonArray(messages.map { message ->
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive(message.role),
                                "content" to JsonPrimitive(message.content),
                            )
                        )
                    })
                )
                selection.assistant.doubleValue("temperature")?.let { put("temperature", JsonPrimitive(it)) }
                selection.assistant.doubleValue("topP")?.let { put("top_p", JsonPrimitive(it)) }
                selection.assistant.intValue("maxTokens")?.let { put("max_tokens", JsonPrimitive(it)) }
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
        )

        val choice = response.arrayValue("choices")?.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Provider returned no choices")
        val message = choice.objectValue("message") ?: JsonObject(emptyMap())
        val content = extractOpenAiContent(message)
        val reasoning = message.stringValue("reasoning_content") ?: message.stringValue("reasoning")

        return AssistantGenerationResult(
            parts = buildParts(content = content, reasoning = reasoning),
            modelId = selection.modelRecord.stringValue("id") ?: selection.modelRecord.stringValue("modelId"),
            usage = response["usage"],
        )
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

    private suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.encodeToString(JsonObject.serializer(), body)))

        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val response = withContext(Dispatchers.IO) {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            val raw = response.body().take(400)
            throw IllegalStateException("Provider request failed (${response.statusCode()}): $raw")
        }

        return (AppJson.parseToJsonElement(response.body()) as? JsonObject)
            ?: throw IllegalStateException("Provider returned invalid JSON object")
    }

    private fun extractOpenAiContent(message: JsonObject): String {
        val contentElement = message["content"]
        return when (contentElement) {
            is JsonPrimitive -> contentElement.content
            is JsonArray -> contentElement.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val type = obj.stringValue("type")
                when (type) {
                    "text", "output_text" -> obj.stringValue("text")
                    else -> obj.stringValue("content")
                }
            }.filter { it.isNotBlank() }.joinToString("\n")
            else -> ""
        }
    }

    private fun buildParts(content: String, reasoning: String?): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()
        if (!reasoning.isNullOrBlank()) {
            parts += JsonObject(
                mapOf(
                    "type" to JsonPrimitive("reasoning"),
                    "reasoning" to JsonPrimitive(reasoning),
                )
            )
        }
        val text = content.trim()
        parts += textPart(if (text.isBlank()) "Model returned empty response" else text)
        return parts
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
            val text = message.parts.toPromptText().trim()
            if (text.isBlank()) return@forEach

            val normalizedRole = when (message.role.trim().lowercase()) {
                "assistant" -> "assistant"
                "system" -> "system"
                "tool" -> "user"
                else -> "user"
            }
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
                    pieces += "[tool: $toolName] $input"
                }
                else -> Unit
            }
        }
        return pieces.joinToString("\n")
    }

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
    )

    private fun selectModel(settings: JsonObject, assistantId: String): Selection {
        val assistants = settings.arrayValue("assistants")?.mapNotNull { it as? JsonObject } ?: emptyList()
        val assistant = assistants.firstOrNull { it.stringValue("id") == assistantId } ?: JsonObject(emptyMap())

        val modelRef = assistant.stringValue("chatModelId")
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

        return Selection(
            providerType = providerType,
            provider = provider,
            modelRecord = model,
            assistant = assistant,
            apiKey = apiKey,
            customHeaders = customHeaders,
            customBodies = customBodies,
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
