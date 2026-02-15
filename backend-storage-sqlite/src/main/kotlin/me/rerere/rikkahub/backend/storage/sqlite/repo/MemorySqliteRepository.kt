package me.rerere.rikkahub.backend.storage.sqlite.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.backend.core.model.AssistantMemoryRecord
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase

class MemorySqliteRepository(
    private val database: SqliteDatabase,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemoryRecord> = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            val items = mutableListOf<AssistantMemoryRecord>()
            connection.prepareStatement(
                """
                SELECT id, assistant_id, content
                FROM memoryentity
                WHERE assistant_id = ?
                ORDER BY id ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, assistantId)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        items += AssistantMemoryRecord(
                            id = rs.getInt("id"),
                            assistantId = rs.getString("assistant_id"),
                            content = rs.getString("content"),
                        )
                    }
                }
            }
            items
        }
    }

    suspend fun getMemoryById(id: Int): AssistantMemoryRecord? = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, assistant_id, content
                FROM memoryentity
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        AssistantMemoryRecord(
                            id = rs.getInt("id"),
                            assistantId = rs.getString("assistant_id"),
                            content = rs.getString("content"),
                        )
                    }
                }
            }
        }
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemoryRecord = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO memoryentity (assistant_id, content)
                VALUES (?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, assistantId)
                statement.setString(2, content)
                statement.executeUpdate()
                statement.generatedKeys.use { generated ->
                    generated.next()
                    AssistantMemoryRecord(
                        id = generated.getInt(1),
                        assistantId = assistantId,
                        content = content,
                    )
                }
            }
        }
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemoryRecord = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE memoryentity
                SET content = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, content)
                statement.setInt(2, id)
                statement.executeUpdate()
            }
        }

        getMemoryById(id) ?: error("Memory record #$id not found")
    }

    suspend fun deleteMemory(id: Int): Boolean = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement("DELETE FROM memoryentity WHERE id = ?").use { statement ->
                statement.setInt(1, id)
                statement.executeUpdate() > 0
            }
        }
    }
}
