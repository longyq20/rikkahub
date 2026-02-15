package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.NotFoundException
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
import me.rerere.rikkahub.backend.core.util.requireUuid
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.core.util.withArray
import me.rerere.rikkahub.backend.core.util.withBoolean
import me.rerere.rikkahub.backend.core.util.withInt
import me.rerere.rikkahub.backend.core.util.withString
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import kotlin.time.Duration.Companion.seconds

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
            val modelId = request.modelId.requireUuid("modelId")
            settingsRepository.update { settings ->
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
            settingsRepository.update { it.withInt("searchServiceSelected", request.index) }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/model/built-in-tool") {
            val request = call.receive<UpdateBuiltInToolRequest>()
            val modelId = request.modelId.requireUuid("modelId")
            val targetType = normalizeToolType(request.tool)

            settingsRepository.update { settings ->
                val providers = (settings["providers"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                var found = false
                val updatedProviders = providers.map { provider ->
                    val models = (provider["models"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    val updatedModels = models.map { model ->
                        if ((model.stringValue("id") ?: "") != modelId) {
                            return@map model
                        }
                        found = true
                        val tools = (model["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
                        if (request.enabled) {
                            if (tools.none { (it as? JsonObject)?.stringValue("type") == targetType }) {
                                tools += JsonObject(mapOf("type" to JsonPrimitive(targetType)))
                            }
                        } else {
                            tools.removeAll { (it as? JsonObject)?.stringValue("type") == targetType }
                        }
                        JsonObject(model.toMutableMap().apply { this["tools"] = JsonArray(tools) })
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

private fun normalizeToolType(raw: String): String {
    return when (raw.trim().lowercase()) {
        "search" -> "search"
        "url_context", "url-context", "urlcontext" -> "url_context"
        else -> throw BadRequestException("Unsupported built-in tool")
    }
}