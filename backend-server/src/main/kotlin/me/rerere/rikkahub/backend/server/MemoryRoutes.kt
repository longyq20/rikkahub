package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.NotFoundException
import me.rerere.rikkahub.backend.core.model.AssistantMemoryRecord
import me.rerere.rikkahub.backend.core.util.arrayValue
import me.rerere.rikkahub.backend.core.util.booleanValue
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.storage.sqlite.repo.MemorySqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository

@Serializable
data class MemoryListResponse(
    val assistantId: String,
    val items: List<AssistantMemoryRecord>,
)

@Serializable
data class CreateMemoryRequest(
    val assistantId: String? = null,
    val content: String,
)

@Serializable
data class UpdateMemoryRequest(
    val assistantId: String? = null,
    val content: String,
)

fun Route.registerMemoryRoutes(
    settingsRepository: SettingsJsonRepository,
    memoryRepository: MemorySqliteRepository,
) {
    route("/memory") {
        get {
            val settings = settingsRepository.current()
            val targetAssistantId = resolveMemoryTargetAssistantId(
                settings = settings,
                fallbackAssistantId = settingsRepository.currentAssistantId(),
                requestedAssistantId = call.request.queryParameters["assistantId"],
            )
            val items = memoryRepository.getMemoriesOfAssistant(targetAssistantId)
            call.respond(HttpStatusCode.OK, MemoryListResponse(assistantId = targetAssistantId, items = items))
        }

        post {
            val request = call.receive<CreateMemoryRequest>()
            val content = request.content.trim()
            if (content.isBlank()) {
                throw BadRequestException("content must not be blank")
            }

            val settings = settingsRepository.current()
            val targetAssistantId = resolveMemoryTargetAssistantId(
                settings = settings,
                fallbackAssistantId = settingsRepository.currentAssistantId(),
                requestedAssistantId = request.assistantId,
            )
            val created = memoryRepository.addMemory(targetAssistantId, content)
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{id}") {
            val id = parseMemoryId(call.parameters["id"])
            val request = call.receive<UpdateMemoryRequest>()
            val content = request.content.trim()
            if (content.isBlank()) {
                throw BadRequestException("content must not be blank")
            }

            val settings = settingsRepository.current()
            val targetAssistantId = resolveMemoryTargetAssistantId(
                settings = settings,
                fallbackAssistantId = settingsRepository.currentAssistantId(),
                requestedAssistantId = request.assistantId,
            )

            val existing = memoryRepository.getMemoryById(id) ?: throw NotFoundException("Memory not found")
            if (existing.assistantId != targetAssistantId) {
                throw BadRequestException("Memory does not belong to assistant")
            }

            val updated = memoryRepository.updateContent(id, content)
            call.respond(HttpStatusCode.OK, updated)
        }

        delete("/{id}") {
            val id = parseMemoryId(call.parameters["id"])
            val settings = settingsRepository.current()
            val targetAssistantId = resolveMemoryTargetAssistantId(
                settings = settings,
                fallbackAssistantId = settingsRepository.currentAssistantId(),
                requestedAssistantId = call.request.queryParameters["assistantId"],
            )

            val existing = memoryRepository.getMemoryById(id) ?: throw NotFoundException("Memory not found")
            if (existing.assistantId != targetAssistantId) {
                throw BadRequestException("Memory does not belong to assistant")
            }

            memoryRepository.deleteMemory(id)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

private fun parseMemoryId(raw: String?): Int {
    val id = raw?.toIntOrNull() ?: throw BadRequestException("id must be a positive integer")
    if (id <= 0) throw BadRequestException("id must be a positive integer")
    return id
}

private fun resolveMemoryTargetAssistantId(
    settings: JsonObject,
    fallbackAssistantId: String,
    requestedAssistantId: String?,
): String {
    val assistantId = requestedAssistantId?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackAssistantId
    if (assistantId == MemorySqliteRepository.GLOBAL_MEMORY_ID) {
        return assistantId
    }

    val assistant = settings.arrayValue("assistants")
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it.stringValue("id") == assistantId }
        ?: throw NotFoundException("Assistant not found")

    return if (assistant.booleanValue("useGlobalMemory") == true) {
        MemorySqliteRepository.GLOBAL_MEMORY_ID
    } else {
        assistantId
    }
}