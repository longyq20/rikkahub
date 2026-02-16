package me.rerere.rikkahub.backend.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUsageNormalizationTest {
    @Test
    fun toDto_normalizesOpenAiSnakeCaseUsage() {
        val record = messageRecord(
            usage = JsonObject(
                mapOf(
                    "prompt_tokens" to JsonPrimitive(120),
                    "completion_tokens" to JsonPrimitive(45),
                    "total_tokens" to JsonPrimitive(165),
                    "prompt_tokens_details" to JsonObject(
                        mapOf("cached_tokens" to JsonPrimitive(30))
                    ),
                )
            )
        )

        val usage = record.toDto().usage as? JsonObject
        requireNotNull(usage)

        assertEquals(120L, usage.long("promptTokens"))
        assertEquals(45L, usage.long("completionTokens"))
        assertEquals(30L, usage.long("cachedTokens"))
        assertEquals(165L, usage.long("totalTokens"))
    }

    @Test
    fun toDto_normalizesGoogleUsageMetadata() {
        val record = messageRecord(
            usage = JsonObject(
                mapOf(
                    "promptTokenCount" to JsonPrimitive(80),
                    "candidatesTokenCount" to JsonPrimitive(20),
                    "totalTokenCount" to JsonPrimitive(100),
                )
            )
        )

        val usage = record.toDto().usage as? JsonObject
        requireNotNull(usage)

        assertEquals(80L, usage.long("promptTokens"))
        assertEquals(20L, usage.long("completionTokens"))
        assertEquals(0L, usage.long("cachedTokens"))
        assertEquals(100L, usage.long("totalTokens"))
    }

    @Test
    fun toDto_keepsCamelCaseAndComputesTotalWhenMissing() {
        val record = messageRecord(
            usage = JsonObject(
                mapOf(
                    "promptTokens" to JsonPrimitive(10),
                    "completionTokens" to JsonPrimitive(2),
                    "cachedTokens" to JsonPrimitive(1),
                )
            )
        )

        val usage = record.toDto().usage as? JsonObject
        requireNotNull(usage)

        assertEquals(10L, usage.long("promptTokens"))
        assertEquals(2L, usage.long("completionTokens"))
        assertEquals(1L, usage.long("cachedTokens"))
        assertEquals(12L, usage.long("totalTokens"))
    }

    @Test
    fun toDto_returnsNullForUnrecognizedUsageShape() {
        val record = messageRecord(
            usage = JsonObject(
                mapOf(
                    "foo" to JsonPrimitive("bar"),
                )
            )
        )

        assertNull(record.toDto().usage)
    }

    @Test
    fun toDto_clampsNegativeValuesToZero() {
        val record = messageRecord(
            usage = JsonObject(
                mapOf(
                    "prompt_tokens" to JsonPrimitive(-1),
                    "completion_tokens" to JsonPrimitive(-2),
                    "total_tokens" to JsonPrimitive(-3),
                    "prompt_tokens_details" to JsonObject(mapOf("cached_tokens" to JsonPrimitive(-4))),
                )
            )
        )

        val usage = record.toDto().usage as? JsonObject
        requireNotNull(usage)

        assertEquals(0L, usage.long("promptTokens"))
        assertEquals(0L, usage.long("completionTokens"))
        assertEquals(0L, usage.long("cachedTokens"))
        assertEquals(0L, usage.long("totalTokens"))
        assertTrue(usage.keys.containsAll(listOf("promptTokens", "completionTokens", "cachedTokens", "totalTokens")))
    }

    private fun messageRecord(usage: JsonObject?): MessageRecord {
        return MessageRecord(
            id = "m1",
            role = "ASSISTANT",
            parts = listOf(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive("hello"),
                    )
                )
            ),
            createdAt = "2026-01-01T00:00:00Z",
            usage = usage,
        )
    }

    private fun JsonObject.long(key: String): Long {
        return (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: error("missing long key: $key")
    }
}
