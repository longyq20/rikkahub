package me.rerere.rikkahub.backend.storage.sqlite.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.core.model.MessageNodeRecord
import me.rerere.rikkahub.backend.core.model.MessageRecord
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class ConversationSqliteRepositoryTest {
    @Test
    fun upsertAndGetConversation_roundTrip() = runBlocking {
        val repo = createRepository()
        val now = Instant.now().toEpochMilli()

        val record = ConversationRecord(
            id = "conv-1",
            assistantId = "assistant-1",
            title = "Hello",
            messageNodes = listOf(
                MessageNodeRecord(
                    id = "node-1",
                    messages = listOf(
                        MessageRecord(
                            id = "msg-1",
                            role = "USER",
                            parts = listOf(textPart("hello")),
                            createdAt = Instant.now().toString(),
                        )
                    ),
                    selectIndex = 0,
                )
            ),
            createAt = now,
            updateAt = now,
        )

        repo.upsertConversation(record)

        val loaded = repo.getConversationById("conv-1")
        assertNotNull(loaded)
        assertEquals("Hello", loaded?.title)
        assertEquals(1, loaded?.messageNodes?.size)
        assertEquals("msg-1", loaded?.messageNodes?.first()?.messages?.first()?.id)
    }

    @Test
    fun getConversationsOfAssistantPage_ordersPinnedThenUpdated() = runBlocking {
        val repo = createRepository()
        val base = Instant.now().toEpochMilli()

        repo.upsertConversation(conversation(id = "c-old", title = "old", pinned = false, updateAt = base - 1000))
        repo.upsertConversation(conversation(id = "c-new", title = "new", pinned = false, updateAt = base))
        repo.upsertConversation(conversation(id = "c-pin", title = "pin", pinned = true, updateAt = base - 5000))

        val page = repo.getConversationsOfAssistantPage(
            assistantId = "assistant-1",
            offset = 0,
            limit = 10,
        )

        val ids = page.items.map { it.id }
        assertEquals(listOf("c-pin", "c-new", "c-old"), ids)
    }

    @Test
    fun getConversationsOfAssistantPage_supportsTitleKeyword() = runBlocking {
        val repo = createRepository()
        repo.upsertConversation(conversation(id = "c1", title = "Alpha Plan", pinned = false))
        repo.upsertConversation(conversation(id = "c2", title = "Beta Plan", pinned = false))

        val page = repo.getConversationsOfAssistantPage(
            assistantId = "assistant-1",
            offset = 0,
            limit = 10,
            titleKeyword = "Alpha",
        )

        assertEquals(1, page.items.size)
        assertTrue(page.items.first().title.contains("Alpha"))
    }

    private fun createRepository(): ConversationSqliteRepository {
        val dir = Files.createTempDirectory("repo-test-")
        val db = SqliteDatabase(BackendPaths(dir))
        return ConversationSqliteRepository(db)
    }

    private fun conversation(
        id: String,
        title: String,
        pinned: Boolean,
        updateAt: Long = Instant.now().toEpochMilli(),
    ): ConversationRecord {
        return ConversationRecord(
            id = id,
            assistantId = "assistant-1",
            title = title,
            messageNodes = emptyList(),
            isPinned = pinned,
            createAt = updateAt,
            updateAt = updateAt,
        )
    }

    private fun textPart(text: String): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(text),
            )
        )
    }
}
