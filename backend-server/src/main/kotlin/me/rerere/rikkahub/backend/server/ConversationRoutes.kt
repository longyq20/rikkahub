package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.ForkConversationRequest
import me.rerere.rikkahub.backend.core.api.ForkConversationResponse
import me.rerere.rikkahub.backend.core.api.MoveConversationRequest
import me.rerere.rikkahub.backend.core.api.NotFoundException
import me.rerere.rikkahub.backend.core.api.PagedResult
import me.rerere.rikkahub.backend.core.api.RegenerateRequest
import me.rerere.rikkahub.backend.core.api.SelectMessageNodeRequest
import me.rerere.rikkahub.backend.core.api.SendMessageRequest
import me.rerere.rikkahub.backend.core.api.ToolApprovalRequest
import me.rerere.rikkahub.backend.core.api.UpdateConversationTitleRequest
import me.rerere.rikkahub.backend.core.model.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.backend.core.model.toDto
import me.rerere.rikkahub.backend.core.model.toListDto
import me.rerere.rikkahub.backend.core.util.requireUuid
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.server.service.ConversationEngine
import me.rerere.rikkahub.backend.storage.sqlite.repo.ConversationSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import java.time.Instant

fun Route.registerConversationRoutes(
    settingsRepository: SettingsJsonRepository,
    conversationRepository: ConversationSqliteRepository,
    conversationEngine: ConversationEngine,
) {
    route("/conversations") {
        get {
            val assistantId = settingsRepository.currentAssistantId()
            val page = conversationRepository.getConversationsOfAssistantPage(assistantId, offset = 0, limit = 200)
            call.respond(page.items.map { it.toListDto(isGenerating = conversationEngine.isGenerating(it.id)) })
        }

        get("/paged") {
            val assistantId = settingsRepository.currentAssistantId()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            if (offset < 0) throw BadRequestException("offset must be >= 0")
            if (limit !in 1..100) throw BadRequestException("limit must be in 1..100")

            val page = conversationRepository.getConversationsOfAssistantPage(
                assistantId = assistantId,
                offset = offset,
                limit = limit,
                titleKeyword = query,
            )
            call.respond(
                PagedResult(
                    items = page.items.map { it.toListDto(isGenerating = conversationEngine.isGenerating(it.id)) },
                    nextOffset = page.nextOffset,
                )
            )
        }

        get("/{id}") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val conversation = conversationRepository.getConversationById(conversationId)
                ?: throw NotFoundException("Conversation not found")
            call.respond(conversation.toDto(isGenerating = conversationEngine.isGenerating(conversation.id)))
        }

        delete("/{id}") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val deleted = conversationEngine.deleteConversation(conversationId)
            if (!deleted) throw NotFoundException("Conversation not found")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/pin") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            conversationEngine.togglePin(conversationId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/regenerate-title") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            conversationEngine.updateConversationTitle(conversationId, "Conversation ${Instant.now().toString().take(19)}")
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/title") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val request = call.receive<UpdateConversationTitleRequest>()
            val title = request.title.trim()
            if (title.isBlank()) throw BadRequestException("Title must not be blank")
            conversationEngine.updateConversationTitle(conversationId, title)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/move") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val request = call.receive<MoveConversationRequest>()
            val targetAssistantId = request.assistantId.requireUuid("assistant id")
            ensureAssistantExists(settingsRepository.current(), targetAssistantId)
            conversationEngine.moveConversation(conversationId, targetAssistantId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/messages") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val request = call.receive<SendMessageRequest>()
            conversationEngine.sendMessage(conversationId, request.parts, answer = true)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/messages/{messageId}/edit") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val messageId = call.parameters["messageId"].requireUuid("message id")
            val request = call.receive<me.rerere.rikkahub.backend.core.api.EditMessageRequest>()
            conversationEngine.editMessage(conversationId, messageId, request.parts)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/fork") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val request = call.receive<ForkConversationRequest>()
            val messageId = request.messageId.requireUuid("message id")
            val fork = conversationEngine.forkConversationAtMessage(conversationId, messageId)
            call.respond(HttpStatusCode.Created, ForkConversationResponse(conversationId = fork.id))
        }

        delete("/{id}/messages/{messageId}") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val messageId = call.parameters["messageId"].requireUuid("message id")
            conversationEngine.deleteMessage(conversationId, messageId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        post("/{id}/nodes/{nodeId}/select") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val nodeId = call.parameters["nodeId"].requireUuid("node id")
            val request = call.receive<SelectMessageNodeRequest>()
            conversationEngine.selectMessageNode(conversationId, nodeId, request.selectIndex)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/regenerate") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val request = call.receive<RegenerateRequest>()
            val messageId = request.messageId.requireUuid("message id")
            conversationEngine.regenerateAtMessage(conversationId, messageId)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/stop") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            conversationEngine.stopGeneration(conversationId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "stopped"))
        }

        post("/{id}/tool-approval") {
            val conversationId = call.parameters["id"].requireUuid("conversation id")
            val request = call.receive<ToolApprovalRequest>()
            conversationEngine.handleToolApproval(conversationId, request.toolCallId, request.approved, request.reason)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        registerConversationSseRoutes(settingsRepository, conversationEngine)
    }
}

private fun ensureAssistantExists(settings: JsonObject, assistantId: String) {
    val assistants = (settings["assistants"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    if (assistants.none { (it.stringValue("id") ?: DEFAULT_ASSISTANT_ID) == assistantId }) {
        throw BadRequestException("Assistant not found")
    }
}