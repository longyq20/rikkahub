package me.rerere.rikkahub.backend.core.api

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationDiffTest {
    @Test
    fun singleNodeDiff_detectsOneChangedNode() {
        val base = conversation(
            messages = listOf(
                node("n1", "m1", "hello"),
                node("n2", "m2", "world"),
            )
        )
        val current = conversation(
            messages = listOf(
                node("n1", "m1", "hello"),
                node("n2", "m2b", "world updated"),
            )
        )

        val diff = base.singleNodeDiffOrNull(current)
        requireNotNull(diff)
        assertEquals(1, diff.nodeIndex)
        assertEquals("n2", diff.node.id)
        assertEquals("m2b", diff.node.messages.first().id)
    }

    @Test
    fun singleNodeDiff_returnsNullWhenMultipleNodesChanged() {
        val base = conversation(
            messages = listOf(
                node("n1", "m1", "hello"),
                node("n2", "m2", "world"),
            )
        )
        val current = conversation(
            messages = listOf(
                node("n1", "m1b", "hello2"),
                node("n2", "m2b", "world2"),
            )
        )

        assertNull(base.singleNodeDiffOrNull(current))
    }

    @Test
    fun singleNodeDiff_returnsNullWhenConversationMetaChanged() {
        val base = conversation(title = "A")
        val current = conversation(title = "B")

        assertNull(base.singleNodeDiffOrNull(current))
    }

    private fun conversation(
        title: String = "title",
        messages: List<MessageNodeDto> = listOf(node("n1", "m1", "hello")),
    ): ConversationDto {
        return ConversationDto(
            id = "c1",
            assistantId = "a1",
            title = title,
            messages = messages,
            truncateIndex = -1,
            chatSuggestions = emptyList(),
            isPinned = false,
            createAt = 1,
            updateAt = 2,
            isGenerating = false,
        )
    }

    private fun node(nodeId: String, messageId: String, text: String): MessageNodeDto {
        return MessageNodeDto(
            id = nodeId,
            messages = listOf(
                MessageDto(
                    id = messageId,
                    role = "USER",
                    parts = listOf(JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("text"), "text" to kotlinx.serialization.json.JsonPrimitive(text)))),
                    createdAt = "2026-01-01T00:00:00Z",
                )
            ),
            selectIndex = 0,
        )
    }
}
