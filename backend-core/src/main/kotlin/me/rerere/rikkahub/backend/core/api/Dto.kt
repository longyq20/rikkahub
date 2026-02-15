package me.rerere.rikkahub.backend.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SendMessageRequest(
    val parts: List<JsonObject>,
)

@Serializable
data class RegenerateRequest(
    val messageId: String,
)

@Serializable
data class ToolApprovalRequest(
    val toolCallId: String,
    val approved: Boolean,
    val reason: String = "",
)

@Serializable
data class EditMessageRequest(
    val parts: List<JsonObject>,
)

@Serializable
data class ForkConversationRequest(
    val messageId: String,
)

@Serializable
data class SelectMessageNodeRequest(
    val selectIndex: Int,
)

@Serializable
data class MoveConversationRequest(
    val assistantId: String,
)

@Serializable
data class UpdateConversationTitleRequest(
    val title: String,
)

@Serializable
data class UpdateAssistantRequest(
    val assistantId: String,
)

@Serializable
data class UpdateAssistantModelRequest(
    val assistantId: String,
    val modelId: String,
)

@Serializable
data class UpdateAssistantThinkingBudgetRequest(
    val assistantId: String,
    val thinkingBudget: Int?,
)

@Serializable
data class UpdateAssistantMcpServersRequest(
    val assistantId: String,
    val mcpServerIds: List<String>,
)

@Serializable
data class UpdateAssistantInjectionsRequest(
    val assistantId: String,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
)

@Serializable
data class UpdateSearchEnabledRequest(
    val enabled: Boolean,
)

@Serializable
data class UpdateSearchServiceRequest(
    val index: Int,
)

@Serializable
data class UpdateBuiltInToolRequest(
    val modelId: String,
    val tool: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateFavoriteModelsRequest(
    val modelIds: List<String>,
)

@Serializable
data class WebAuthTokenRequest(
    val password: String,
)

@Serializable
data class ConversationListDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false,
)

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val nextOffset: Int? = null,
    val hasMore: Boolean = nextOffset != null,
)

@Serializable
data class UploadedFileDto(
    val id: Long,
    val url: String,
    val fileName: String,
    val mime: String,
    val size: Long,
)

@Serializable
data class UploadFilesResponseDto(
    val files: List<UploadedFileDto>,
)

@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val truncateIndex: Int,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false,
)

@Serializable
data class MessageNodeDto(
    val id: String,
    val messages: List<MessageDto>,
    val selectIndex: Int,
)

@Serializable
data class MessageDto(
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
data class ForkConversationResponse(
    val conversationId: String,
)

@Serializable
data class WebAuthTokenResponse(
    val token: String,
    val expiresAt: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int,
)

@Serializable
data class ConversationSnapshotEvent(
    val type: String = "snapshot",
    val seq: Long,
    val conversation: ConversationDto,
)

@Serializable
data class ConversationNodeUpdateEvent(
    val type: String = "node_update",
    val seq: Long,
    val conversationId: String,
    val nodeId: String,
    val nodeIndex: Int,
    val node: MessageNodeDto,
    val updateAt: Long,
    val isGenerating: Boolean,
)

@Serializable
data class ErrorEvent(
    val type: String = "error",
    val message: String,
)

@Serializable
data class ConversationListInvalidateEvent(
    val type: String = "invalidate",
    val assistantId: String,
    val timestamp: Long,
)

@Serializable
data class SystemHealthDto(
    val status: String,
)

@Serializable
data class SystemInfoDto(
    val name: String,
    val version: String,
    val host: String,
    val port: Int,
    val platform: String,
    val dataDir: String,
    val jwtEnabled: Boolean,
)

@Serializable
data class MigrationImportReportDto(
    val success: Boolean,
    val rolledBack: Boolean,
    val message: String,
    val importedConversations: Int,
    val importedMessageNodes: Int,
    val importedFiles: Int,
)