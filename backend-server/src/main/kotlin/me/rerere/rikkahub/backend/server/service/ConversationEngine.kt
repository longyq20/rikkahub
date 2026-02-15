package me.rerere.rikkahub.backend.server.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.core.model.MessageNodeRecord
import me.rerere.rikkahub.backend.core.model.MessageRecord
import me.rerere.rikkahub.backend.core.util.arrayValue
import me.rerere.rikkahub.backend.core.util.objectValue
import me.rerere.rikkahub.backend.core.util.randomId
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.storage.sqlite.repo.ConversationSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class EngineErrorEvent(
    val conversationId: String,
    val message: String,
)

class ConversationEngine(
    private val conversationRepository: ConversationSqliteRepository,
    private val settingsRepository: SettingsJsonRepository,
    private val llmGenerator: LlmGenerator = PortableLlmGenerator(),
    private val toolExecutor: ToolExecutor = NoopToolExecutor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generationJobs = ConcurrentHashMap<String, Job>()

    private val _conversationEvents = MutableSharedFlow<String>(extraBufferCapacity = 128)
    private val _listInvalidateEvents = MutableSharedFlow<String>(extraBufferCapacity = 128)
    private val _errors = MutableSharedFlow<EngineErrorEvent>(extraBufferCapacity = 128)

    val conversationEvents: SharedFlow<String> = _conversationEvents
    val listInvalidateEvents: SharedFlow<String> = _listInvalidateEvents
    val errors: SharedFlow<EngineErrorEvent> = _errors

    suspend fun ensureConversation(id: String): ConversationRecord {
        conversationRepository.getConversationById(id)?.let { return it }
        val now = Instant.now().toEpochMilli()
        val created = ConversationRecord(
            id = id,
            assistantId = settingsRepository.currentAssistantId(),
            title = "",
            messageNodes = emptyList(),
            createAt = now,
            updateAt = now,
        )
        conversationRepository.upsertConversation(created)
        emitConversationChanged(created.id, created.assistantId)
        return created
    }

    suspend fun getConversation(id: String): ConversationRecord? = conversationRepository.getConversationById(id)

    fun isGenerating(conversationId: String): Boolean = generationJobs.containsKey(conversationId)

    suspend fun sendMessage(conversationId: String, parts: List<JsonObject>, answer: Boolean = true) {
        if (parts.isEmpty()) {
            throw BadRequestException("parts must not be empty")
        }
        val conversation = ensureConversation(conversationId)
        val now = Instant.now().toEpochMilli()
        val userMessage = createMessage(role = "USER", parts = parts, modelId = null)
        val nextConversation = conversation.copy(
            title = pickConversationTitle(conversation.title, parts),
            messageNodes = conversation.messageNodes + MessageNodeRecord(
                id = randomId(),
                messages = listOf(userMessage),
                selectIndex = 0,
            ),
            updateAt = now,
        )
        conversationRepository.upsertConversation(nextConversation)
        emitConversationChanged(nextConversation.id, nextConversation.assistantId)

        if (answer) {
            startAssistantGeneration(nextConversation.id)
        }
    }

    suspend fun editMessage(conversationId: String, messageId: String, parts: List<JsonObject>) {
        if (parts.isEmpty()) {
            throw BadRequestException("parts must not be empty")
        }
        val conversation = ensureConversation(conversationId)
        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (nodeIndex < 0) {
            throw BadRequestException("Message not found")
        }

        val node = conversation.messageNodes[nodeIndex]
        val original = node.messages.find { it.id == messageId } ?: throw BadRequestException("Message not found")
        val edited = original.copy(
            id = randomId(),
            parts = parts,
            createdAt = Instant.now().toString(),
            finishedAt = null,
        )

        val updatedNode = node.copy(
            messages = node.messages + edited,
            selectIndex = node.messages.size,
        )
        val updatedNodes = conversation.messageNodes.toMutableList().apply {
            this[nodeIndex] = updatedNode
        }

        val updated = conversation.copy(
            messageNodes = updatedNodes,
            updateAt = Instant.now().toEpochMilli(),
        )
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)

        if (original.role == "USER") {
            startAssistantGeneration(updated.id)
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        val conversation = ensureConversation(conversationId)
        val updatedNodes = conversation.messageNodes.filterNot { node ->
            node.messages.any { it.id == messageId }
        }
        if (updatedNodes.size == conversation.messageNodes.size) {
            throw BadRequestException("Message not found")
        }
        val updated = conversation.copy(
            messageNodes = updatedNodes,
            updateAt = Instant.now().toEpochMilli(),
        )
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)
    }

    suspend fun forkConversationAtMessage(conversationId: String, messageId: String): ConversationRecord {
        val conversation = ensureConversation(conversationId)
        val targetIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetIndex < 0) {
            throw BadRequestException("Message not found")
        }

        val now = Instant.now().toEpochMilli()
        val forkId = randomId()
        val fork = conversation.copy(
            id = forkId,
            title = if (conversation.title.isBlank()) "Fork" else "${conversation.title} (Fork)",
            messageNodes = conversation.messageNodes.take(targetIndex + 1),
            createAt = now,
            updateAt = now,
        )
        conversationRepository.upsertConversation(fork)
        emitConversationChanged(fork.id, fork.assistantId)
        return fork
    }

    suspend fun selectMessageNode(conversationId: String, nodeId: String, selectIndex: Int) {
        val conversation = ensureConversation(conversationId)
        val nodeIndex = conversation.messageNodes.indexOfFirst { it.id == nodeId }
        if (nodeIndex < 0) {
            throw BadRequestException("Message node not found")
        }
        val node = conversation.messageNodes[nodeIndex]
        if (selectIndex !in node.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }
        val updatedNodes = conversation.messageNodes.toMutableList().apply {
            this[nodeIndex] = node.copy(selectIndex = selectIndex)
        }
        val updated = conversation.copy(messageNodes = updatedNodes, updateAt = Instant.now().toEpochMilli())
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)
    }

    suspend fun regenerateAtMessage(conversationId: String, messageId: String) {
        val conversation = ensureConversation(conversationId)
        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (nodeIndex < 0) {
            throw BadRequestException("Message not found")
        }

        val node = conversation.messageNodes[nodeIndex]
        val target = node.messages.find { it.id == messageId } ?: throw BadRequestException("Message not found")

        val trimmedNodes = when (target.role.uppercase()) {
            "ASSISTANT" -> conversation.messageNodes.take(nodeIndex)
            "USER" -> conversation.messageNodes.take(nodeIndex + 1)
            else -> conversation.messageNodes.take(nodeIndex + 1)
        }

        val updated = conversation.copy(
            messageNodes = trimmedNodes,
            updateAt = Instant.now().toEpochMilli(),
        )
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)

        startAssistantGeneration(updated.id)
    }

    suspend fun handleToolApproval(conversationId: String, toolCallId: String, approved: Boolean, reason: String) {
        val conversation = ensureConversation(conversationId)
        var found = false

        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            if ((part.stringValue("type") ?: "") != "tool") {
                                return@map part
                            }
                            if ((part.stringValue("toolCallId") ?: "") != toolCallId) {
                                return@map part
                            }
                            found = true
                            JsonObject(
                                part.toMutableMap().apply {
                                    this["approvalState"] = if (approved) {
                                        JsonObject(mapOf("type" to JsonPrimitive("approved")))
                                    } else {
                                        JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("denied"),
                                                "reason" to JsonPrimitive(reason),
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            )
        }

        if (!found) {
            throw BadRequestException("Tool call not found")
        }

        val updated = conversation.copy(messageNodes = updatedNodes, updateAt = Instant.now().toEpochMilli())
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)

        val toolState = analyzeToolState(updated)
        if (!toolState.hasPendingTools) {
            startAssistantGeneration(updated.id)
        }
    }

    suspend fun stopGeneration(conversationId: String) {
        generationJobs.remove(conversationId)?.cancel()
        conversationRepository.getConversationById(conversationId)?.let {
            emitConversationChanged(it.id, it.assistantId)
        }
    }

    suspend fun moveConversation(conversationId: String, assistantId: String) {
        val conversation = ensureConversation(conversationId)
        val updated = conversation.copy(
            assistantId = assistantId,
            updateAt = Instant.now().toEpochMilli(),
        )
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)
    }

    suspend fun updateConversationTitle(conversationId: String, title: String) {
        val conversation = ensureConversation(conversationId)
        val updated = conversation.copy(
            title = title,
            updateAt = Instant.now().toEpochMilli(),
        )
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)
    }

    suspend fun regenerateConversationTitle(conversationId: String) {
        val conversation = ensureConversation(conversationId)
        val settings = settingsRepository.current()

        val generated = runCatching {
            llmGenerator.generateTitle(settings, conversation)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val fallback = deriveConversationTitle(conversation)
        updateConversationTitle(conversationId, generated ?: fallback)
    }

    suspend fun togglePin(conversationId: String) {
        val conversation = ensureConversation(conversationId)
        val updated = conversation.copy(
            isPinned = !conversation.isPinned,
            updateAt = Instant.now().toEpochMilli(),
        )
        conversationRepository.upsertConversation(updated)
        emitConversationChanged(updated.id, updated.assistantId)
    }

    suspend fun deleteConversation(conversationId: String): Boolean {
        stopGeneration(conversationId)
        val existing = conversationRepository.getConversationById(conversationId) ?: return false
        val deleted = conversationRepository.deleteConversationById(conversationId)
        if (deleted) {
            _listInvalidateEvents.emit(existing.assistantId)
            _conversationEvents.emit(conversationId)
        }
        return deleted
    }

    fun emitError(conversationId: String, message: String) {
        scope.launch {
            _errors.emit(EngineErrorEvent(conversationId, message))
        }
    }

    private fun startAssistantGeneration(conversationId: String) {
        generationJobs.remove(conversationId)?.cancel()
        val job = scope.launch {
            val snapshot = conversationRepository.getConversationById(conversationId)
            if (snapshot != null) {
                emitConversationChanged(snapshot.id, snapshot.assistantId)
            }
            try {
                runGenerationLoop(conversationId)
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                emitError(conversationId, t.message ?: "Generation failed")
            } finally {
                generationJobs.remove(conversationId)
                conversationRepository.getConversationById(conversationId)?.let {
                    emitConversationChanged(it.id, it.assistantId)
                }
            }
        }
        generationJobs[conversationId] = job
    }

    private suspend fun runGenerationLoop(conversationId: String) {
        repeat(MAX_TOOL_CHAIN_STEPS) { step ->
            var latest = conversationRepository.getConversationById(conversationId) ?: return
            val settings = settingsRepository.current()

            val execution = executeRunnableTools(settings, latest)
            if (execution.executedAny) {
                latest = execution.updatedConversation.copy(updateAt = Instant.now().toEpochMilli())
                conversationRepository.upsertConversation(latest)
                emitConversationChanged(latest.id, latest.assistantId)
            }

            if (execution.hasPendingTools) {
                return
            }
            if (execution.hasRunnableTools && !execution.executedAny) {
                emitError(conversationId, "Tool execution pending but no executable tool produced output")
                return
            }

            val generation = llmGenerator.generateReply(settings, latest)
            val assistantMessage = createMessage(
                role = "ASSISTANT",
                parts = generation.parts,
                modelId = generation.modelId,
                usage = generation.usage,
            )

            val updated = latest.copy(
                messageNodes = latest.messageNodes + MessageNodeRecord(
                    id = randomId(),
                    messages = listOf(assistantMessage),
                    selectIndex = 0,
                ),
                updateAt = Instant.now().toEpochMilli(),
            )
            conversationRepository.upsertConversation(updated)
            emitConversationChanged(updated.id, updated.assistantId)

            val stateAfterGeneration = analyzeToolState(updated)
            if (stateAfterGeneration.hasPendingTools) {
                return
            }
            if (!stateAfterGeneration.hasRunnableTools) {
                return
            }

            if (step == MAX_TOOL_CHAIN_STEPS - 1) {
                emitError(conversationId, "Tool chain loop limit reached")
            }
        }
    }

    private suspend fun executeRunnableTools(settings: JsonObject, conversation: ConversationRecord): ToolExecutionResult {
        var executedAny = false

        val updatedNodes = conversation.messageNodes.map { node ->
            if (node.messages.isEmpty()) {
                return@map node
            }

            val selectedIndex = node.selectIndex.coerceIn(0, node.messages.lastIndex)
            val updatedMessages = node.messages.mapIndexed { messageIndex, message ->
                if (messageIndex != selectedIndex) {
                    return@mapIndexed message
                }

                val updatedParts = message.parts.map { part ->
                    if ((part.stringValue("type") ?: "") != "tool") {
                        return@map part
                    }

                    val output = part.arrayValue("output")
                    if (output != null && output.isNotEmpty()) {
                        return@map part
                    }

                    val approvalType = part.objectValue("approvalState")?.stringValue("type") ?: "auto"
                    if (approvalType != "auto" && approvalType != "approved") {
                        return@map part
                    }

                    val toolName = part.stringValue("toolName") ?: "tool"
                    val input = part.stringValue("input").orEmpty()
                    val toolOutput = toolExecutor.execute(settings, conversation.assistantId, toolName, input)
                    executedAny = true

                    JsonObject(
                        part.toMutableMap().apply {
                            this["output"] = JsonArray(toolOutput)
                        }
                    )
                }

                message.copy(parts = updatedParts)
            }

            node.copy(messages = updatedMessages)
        }

        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
        val state = analyzeToolState(updatedConversation)
        return ToolExecutionResult(
            updatedConversation = updatedConversation,
            executedAny = executedAny,
            hasPendingTools = state.hasPendingTools,
            hasRunnableTools = state.hasRunnableTools,
        )
    }

    private fun analyzeToolState(conversation: ConversationRecord): ToolState {
        var hasPending = false
        var hasRunnable = false

        conversation.messageNodes.forEach { node ->
            val selected = node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull() ?: return@forEach
            selected.parts.forEach { part ->
                if ((part.stringValue("type") ?: "") != "tool") return@forEach

                val output = part.arrayValue("output")
                if (output != null && output.isNotEmpty()) {
                    return@forEach
                }

                when (part.objectValue("approvalState")?.stringValue("type") ?: "auto") {
                    "pending" -> hasPending = true
                    "auto", "approved" -> hasRunnable = true
                }
            }
        }

        return ToolState(hasPendingTools = hasPending, hasRunnableTools = hasRunnable)
    }

    private suspend fun emitConversationChanged(conversationId: String, assistantId: String) {
        _conversationEvents.emit(conversationId)
        _listInvalidateEvents.emit(assistantId)
    }

    private fun createMessage(
        role: String,
        parts: List<JsonObject>,
        modelId: String? = "auto",
        usage: JsonElement? = null,
    ): MessageRecord {
        return MessageRecord(
            id = randomId(),
            role = role,
            parts = parts,
            annotations = emptyList(),
            createdAt = Instant.now().toString(),
            finishedAt = Instant.now().toString(),
            modelId = modelId,
            usage = usage,
            translation = null,
        )
    }

    private fun pickConversationTitle(currentTitle: String, parts: List<JsonObject>): String {
        if (currentTitle.isNotBlank()) return currentTitle
        val textPart = parts.firstOrNull { (it.stringValue("type") ?: "") == "text" }
        val raw = textPart?.stringValue("text")?.trim().orEmpty()
        if (raw.isEmpty()) return "New Conversation"
        return raw.replace(Regex("\\s+"), " ").take(48)
    }

    private fun deriveConversationTitle(conversation: ConversationRecord): String {
        val firstText = conversation.messageNodes
            .asSequence()
            .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull() }
            .flatMap { message -> message.parts.asSequence() }
            .firstOrNull { part -> part.stringValue("type") == "text" && !part.stringValue("text").isNullOrBlank() }
            ?.stringValue("text")
            ?.trim()
            .orEmpty()

        if (firstText.isBlank()) {
            return "Conversation ${Instant.now().toString().take(19)}"
        }
        return firstText.replace(Regex("\\s+"), " ").take(48)
    }

    private data class ToolExecutionResult(
        val updatedConversation: ConversationRecord,
        val executedAny: Boolean,
        val hasPendingTools: Boolean,
        val hasRunnableTools: Boolean,
    )

    private data class ToolState(
        val hasPendingTools: Boolean,
        val hasRunnableTools: Boolean,
    )

    private companion object {
        const val MAX_TOOL_CHAIN_STEPS = 8
    }
}
