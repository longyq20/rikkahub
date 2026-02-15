package me.rerere.rikkahub.backend.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.backend.core.api.ConversationDto
import me.rerere.rikkahub.backend.core.api.ConversationListDto
import me.rerere.rikkahub.backend.core.api.MessageDto
import me.rerere.rikkahub.backend.core.api.MessageNodeDto

const val DEFAULT_ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

@Serializable
data class MessageRecord(
    val id: String,
    val role: String,
    val parts: List<JsonObject>,
    val annotations: List<JsonObject> = emptyList(),
    val createdAt: String,
    val finishedAt: String? = null,
    val modelId: String? = null,
    val usage: JsonElement? = null,
    val translation: String? = null,
)

@Serializable
data class MessageNodeRecord(
    val id: String,
    val messages: List<MessageRecord>,
    val selectIndex: Int,
)

@Serializable
data class ConversationRecord(
    val id: String,
    val assistantId: String,
    val title: String,
    val messageNodes: List<MessageNodeRecord>,
    val truncateIndex: Int = -1,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val createAt: Long,
    val updateAt: Long,
)

@Serializable
data class ManagedFileRecord(
    val id: Long,
    val folder: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
@Serializable
data class AssistantMemoryRecord(
    val id: Int,
    val assistantId: String,
    val content: String,
)
fun ConversationRecord.toListDto(isGenerating: Boolean = false): ConversationListDto =
    ConversationListDto(
        id = id,
        assistantId = assistantId,
        title = title,
        isPinned = isPinned,
        createAt = createAt,
        updateAt = updateAt,
        isGenerating = isGenerating,
    )

fun ConversationRecord.toDto(isGenerating: Boolean = false): ConversationDto =
    ConversationDto(
        id = id,
        assistantId = assistantId,
        title = title,
        messages = messageNodes.map { it.toDto() },
        truncateIndex = truncateIndex,
        chatSuggestions = chatSuggestions,
        isPinned = isPinned,
        createAt = createAt,
        updateAt = updateAt,
        isGenerating = isGenerating,
    )

fun MessageNodeRecord.toDto(): MessageNodeDto = MessageNodeDto(
    id = id,
    messages = messages.map { it.toDto() },
    selectIndex = selectIndex,
)

fun MessageRecord.toDto(): MessageDto = MessageDto(
    id = id,
    role = role,
    parts = parts,
    annotations = annotations,
    createdAt = createdAt,
    finishedAt = finishedAt,
    modelId = modelId,
    usage = usage,
    translation = translation,
)
