package me.rerere.rikkahub.backend.storage.sqlite.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.core.model.MessageNodeRecord
import me.rerere.rikkahub.backend.core.model.MessageRecord
import me.rerere.rikkahub.backend.core.util.randomId
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import java.time.Instant

class ConversationSqliteRepository(
    private val database: SqliteDatabase,
) {
    data class ConversationPageResult(
        val items: List<ConversationRecord>,
        val nextOffset: Int?,
    )

    suspend fun getConversationsOfAssistantPage(
        assistantId: String,
        offset: Int,
        limit: Int,
        titleKeyword: String = "",
    ): ConversationPageResult = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            val items = mutableListOf<ConversationRecord>()
            val sql = if (titleKeyword.isBlank()) {
                """
                SELECT id, assistant_id, title, create_at, update_at, truncate_index, suggestions, is_pinned
                FROM conversationentity
                WHERE assistant_id = ?
                ORDER BY is_pinned DESC, update_at DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            } else {
                """
                SELECT id, assistant_id, title, create_at, update_at, truncate_index, suggestions, is_pinned
                FROM conversationentity
                WHERE assistant_id = ? AND title LIKE ?
                ORDER BY is_pinned DESC, update_at DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            }

            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, assistantId)
                if (titleKeyword.isBlank()) {
                    statement.setInt(2, limit + 1)
                    statement.setInt(3, offset)
                } else {
                    statement.setString(2, "%$titleKeyword%")
                    statement.setInt(3, limit + 1)
                    statement.setInt(4, offset)
                }

                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        items += ConversationRecord(
                            id = rs.getString("id"),
                            assistantId = rs.getString("assistant_id"),
                            title = rs.getString("title"),
                            messageNodes = emptyList(),
                            truncateIndex = rs.getInt("truncate_index"),
                            chatSuggestions = decodeSuggestions(rs.getString("suggestions")),
                            isPinned = rs.getInt("is_pinned") == 1,
                            createAt = rs.getLong("create_at"),
                            updateAt = rs.getLong("update_at"),
                        )
                    }
                }
            }

            val hasMore = items.size > limit
            val nextOffset = if (hasMore) offset + limit else null
            ConversationPageResult(
                items = if (hasMore) items.take(limit) else items,
                nextOffset = nextOffset,
            )
        }
    }

    suspend fun getConversationById(id: String): ConversationRecord? = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            val conversation = connection.prepareStatement(
                """
                SELECT id, assistant_id, title, create_at, update_at, truncate_index, suggestions, is_pinned
                FROM conversationentity
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        ConversationRecord(
                            id = rs.getString("id"),
                            assistantId = rs.getString("assistant_id"),
                            title = rs.getString("title"),
                            messageNodes = emptyList(),
                            truncateIndex = rs.getInt("truncate_index"),
                            chatSuggestions = decodeSuggestions(rs.getString("suggestions")),
                            isPinned = rs.getInt("is_pinned") == 1,
                            createAt = rs.getLong("create_at"),
                            updateAt = rs.getLong("update_at"),
                        )
                    }
                }
            } ?: return@withConnection null

            val nodes = mutableListOf<MessageNodeRecord>()
            connection.prepareStatement(
                """
                SELECT id, node_index, messages, select_index
                FROM message_node
                WHERE conversation_id = ?
                ORDER BY node_index ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        nodes += MessageNodeRecord(
                            id = rs.getString("id"),
                            messages = decodeMessages(rs.getString("messages")),
                            selectIndex = rs.getInt("select_index"),
                        )
                    }
                }
            }

            conversation.copy(messageNodes = nodes)
        }
    }

    suspend fun upsertConversation(conversation: ConversationRecord) = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO conversationentity (
                        id, assistant_id, title, nodes, create_at, update_at, truncate_index, suggestions, is_pinned
                    ) VALUES (?, ?, ?, '[]', ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        assistant_id = excluded.assistant_id,
                        title = excluded.title,
                        update_at = excluded.update_at,
                        truncate_index = excluded.truncate_index,
                        suggestions = excluded.suggestions,
                        is_pinned = excluded.is_pinned
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, conversation.id)
                    statement.setString(2, conversation.assistantId)
                    statement.setString(3, conversation.title)
                    statement.setLong(4, conversation.createAt)
                    statement.setLong(5, conversation.updateAt)
                    statement.setInt(6, conversation.truncateIndex)
                    statement.setString(7, AppJson.encodeToString(JsonArray.serializer(), JsonArray(conversation.chatSuggestions.map(::JsonPrimitive))))
                    statement.setInt(8, if (conversation.isPinned) 1 else 0)
                    statement.executeUpdate()
                }

                connection.prepareStatement("DELETE FROM message_node WHERE conversation_id = ?").use { statement ->
                    statement.setString(1, conversation.id)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO message_node (id, conversation_id, node_index, messages, select_index)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    conversation.messageNodes.forEachIndexed { index, node ->
                        statement.setString(1, node.id)
                        statement.setString(2, conversation.id)
                        statement.setInt(3, index)
                        statement.setString(4, encodeMessages(node.messages))
                        statement.setInt(5, node.selectIndex)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    suspend fun deleteConversationById(id: String): Boolean = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement("DELETE FROM conversationentity WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate() > 0
            }
        }
    }

    suspend fun countConversations(): Int = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM conversationentity").use { statement ->
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    suspend fun countMessageNodes(): Int = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM message_node").use { statement ->
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    private fun decodeSuggestions(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val element = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return element.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun decodeMessages(raw: String?): List<MessageRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val element = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return element.mapNotNull { json ->
            val message = json as? JsonObject ?: return@mapNotNull null
            MessageRecord(
                id = message.stringValue("id") ?: randomId(),
                role = message.stringValue("role") ?: "USER",
                parts = (message["parts"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList(),
                annotations = (message["annotations"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList(),
                createdAt = message.stringValue("createdAt") ?: Instant.now().toString(),
                finishedAt = message.stringValue("finishedAt"),
                modelId = message.stringValue("modelId"),
                usage = message["usage"],
                translation = message.stringValue("translation"),
            )
        }
    }

    private fun encodeMessages(messages: List<MessageRecord>): String {
        val array = JsonArray(messages.map { message ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(message.id),
                    "role" to JsonPrimitive(message.role),
                    "parts" to JsonArray(message.parts),
                    "annotations" to JsonArray(message.annotations),
                    "createdAt" to JsonPrimitive(message.createdAt),
                    "finishedAt" to (message.finishedAt?.let(::JsonPrimitive) ?: JsonPrimitive("")),
                    "modelId" to (message.modelId?.let(::JsonPrimitive) ?: JsonPrimitive("")),
                    "usage" to (message.usage ?: JsonPrimitive("")),
                    "translation" to (message.translation?.let(::JsonPrimitive) ?: JsonPrimitive("")),
                ).filterValues { it != JsonPrimitive("") }
            )
        })
        return AppJson.encodeToString(JsonArray.serializer(), array)
    }
}