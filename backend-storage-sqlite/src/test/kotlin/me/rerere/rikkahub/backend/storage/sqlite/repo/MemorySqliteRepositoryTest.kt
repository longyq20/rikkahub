package me.rerere.rikkahub.backend.storage.sqlite.repo

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MemorySqliteRepositoryTest {
    @Test
    fun addUpdateDelete_memoryRoundTrip() = runBlocking {
        val repo = createRepository()

        val created = repo.addMemory(assistantId = "assistant-1", content = "old")
        assertTrue(created.id > 0)
        assertEquals("assistant-1", created.assistantId)

        val updated = repo.updateContent(created.id, "new")
        assertEquals(created.id, updated.id)
        assertEquals("new", updated.content)

        val loaded = repo.getMemoryById(created.id)
        assertNotNull(loaded)
        assertEquals("new", loaded?.content)

        val deleted = repo.deleteMemory(created.id)
        assertTrue(deleted)
        assertNull(repo.getMemoryById(created.id))
    }

    @Test
    fun getMemoriesOfAssistant_filtersByAssistantId() = runBlocking {
        val repo = createRepository()
        repo.addMemory(assistantId = "assistant-1", content = "a")
        repo.addMemory(assistantId = "assistant-2", content = "b")

        val a = repo.getMemoriesOfAssistant("assistant-1")
        val b = repo.getMemoriesOfAssistant("assistant-2")

        assertEquals(1, a.size)
        assertEquals("a", a.first().content)
        assertEquals(1, b.size)
        assertEquals("b", b.first().content)
    }

    private fun createRepository(): MemorySqliteRepository {
        val dir = Files.createTempDirectory("memory-repo-test-")
        val db = SqliteDatabase(BackendPaths(dir))
        return MemorySqliteRepository(db)
    }
}
