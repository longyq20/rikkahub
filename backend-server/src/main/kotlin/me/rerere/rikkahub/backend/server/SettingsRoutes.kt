package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.FetchProviderModelsRequest
import me.rerere.rikkahub.backend.core.api.FetchProviderModelsResponse
import me.rerere.rikkahub.backend.core.api.NotFoundException
import me.rerere.rikkahub.backend.core.api.ProviderModelFetchDto
import me.rerere.rikkahub.backend.core.api.UpdateAssistantInjectionsRequest
import me.rerere.rikkahub.backend.core.api.UpdateAssistantMcpServersRequest
import me.rerere.rikkahub.backend.core.api.UpdateAssistantModelRequest
import me.rerere.rikkahub.backend.core.api.UpdateAssistantRequest
import me.rerere.rikkahub.backend.core.api.UpdateAssistantThinkingBudgetRequest
import me.rerere.rikkahub.backend.core.api.UpdateBuiltInToolRequest
import me.rerere.rikkahub.backend.core.api.UpdateFavoriteModelsRequest
import me.rerere.rikkahub.backend.core.api.UpdateSearchEnabledRequest
import me.rerere.rikkahub.backend.core.api.UpdateSearchServiceRequest
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.util.arrayValue
import me.rerere.rikkahub.backend.core.util.requireUuid
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.core.util.withArray
import me.rerere.rikkahub.backend.core.util.withBoolean
import me.rerere.rikkahub.backend.core.util.withInt
import me.rerere.rikkahub.backend.core.util.withString
import me.rerere.rikkahub.backend.server.service.httpClientWithProxy
import me.rerere.rikkahub.backend.server.service.parseProviderProxy
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

private val providerModelHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build()

fun Route.registerSettingsRoutes(settingsRepository: SettingsJsonRepository) {
    route("/settings") {
        post("/assistant") {
            val request = call.receive<UpdateAssistantRequest>()
            val assistantId = request.assistantId.requireUuid("assistantId")
            settingsRepository.update { it.withString("assistantId", assistantId) }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/model") {
            val request = call.receive<UpdateAssistantModelRequest>()
            val assistantId = request.assistantId.requireUuid("assistantId")
            val modelId = request.modelId.trim()
            if (modelId.isBlank()) {
                throw BadRequestException("modelId is required")
            }

            settingsRepository.update { settings ->
                val model = findModelById(settings, modelId)
                    ?: throw NotFoundException("Model not found")
                if (!isChatModel(model)) {
                    throw BadRequestException("modelId must support chat input")
                }
                mutateAssistant(settings, assistantId) { assistant -> assistant.withString("chatModelId", modelId) }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/thinking-budget") {
            val request = call.receive<UpdateAssistantThinkingBudgetRequest>()
            val assistantId = request.assistantId.requireUuid("assistantId")
            val thinkingBudget = request.thinkingBudget
            settingsRepository.update { settings ->
                mutateAssistant(settings, assistantId) { assistant ->
                    if (thinkingBudget == null) {
                        JsonObject(assistant.toMutableMap().apply { remove("thinkingBudget") })
                    } else {
                        assistant.withInt("thinkingBudget", thinkingBudget)
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/mcp") {
            val request = call.receive<UpdateAssistantMcpServersRequest>()
            val assistantId = request.assistantId.requireUuid("assistantId")

            settingsRepository.update { settings ->
                val validServerIds = settingsMcpServerIds(settings)
                val requestedServerIds = request.mcpServerIds.toSet()
                if (!validServerIds.containsAll(requestedServerIds)) {
                    throw BadRequestException("mcpServerIds contains unknown server id")
                }

                mutateAssistant(settings, assistantId) { assistant ->
                    assistant.withArray("mcpServers", request.mcpServerIds.map { JsonPrimitive(it) })
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/injections") {
            val request = call.receive<UpdateAssistantInjectionsRequest>()
            val assistantId = request.assistantId.requireUuid("assistantId")

            settingsRepository.update { settings ->
                val validModeInjectionIds = settingsModeInjectionIds(settings)
                val requestedModeInjectionIds = request.modeInjectionIds.toSet()
                if (!validModeInjectionIds.containsAll(requestedModeInjectionIds)) {
                    throw BadRequestException("modeInjectionIds contains unknown injection id")
                }

                val validLorebookIds = settingsLorebookIds(settings)
                val requestedLorebookIds = request.lorebookIds.toSet()
                if (!validLorebookIds.containsAll(requestedLorebookIds)) {
                    throw BadRequestException("lorebookIds contains unknown lorebook id")
                }

                mutateAssistant(settings, assistantId) { assistant ->
                    assistant
                        .withArray("modeInjectionIds", request.modeInjectionIds.map { JsonPrimitive(it) })
                        .withArray("lorebookIds", request.lorebookIds.map { JsonPrimitive(it) })
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/enabled") {
            val request = call.receive<UpdateSearchEnabledRequest>()
            settingsRepository.update { it.withBoolean("enableWebSearch", request.enabled) }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/service") {
            val request = call.receive<UpdateSearchServiceRequest>()
            settingsRepository.update { settings ->
                val services = settingsSearchServices(settings)
                if (services.isEmpty()) {
                    throw BadRequestException("No search services configured")
                }
                if (request.index !in services.indices) {
                    throw BadRequestException("search service index out of range")
                }
                settings.withInt("searchServiceSelected", request.index)
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/model/built-in-tool") {
            val request = call.receive<UpdateBuiltInToolRequest>()
            val modelId = request.modelId.trim()
            if (modelId.isBlank()) {
                throw BadRequestException("modelId is required")
            }
            val targetType = normalizeToolType(request.tool)

            settingsRepository.update { settings ->
                val model = findModelById(settings, modelId)
                    ?: throw NotFoundException("Model not found")
                if (!isChatModel(model)) {
                    throw BadRequestException("modelId must support chat input")
                }

                val providers = (settings["providers"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                var found = false
                val updatedProviders = providers.map { provider ->
                    val models = (provider["models"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    val updatedModels = models.map { modelItem ->
                        if ((modelItem.stringValue("id") ?: "") != modelId) {
                            return@map modelItem
                        }
                        found = true
                        val tools = (modelItem["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
                        if (request.enabled) {
                            if (tools.none { (it as? JsonObject)?.stringValue("type") == targetType }) {
                                tools += JsonObject(mapOf("type" to JsonPrimitive(targetType)))
                            }
                        } else {
                            tools.removeAll { (it as? JsonObject)?.stringValue("type") == targetType }
                        }
                        JsonObject(modelItem.toMutableMap().apply { this["tools"] = JsonArray(tools) })
                    }
                    JsonObject(provider.toMutableMap().apply { this["models"] = JsonArray(updatedModels) })
                }
                if (!found) throw NotFoundException("Model not found")
                JsonObject(settings.toMutableMap().apply { this["providers"] = JsonArray(updatedProviders) })
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/favorite-models") {
            val request = call.receive<UpdateFavoriteModelsRequest>()
            settingsRepository.update { it.withArray("favoriteModels", request.modelIds.map { JsonPrimitive(it) }) }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/provider/models/fetch") {
            val request = call.receive<FetchProviderModelsRequest>()
            val providerId = request.providerId.trim()
            if (providerId.isBlank()) {
                throw BadRequestException("providerId is required")
            }

            val settings = settingsRepository.current()
            val providers = settings.arrayValue("providers")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val provider = providers.firstOrNull { it.stringValue("id") == providerId }
                ?: throw NotFoundException("Provider not found")

            val models = fetchProviderModels(provider)
            call.respond(
                HttpStatusCode.OK,
                FetchProviderModelsResponse(
                    providerId = providerId,
                    models = models,
                )
            )
        }

        post("/replace") {
            val value = call.receive<JsonObject>()
            settingsRepository.replaceAll(value)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
        sse("/stream") {
            heartbeat { period = 15.seconds }
            settingsRepository.settingsFlow.collect { settings ->
                send(event = "update", data = AppJson.encodeToString(JsonObject.serializer(), settings))
            }
        }
    }
}

private fun settingsMcpServerIds(settings: JsonObject): Set<String> {
    return (settings["mcpServers"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.stringValue("id") }
        ?.toSet()
        ?: emptySet()
}

private fun settingsModeInjectionIds(settings: JsonObject): Set<String> {
    return (settings["modeInjections"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.stringValue("id") }
        ?.toSet()
        ?: emptySet()
}

private fun settingsLorebookIds(settings: JsonObject): Set<String> {
    return (settings["lorebooks"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.stringValue("id") }
        ?.toSet()
        ?: emptySet()
}

private fun settingsSearchServices(settings: JsonObject): List<JsonObject> {
    return (settings["searchServices"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?: emptyList()
}

private fun findModelById(settings: JsonObject, modelId: String): JsonObject? {
    val providers = (settings["providers"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
    for (provider in providers) {
        val models = (provider["models"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (model in models) {
            if (model.stringValue("id") == modelId) {
                return model
            }
        }
    }
    return null
}

private fun isChatModel(model: JsonObject): Boolean {
    val type = model.stringValue("type")?.trim()?.uppercase().orEmpty()
    if (type == "CHAT") return true
    if (type != "IMAGE") return false

    val inputModalities = model.arrayValue("inputModalities")
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.uppercase() }
        .orEmpty()

    return inputModalities.isEmpty() || inputModalities.contains("TEXT")
}

private fun mutateAssistant(
    settings: JsonObject,
    assistantId: String,
    mutator: (JsonObject) -> JsonObject,
): JsonObject {
    val assistants = (settings["assistants"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    var found = false
    val updatedAssistants = assistants.map { assistant ->
        if ((assistant.stringValue("id") ?: "") == assistantId) {
            found = true
            mutator(assistant)
        } else {
            assistant
        }
    }
    if (!found) throw NotFoundException("Assistant not found")

    return JsonObject(settings.toMutableMap().apply {
        this["assistants"] = JsonArray(updatedAssistants)
        this["assistantId"] = JsonPrimitive(assistantId)
    })
}

private suspend fun fetchProviderModels(provider: JsonObject): List<ProviderModelFetchDto> {
    val type = provider.stringValue("type")?.trim()?.lowercase().orEmpty()
    return when (type) {
        "openai" -> fetchOpenAiModels(provider)
        "claude" -> fetchClaudeModels(provider)
        "google" -> fetchGoogleModels(provider)
        else -> throw BadRequestException("Unsupported provider type: $type")
    }
}

private suspend fun fetchOpenAiModels(provider: JsonObject): List<ProviderModelFetchDto> {
    val baseUrl = provider.stringValue("baseUrl")?.trim()?.trimEnd('/').orEmpty()
    val apiKey = provider.stringValue("apiKey")?.trim().orEmpty()
    if (baseUrl.isBlank() || apiKey.isBlank()) {
        throw BadRequestException("Provider baseUrl/apiKey is required")
    }

    val json = requestJsonObject(
        url = "$baseUrl/models",
        headers = mapOf("Authorization" to "Bearer $apiKey"),
        provider = provider,
    )

    return json.arrayValue("data")
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { item ->
            val modelId = item.stringValue("id")?.trim().orEmpty()
            if (modelId.isBlank()) return@mapNotNull null
            ProviderModelFetchDto(
                modelId = modelId,
                displayName = modelId,
                type = inferModelType(modelId),
            )
        }
        .orEmpty()
}

private suspend fun fetchClaudeModels(provider: JsonObject): List<ProviderModelFetchDto> {
    val baseUrl = provider.stringValue("baseUrl")?.trim()?.trimEnd('/').orEmpty()
    val apiKey = provider.stringValue("apiKey")?.trim().orEmpty()
    if (baseUrl.isBlank() || apiKey.isBlank()) {
        throw BadRequestException("Provider baseUrl/apiKey is required")
    }

    val json = requestJsonObject(
        url = "$baseUrl/models",
        headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
        ),
        provider = provider,
    )

    return json.arrayValue("data")
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { item ->
            val modelId = item.stringValue("id")?.trim().orEmpty()
            if (modelId.isBlank()) return@mapNotNull null
            ProviderModelFetchDto(
                modelId = modelId,
                displayName = item.stringValue("display_name")?.trim().orEmpty().ifBlank { modelId },
                type = "CHAT",
            )
        }
        .orEmpty()
}

private suspend fun fetchGoogleModels(provider: JsonObject): List<ProviderModelFetchDto> {
    val vertexAI = provider["vertexAI"] as? JsonPrimitive
    if (vertexAI?.content?.toBooleanStrictOrNull() == true) {
        throw BadRequestException("Google Vertex AI model discovery is not supported yet")
    }

    val baseUrl = provider.stringValue("baseUrl")?.trim()?.trimEnd('/').orEmpty()
    val apiKey = provider.stringValue("apiKey")?.trim().orEmpty()
    if (baseUrl.isBlank() || apiKey.isBlank()) {
        throw BadRequestException("Provider baseUrl/apiKey is required")
    }

    val url = "$baseUrl/models?pageSize=100&key=${URLEncoder.encode(apiKey, StandardCharsets.UTF_8)}"
    val json = requestJsonObject(
        url = url,
        headers = emptyMap(),
        provider = provider,
    )

    return json.arrayValue("models")
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { item ->
            val name = item.stringValue("name")?.trim().orEmpty()
            val modelId = name.substringAfter('/', "").ifBlank { name }
            if (modelId.isBlank()) return@mapNotNull null

            val methods = item.arrayValue("supportedGenerationMethods")
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                .orEmpty()
            val type = when {
                methods.contains("generateContent") -> "CHAT"
                methods.contains("embedContent") -> "EMBEDDING"
                else -> inferModelType(modelId)
            }

            ProviderModelFetchDto(
                modelId = modelId,
                displayName = item.stringValue("displayName")?.trim().orEmpty().ifBlank { modelId },
                type = type,
            )
        }
        .orEmpty()
}

private suspend fun requestJsonObject(
    url: String,
    headers: Map<String, String>,
    provider: JsonObject,
): JsonObject {
    val requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .GET()

    headers.forEach { (key, value) -> requestBuilder.header(key, value) }

    val client = httpClientWithProxy(providerModelHttpClient, parseProviderProxy(provider))
    val response = withContext(Dispatchers.IO) {
        client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }
    if (response.statusCode() !in 200..299) {
        val body = response.body().take(400)
        throw BadRequestException("Provider API request failed (${response.statusCode()}): $body")
    }

    return (AppJson.parseToJsonElement(response.body()) as? JsonObject)
        ?: throw BadRequestException("Provider API returned invalid JSON")
}

private fun inferModelType(modelId: String): String {
    val lower = modelId.trim().lowercase()
    return when {
        lower.contains("embedding") || lower.contains("embed") -> "EMBEDDING"
        lower.startsWith("dall-e") ||
            lower.startsWith("gpt-image") ||
            lower.contains("imagegen") ||
            lower.contains("image-generation") -> "IMAGE"
        else -> "CHAT"
    }
}

private fun normalizeToolType(raw: String): String {
    return when (raw.trim().lowercase()) {
        "search" -> "search"
        "url_context", "url-context", "urlcontext" -> "url_context"
        else -> throw BadRequestException("Unsupported built-in tool")
    }
}
