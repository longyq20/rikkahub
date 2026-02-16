package me.rerere.rikkahub.backend.server.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.core.model.MessageNodeRecord
import me.rerere.rikkahub.backend.core.model.MessageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant

class OpenAiImageInputTest {
    @Test
    fun uploadedImage_isSentAsOpenAiImageUrlContent() = runBlocking {
        val recordedBodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val dataDir = Files.createTempDirectory("openai-image-input-")

        try {
            val uploadDir = dataDir.resolve("upload")
            Files.createDirectories(uploadDir)
            Files.write(uploadDir.resolve("test.jpg"), byteArrayOf(1, 2, 3, 4, 5))

            server.createContext("/chat/completions") { ex ->
                val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                recordedBodies += body

                val response = """
                    {
                      "id": "cmpl_img_1",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "image received"},
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
                    }
                """.trimIndent()

                ex.sendJson(200, response)
            }
            server.start()

            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val settings = JsonObject(
                mapOf(
                    "assistantId" to JsonPrimitive("a1"),
                    "chatModelId" to JsonPrimitive("m1"),
                    "providers" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("p1"),
                                    "type" to JsonPrimitive("openai"),
                                    "enabled" to JsonPrimitive(true),
                                    "apiKey" to JsonPrimitive("k"),
                                    "baseUrl" to JsonPrimitive(baseUrl),
                                    "chatCompletionsPath" to JsonPrimitive("/chat/completions"),
                                    "models" to JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "id" to JsonPrimitive("m1"),
                                                    "modelId" to JsonPrimitive("gpt-test"),
                                                    "tools" to JsonArray(emptyList()),
                                                )
                                            )
                                        )
                                    ),
                                )
                            )
                        )
                    ),
                    "assistants" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("a1"),
                                    "name" to JsonPrimitive("A"),
                                    "chatModelId" to JsonPrimitive("m1"),
                                    "tags" to JsonArray(emptyList()),
                                )
                            )
                        )
                    ),
                )
            )

            val conversation = ConversationRecord(
                id = "c1",
                assistantId = "a1",
                title = "",
                messageNodes = listOf(
                    MessageNodeRecord(
                        id = "n1",
                        messages = listOf(
                            MessageRecord(
                                id = "u1",
                                role = "USER",
                                parts = listOf(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive("what is in this image?"),
                                        )
                                    ),
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("image"),
                                            "url" to JsonPrimitive("upload/test.jpg"),
                                        )
                                    ),
                                ),
                                createdAt = Instant.now().toString(),
                            )
                        ),
                        selectIndex = 0,
                    )
                ),
                createAt = Instant.now().toEpochMilli(),
                updateAt = Instant.now().toEpochMilli(),
            )

            val generator = PortableLlmGenerator(dataDir = dataDir)
            val result = generator.generateReply(settings, conversation)

            assertEquals("image received", result.parts.first()["text"]?.jsonPrimitive?.content)

            val request = recordedBodies.firstOrNull() ?: error("request not recorded")
            val json = AppJson.parseToJsonElement(request) as JsonObject
            val messages = json["messages"] as? JsonArray ?: error("messages missing")
            val userMessage = messages
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["role"]?.jsonPrimitive?.content == "user" }
                ?: error("user message missing")

            val content = userMessage["content"] as? JsonArray ?: error("user content should be array")
            val contentItems = content.mapNotNull { it as? JsonObject }

            val hasText = contentItems.any {
                it["type"]?.jsonPrimitive?.content == "text" &&
                    it["text"]?.jsonPrimitive?.content?.contains("what is in this image?") == true
            }
            val hasImage = contentItems.any {
                val type = it["type"]?.jsonPrimitive?.content
                val imageUrl = (it["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.content.orEmpty()
                type == "image_url" && imageUrl.startsWith("data:image/jpeg;base64,")
            }

            assertTrue(hasText)
            assertTrue(hasImage)
        } finally {
            server.stop(0)
            runCatching { dataDir.toFile().deleteRecursively() }
        }
    }


    @Test
    fun messageTemplate_isAppliedBeforeOpenAiRequest() = runBlocking {
        val recordedBodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        try {
            server.createContext("/chat/completions") { ex ->
                val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                recordedBodies += body

                val response = """
                    {
                      "id": "cmpl_tpl_1",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "ok"},
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
                    }
                """.trimIndent()

                ex.sendJson(200, response)
            }
            server.start()

            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val settings = JsonObject(
                mapOf(
                    "assistantId" to JsonPrimitive("a1"),
                    "chatModelId" to JsonPrimitive("m1"),
                    "providers" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("p1"),
                                    "type" to JsonPrimitive("openai"),
                                    "enabled" to JsonPrimitive(true),
                                    "apiKey" to JsonPrimitive("k"),
                                    "baseUrl" to JsonPrimitive(baseUrl),
                                    "chatCompletionsPath" to JsonPrimitive("/chat/completions"),
                                    "models" to JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "id" to JsonPrimitive("m1"),
                                                    "modelId" to JsonPrimitive("gpt-test"),
                                                    "tools" to JsonArray(emptyList()),
                                                )
                                            )
                                        )
                                    ),
                                )
                            )
                        )
                    ),
                    "assistants" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("a1"),
                                    "name" to JsonPrimitive("A"),
                                    "chatModelId" to JsonPrimitive("m1"),
                                    "messageTemplate" to JsonPrimitive("{{ message }}\\n\\nnotes: 鐜板湪鐨勬椂闂存槸 {{ time }} {{ date }}"),
                                    "tags" to JsonArray(emptyList()),
                                )
                            )
                        )
                    ),
                )
            )

            val conversation = ConversationRecord(
                id = "c1",
                assistantId = "a1",
                title = "",
                messageNodes = listOf(
                    MessageNodeRecord(
                        id = "n1",
                        messages = listOf(
                            MessageRecord(
                                id = "u1",
                                role = "USER",
                                parts = listOf(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive("Hello template"),
                                        )
                                    )
                                ),
                                createdAt = Instant.now().toString(),
                            )
                        ),
                        selectIndex = 0,
                    )
                ),
                createAt = Instant.now().toEpochMilli(),
                updateAt = Instant.now().toEpochMilli(),
            )

            val generator = PortableLlmGenerator()
            generator.generateReply(settings, conversation)

            val request = recordedBodies.firstOrNull() ?: error("request not recorded")
            val json = AppJson.parseToJsonElement(request) as JsonObject
            val messages = json["messages"] as? JsonArray ?: error("messages missing")
            val userMessage = messages
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["role"]?.jsonPrimitive?.content == "user" }
                ?: error("user message missing")

            val content = userMessage["content"]?.jsonPrimitive?.content ?: error("user content should be text")
            assertTrue(content.contains("Hello template"))
            assertTrue(content.contains("notes: 鐜板湪鐨勬椂闂存槸"))
            assertFalse(content.contains("{{ time }}"))
            assertFalse(content.contains("{{ date }}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun markdownDataImage_isParsedAsImagePart() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        try {
            val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO6V3JQAAAAASUVORK5CYII="

            server.createContext("/chat/completions") { ex ->
                val response = """
                    {
                      "id": "cmpl_img_markdown_1",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "Here is an image: ![Generated Image](data:image/png;base64,$pngBase64)"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
                    }
                """.trimIndent()

                ex.sendJson(200, response)
            }
            server.start()

            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val settings = JsonObject(
                mapOf(
                    "assistantId" to JsonPrimitive("a1"),
                    "chatModelId" to JsonPrimitive("m1"),
                    "providers" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("p1"),
                                    "type" to JsonPrimitive("openai"),
                                    "enabled" to JsonPrimitive(true),
                                    "apiKey" to JsonPrimitive("k"),
                                    "baseUrl" to JsonPrimitive(baseUrl),
                                    "chatCompletionsPath" to JsonPrimitive("/chat/completions"),
                                    "models" to JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "id" to JsonPrimitive("m1"),
                                                    "modelId" to JsonPrimitive("gpt-test"),
                                                    "tools" to JsonArray(emptyList()),
                                                )
                                            )
                                        )
                                    ),
                                )
                            )
                        )
                    ),
                    "assistants" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("a1"),
                                    "name" to JsonPrimitive("A"),
                                    "chatModelId" to JsonPrimitive("m1"),
                                    "tags" to JsonArray(emptyList()),
                                )
                            )
                        )
                    ),
                )
            )

            val conversation = ConversationRecord(
                id = "c1",
                assistantId = "a1",
                title = "",
                messageNodes = listOf(
                    MessageNodeRecord(
                        id = "n1",
                        messages = listOf(
                            MessageRecord(
                                id = "u1",
                                role = "USER",
                                parts = listOf(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive("please generate image"),
                                        )
                                    )
                                ),
                                createdAt = Instant.now().toString(),
                            )
                        ),
                        selectIndex = 0,
                    )
                ),
                createAt = Instant.now().toEpochMilli(),
                updateAt = Instant.now().toEpochMilli(),
            )

            val generator = PortableLlmGenerator()
            val result = generator.generateReply(settings, conversation)

            val hasImage = result.parts.any {
                it["type"]?.jsonPrimitive?.content == "image" &&
                    it["url"]?.jsonPrimitive?.content?.startsWith("data:image/png;base64,") == true
            }
            val hasLeakedBase64Text = result.parts.any {
                it["type"]?.jsonPrimitive?.content == "text" &&
                    it["text"]?.jsonPrimitive?.content?.contains("data:image/png;base64,") == true
            }

            assertTrue(hasImage)
            assertFalse(hasLeakedBase64Text)
        } finally {
            server.stop(0)
        }
    }
    private fun HttpExchange.sendJson(code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}




