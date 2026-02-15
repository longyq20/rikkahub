package me.rerere.rikkahub.backend.server.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.core.model.MessageNodeRecord
import me.rerere.rikkahub.backend.core.model.MessageRecord
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import me.rerere.rikkahub.backend.storage.sqlite.repo.ConversationSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class ConversationEngineTest {
    @Test
    fun sendMessage_generatesAssistantMessageFromGenerator() = runBlocking {
        val context = createContext()
        val generator = FakeGenerator(reply = "generated reply")
        val engine = ConversationEngine(context.conversationRepo, context.settingsRepo, generator)

        engine.sendMessage("conv-1", listOf(textPart("hello")), answer = true)

        waitUntilDone(engine, "conv-1")

        val conversation = engine.getConversation("conv-1") ?: error("conversation missing")
        assertEquals(2, conversation.messageNodes.size)

        val assistant = conversation.messageNodes.last().messages.first()
        assertEquals("ASSISTANT", assistant.role)
        assertEquals("generated reply", assistant.parts.first().stringValue("text"))
        assertEquals("model-1", assistant.modelId)
    }

    @Test
    fun toolApproval_whenAllPendingResolved_continuesGeneration() = runBlocking {
        val context = createContext()
        val generator = FakeGenerator(reply = "continued after approval")
        val engine = ConversationEngine(context.conversationRepo, context.settingsRepo, generator)

        val now = Instant.now().toEpochMilli()
        val conversation = ConversationRecord(
            id = "conv-tool",
            assistantId = context.settingsRepo.currentAssistantId(),
            title = "",
            messageNodes = listOf(
                MessageNodeRecord(
                    id = "n-user",
                    messages = listOf(
                        MessageRecord(
                            id = "m-user",
                            role = "USER",
                            parts = listOf(textPart("question")),
                            createdAt = Instant.now().toString(),
                        )
                    ),
                    selectIndex = 0,
                ),
                MessageNodeRecord(
                    id = "n-assistant-tool",
                    messages = listOf(
                        MessageRecord(
                            id = "m-tool",
                            role = "ASSISTANT",
                            parts = listOf(pendingToolPart("tool-call-1")),
                            createdAt = Instant.now().toString(),
                        )
                    ),
                    selectIndex = 0,
                ),
            ),
            createAt = now,
            updateAt = now,
        )
        context.conversationRepo.upsertConversation(conversation)

        engine.handleToolApproval(
            conversationId = "conv-tool",
            toolCallId = "tool-call-1",
            approved = true,
            reason = "",
        )

        waitUntilDone(engine, "conv-tool")

        val updated = engine.getConversation("conv-tool") ?: error("conversation missing")
        assertTrue(updated.messageNodes.size >= 3)

        val latest = updated.messageNodes.last().messages.first()
        assertEquals("ASSISTANT", latest.role)
        assertEquals("continued after approval", latest.parts.first().stringValue("text"))

        val toolPart = updated.messageNodes[1].messages.first().parts.first()
        val toolApprovalType = toolPart.objectValue("approvalState")?.stringValue("type")
        assertEquals("approved", toolApprovalType)
        assertTrue((toolPart.arrayValue("output")?.isNotEmpty() == true))
    }

    @Test
    fun toolApproval_executesApprovedToolBeforeGenerating() = runBlocking {
        val context = createContext()
        val toolExecutor = FakeToolExecutor(
            outputs = mapOf(
                "get_time_info" to JsonObject(mapOf("datetime" to JsonPrimitive("2026-02-15T00:00:00Z")))
            )
        )
        val generator = FakeGenerator { conversation ->
            val toolPart = conversation.messageNodes
                .flatMap { it.messages }
                .flatMap { it.parts }
                .firstOrNull { it.stringValue("type") == "tool" }
            val outputText = toolPart
                ?.arrayValue("output")
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.stringValue("text")
                .orEmpty()

            val text = if (outputText.contains("2026-02-15")) {
                "tool output observed"
            } else {
                "tool output missing"
            }
            AssistantGenerationResult(parts = listOf(textPart(text)), modelId = "model-1", usage = null)
        }
        val engine = ConversationEngine(context.conversationRepo, context.settingsRepo, generator, toolExecutor)

        val now = Instant.now().toEpochMilli()
        context.conversationRepo.upsertConversation(
            ConversationRecord(
                id = "conv-tool-check",
                assistantId = context.settingsRepo.currentAssistantId(),
                title = "",
                messageNodes = listOf(
                    MessageNodeRecord(
                        id = "n-user",
                        messages = listOf(
                            MessageRecord(
                                id = "m-user",
                                role = "USER",
                                parts = listOf(textPart("what time")),
                                createdAt = Instant.now().toString(),
                            )
                        ),
                        selectIndex = 0,
                    ),
                    MessageNodeRecord(
                        id = "n-tool",
                        messages = listOf(
                            MessageRecord(
                                id = "m-tool",
                                role = "ASSISTANT",
                                parts = listOf(pendingToolPart("tool-call-2", "get_time_info", "{}")),
                                createdAt = Instant.now().toString(),
                            )
                        ),
                        selectIndex = 0,
                    ),
                ),
                createAt = now,
                updateAt = now,
            )
        )

        engine.handleToolApproval("conv-tool-check", "tool-call-2", approved = true, reason = "")
        waitUntilDone(engine, "conv-tool-check")

        val updated = engine.getConversation("conv-tool-check") ?: error("conversation missing")
        val finalText = updated.messageNodes.last().messages.first().parts.first().stringValue("text")
        assertEquals("tool output observed", finalText)
        assertEquals(1, toolExecutor.calls.size)
    }

    @Test
    fun autoToolMessage_executesAndLoopsToFinalAssistantReply() = runBlocking {
        val context = createContext()
        val toolExecutor = FakeToolExecutor(
            outputs = mapOf(
                "get_time_info" to JsonObject(mapOf("timezone" to JsonPrimitive("UTC")))
            )
        )
        val generator = FakeGenerator { conversation ->
            val hasExecutedTool = conversation.messageNodes
                .flatMap { it.messages }
                .flatMap { it.parts }
                .any { part ->
                    part.stringValue("type") == "tool" && (part.arrayValue("output")?.isNotEmpty() == true)
                }

            if (!hasExecutedTool) {
                AssistantGenerationResult(
                    parts = listOf(autoToolPart("tool-auto-1", "get_time_info", "{}")),
                    modelId = "model-1",
                    usage = null,
                )
            } else {
                AssistantGenerationResult(
                    parts = listOf(textPart("final after auto tool")),
                    modelId = "model-1",
                    usage = null,
                )
            }
        }
        val engine = ConversationEngine(context.conversationRepo, context.settingsRepo, generator, toolExecutor)

        engine.sendMessage("conv-auto-tool", listOf(textPart("time now")), answer = true)
        waitUntilDone(engine, "conv-auto-tool")

        val conversation = engine.getConversation("conv-auto-tool") ?: error("conversation missing")
        assertEquals(3, conversation.messageNodes.size)

        val toolMessage = conversation.messageNodes[1].messages.first()
        val toolPart = toolMessage.parts.first()
        assertEquals("tool", toolPart.stringValue("type"))
        assertTrue(toolPart.arrayValue("output")?.isNotEmpty() == true)

        val final = conversation.messageNodes.last().messages.first()
        assertEquals("final after auto tool", final.parts.first().stringValue("text"))
        assertEquals(1, toolExecutor.calls.size)
    }

    @Test
    fun regenerateAtMessage_trimsContextAndGeneratesAgain() = runBlocking {
        val context = createContext()
        val generator = FakeGenerator(reply = "regen reply")
        val engine = ConversationEngine(context.conversationRepo, context.settingsRepo, generator)

        engine.sendMessage("conv-regen", listOf(textPart("first")), answer = true)
        waitUntilDone(engine, "conv-regen")

        val existing = engine.getConversation("conv-regen") ?: error("conversation missing")
        val userMessageId = existing.messageNodes.first().messages.first().id

        engine.regenerateAtMessage("conv-regen", userMessageId)
        waitUntilDone(engine, "conv-regen")

        val updated = engine.getConversation("conv-regen") ?: error("conversation missing")
        assertTrue(updated.messageNodes.size >= 2)
        val latest = updated.messageNodes.last().messages.first()
        assertEquals("ASSISTANT", latest.role)
        assertEquals("regen reply", latest.parts.first().stringValue("text"))
    }

    private suspend fun waitUntilDone(engine: ConversationEngine, conversationId: String) {
        repeat(120) {
            if (!engine.isGenerating(conversationId)) {
                delay(10)
                return
            }
            delay(25)
        }
        assertFalse("generation should complete", engine.isGenerating(conversationId))
    }

    private fun createContext(): TestContext {
        val dataDir = Files.createTempDirectory("engine-test-")
        val paths = BackendPaths(dataDir)
        val database = SqliteDatabase(paths)
        val settingsRepo = SettingsJsonRepository(paths, jwtEnabled = false, accessPassword = "")
        val conversationRepo = ConversationSqliteRepository(database)
        return TestContext(settingsRepo, conversationRepo)
    }

    private data class TestContext(
        val settingsRepo: SettingsJsonRepository,
        val conversationRepo: ConversationSqliteRepository,
    )

    private class FakeGenerator(
        private val reply: String = "ok",
        private val replyFactory: ((ConversationRecord) -> AssistantGenerationResult)? = null,
    ) : LlmGenerator {
        constructor(factory: (ConversationRecord) -> AssistantGenerationResult) : this(reply = "ok", replyFactory = factory)

        override suspend fun generateReply(
            settings: JsonObject,
            conversation: ConversationRecord,
        ): AssistantGenerationResult {
            return replyFactory?.invoke(conversation)
                ?: AssistantGenerationResult(
                    parts = listOf(textPart(reply)),
                    modelId = "model-1",
                    usage = null,
                )
        }

        override suspend fun generateTitle(settings: JsonObject, conversation: ConversationRecord): String? {
            return "Generated title"
        }
    }

    private class FakeToolExecutor(
        private val outputs: Map<String, JsonObject> = emptyMap(),
    ) : ToolExecutor {
        val calls = mutableListOf<String>()

        override suspend fun execute(settings: JsonObject, assistantId: String, toolName: String, input: String): List<JsonObject> {
            calls += "$toolName::$input"
            val payload = outputs[toolName] ?: JsonObject(mapOf("ok" to JsonPrimitive(true)))
            return listOf(textPart(AppJson.encodeToString(JsonObject.serializer(), payload)))
        }
    }

    private companion object {
        private fun textPart(text: String): JsonObject {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(text),
                )
            )
        }

        private fun pendingToolPart(
            toolCallId: String,
            toolName: String = "demo_tool",
            input: String = "{}",
        ): JsonObject {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool"),
                    "toolCallId" to JsonPrimitive(toolCallId),
                    "toolName" to JsonPrimitive(toolName),
                    "input" to JsonPrimitive(input),
                    "output" to JsonArray(emptyList()),
                    "approvalState" to JsonObject(mapOf("type" to JsonPrimitive("pending"))),
                )
            )
        }

        private fun autoToolPart(toolCallId: String, toolName: String, input: String): JsonObject {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool"),
                    "toolCallId" to JsonPrimitive(toolCallId),
                    "toolName" to JsonPrimitive(toolName),
                    "input" to JsonPrimitive(input),
                    "output" to JsonArray(emptyList()),
                    "approvalState" to JsonObject(mapOf("type" to JsonPrimitive("auto"))),
                )
            )
        }
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.objectValue(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.arrayValue(key: String): JsonArray? =
    this[key] as? JsonArray

