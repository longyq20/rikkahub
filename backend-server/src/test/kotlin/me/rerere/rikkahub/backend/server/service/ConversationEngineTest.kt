package me.rerere.rikkahub.backend.server.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

        val toolApprovalType = updated.messageNodes[1]
            .messages
            .first()
            .parts
            .first()
            .objectValue("approvalState")
            ?.stringValue("type")
        assertEquals("approved", toolApprovalType)
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
        repeat(80) {
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

    private class FakeGenerator(private val reply: String) : LlmGenerator {
        override suspend fun generateReply(
            settings: JsonObject,
            conversation: ConversationRecord,
        ): AssistantGenerationResult {
            return AssistantGenerationResult(
                parts = listOf(textPart(reply)),
                modelId = "model-1",
                usage = null,
            )
        }

        override suspend fun generateTitle(settings: JsonObject, conversation: ConversationRecord): String? {
            return "Generated title"
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

        private fun pendingToolPart(toolCallId: String): JsonObject {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool"),
                    "toolCallId" to JsonPrimitive(toolCallId),
                    "toolName" to JsonPrimitive("demo_tool"),
                    "input" to JsonPrimitive("{}"),
                    "output" to kotlinx.serialization.json.JsonArray(emptyList()),
                    "approvalState" to JsonObject(mapOf("type" to JsonPrimitive("pending"))),
                )
            )
        }
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.objectValue(key: String): JsonObject? =
    this[key] as? JsonObject
