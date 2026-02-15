package me.rerere.rikkahub.backend.server

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.rerere.rikkahub.backend.core.api.ConversationListInvalidateEvent
import me.rerere.rikkahub.backend.core.api.ConversationNodeUpdateEvent
import me.rerere.rikkahub.backend.core.api.ConversationSnapshotEvent
import me.rerere.rikkahub.backend.core.api.ErrorEvent
import me.rerere.rikkahub.backend.core.api.singleNodeDiffOrNull
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.backend.core.model.toDto
import me.rerere.rikkahub.backend.core.util.requireUuid
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.server.service.ConversationEngine
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import kotlin.time.Duration.Companion.seconds

fun Route.registerConversationSseRoutes(
    settingsRepository: SettingsJsonRepository,
    conversationEngine: ConversationEngine,
) {
    sse("/stream") {
        heartbeat { period = 15.seconds }

        var currentAssistantId = settingsRepository.currentAssistantId()
        send(
            event = "invalidate",
            data = AppJson.encodeToString(
                ConversationListInvalidateEvent(
                    assistantId = currentAssistantId,
                    timestamp = System.currentTimeMillis(),
                )
            )
        )

        val settingsFlow = settingsRepository.settingsFlow
            .map { it.stringValue("assistantId") ?: DEFAULT_ASSISTANT_ID }
            .distinctUntilChanged()
            .map { ListStreamPayload.AssistantChanged(it) }
        val invalidateFlow = conversationEngine.listInvalidateEvents.map { ListStreamPayload.Invalidated(it) }

        merge(settingsFlow, invalidateFlow).collect { payload ->
            when (payload) {
                is ListStreamPayload.AssistantChanged -> {
                    currentAssistantId = payload.assistantId
                    send(
                        event = "invalidate",
                        data = AppJson.encodeToString(
                            ConversationListInvalidateEvent(
                                assistantId = currentAssistantId,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    )
                }

                is ListStreamPayload.Invalidated -> {
                    if (payload.assistantId != currentAssistantId) return@collect
                    send(
                        event = "invalidate",
                        data = AppJson.encodeToString(
                            ConversationListInvalidateEvent(
                                assistantId = payload.assistantId,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    )
                }
            }
        }
    }

    sse("/{id}/stream") {
        heartbeat { period = 1.seconds }
        val conversationId = call.parameters["id"].requireUuid("conversation id")
        conversationEngine.ensureConversation(conversationId)

        var seq = 0L
        var previous = conversationEngine.getConversation(conversationId)?.toDto(
            isGenerating = conversationEngine.isGenerating(conversationId)
        )

        suspend fun emitConversation() {
            val conversation = conversationEngine.getConversation(conversationId) ?: return
            val dto = conversation.toDto(isGenerating = conversationEngine.isGenerating(conversationId))
            seq += 1

            val diff = previous?.singleNodeDiffOrNull(dto)
            if (diff != null) {
                send(
                    event = "node_update",
                    data = AppJson.encodeToString(
                        ConversationNodeUpdateEvent(
                            seq = seq,
                            conversationId = dto.id,
                            nodeId = diff.node.id,
                            nodeIndex = diff.nodeIndex,
                            node = diff.node,
                            updateAt = dto.updateAt,
                            isGenerating = dto.isGenerating,
                        )
                    ),
                )
            } else {
                send(
                    event = "snapshot",
                    data = AppJson.encodeToString(ConversationSnapshotEvent(seq = seq, conversation = dto)),
                )
            }
            previous = dto
        }

        emitConversation()

        val conversationFlow = conversationEngine.conversationEvents
            .filter { it == conversationId }
            .map { DetailStreamPayload.ConversationChanged }
        val errorFlow = conversationEngine.errors
            .filter { it.conversationId == conversationId }
            .map { DetailStreamPayload.Error(it.message) }

        merge(conversationFlow, errorFlow).collect { payload ->
            when (payload) {
                is DetailStreamPayload.ConversationChanged -> emitConversation()
                is DetailStreamPayload.Error -> {
                    send(
                        event = "error",
                        data = AppJson.encodeToString(ErrorEvent(message = payload.message)),
                    )
                }
            }
        }
    }
}

private sealed interface ListStreamPayload {
    data class AssistantChanged(val assistantId: String) : ListStreamPayload
    data class Invalidated(val assistantId: String) : ListStreamPayload
}

private sealed interface DetailStreamPayload {
    data object ConversationChanged : DetailStreamPayload
    data class Error(val message: String) : DetailStreamPayload
}