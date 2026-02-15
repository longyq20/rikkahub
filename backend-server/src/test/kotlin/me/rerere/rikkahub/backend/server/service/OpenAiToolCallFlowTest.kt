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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant

class OpenAiToolCallFlowTest {
    @Test
    fun openAiToolCalls_areConvertedToToolParts_toolsAreAdvertised_andToolResultsAreFedBack() = runBlocking {
        val recordedBodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/chat/completions") { ex ->
                val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                recordedBodies += body

                // If the client already sent tool results (role=tool), return a normal text response.
                val response = if (body.contains("\"tool_call_id\"")) {
                    """
                        {
                          "id": "cmpl_2",
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "done"},
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                        }
                    """.trimIndent()
                } else {
                    """
                        {
                          "id": "cmpl_1",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "",
                                "tool_calls": [
                                  {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {"name": "search_web", "arguments": "{\\\"query\\\":\\\"kotlin ktor\\\"}"}
                                  }
                                ]
                              },
                              "finish_reason": "tool_calls"
                            }
                          ],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                        }
                    """.trimIndent()
                }

                ex.sendJson(200, response)
            }
            server.start()

            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val settings = JsonObject(
                mapOf(
                    "assistantId" to JsonPrimitive("a1"),
                    "chatModelId" to JsonPrimitive("m1"),
                    "enableWebSearch" to JsonPrimitive(true),
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
                                                    "tools" to JsonArray(
                                                        listOf(JsonObject(mapOf("type" to JsonPrimitive("search"))))
                                                    ),
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

            val nodeUser = MessageNodeRecord(
                id = "n1",
                messages = listOf(
                    MessageRecord(
                        id = "u1",
                        role = "USER",
                        parts = listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("hi")))),
                        createdAt = Instant.now().toString(),
                    )
                ),
                selectIndex = 0,
            )

            val conversation = ConversationRecord(
                id = "c1",
                assistantId = "a1",
                title = "",
                messageNodes = listOf(nodeUser),
                createAt = Instant.now().toEpochMilli(),
                updateAt = Instant.now().toEpochMilli(),
            )

            val generator = PortableLlmGenerator()
            val first = generator.generateReply(settings, conversation)

            val tool = first.parts.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "tool" } as? JsonObject
                ?: error("tool part missing")
            assertEquals("search_web", tool["toolName"]!!.jsonPrimitive.content)
            assertEquals("call_1", tool["toolCallId"]!!.jsonPrimitive.content)

            val req1 = recordedBodies.firstOrNull().orEmpty()
            assertTrue(req1.contains("\"tools\""))

            // Now emulate a later request where a previous tool call has already been executed.
            val executedToolPart = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool"),
                    "toolCallId" to JsonPrimitive("call_1"),
                    "toolName" to JsonPrimitive("search_web"),
                    "input" to JsonPrimitive("{\"query\":\"kotlin ktor\"}"),
                    "output" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive("{\"results\":[]}"),
                                )
                            )
                        )
                    ),
                    "approvalState" to JsonObject(mapOf("type" to JsonPrimitive("auto"))),
                )
            )

            val nodeAssistant = MessageNodeRecord(
                id = "n2",
                messages = listOf(
                    MessageRecord(
                        id = "a2",
                        role = "ASSISTANT",
                        parts = listOf(executedToolPart),
                        createdAt = Instant.now().toString(),
                    )
                ),
                selectIndex = 0,
            )

            val conversation2 = ConversationRecord(
                id = "c1",
                assistantId = "a1",
                title = "",
                messageNodes = listOf(nodeUser, nodeAssistant),
                createAt = conversation.createAt,
                updateAt = Instant.now().toEpochMilli(),
            )

            generator.generateReply(settings, conversation2)

            val req2 = recordedBodies.getOrNull(1) ?: error("second request not recorded")
            val json2 = AppJson.parseToJsonElement(req2) as JsonObject
            val messages2 = json2["messages"] as? JsonArray
            assertNotNull(messages2)

            val msgObjs = messages2!!.mapNotNull { it as? JsonObject }
            val assistantMsg = msgObjs.firstOrNull { it["role"]?.jsonPrimitive?.content == "assistant" && it.containsKey("tool_calls") }
            assertNotNull(assistantMsg)

            val toolMsg = msgObjs.firstOrNull { it["role"]?.jsonPrimitive?.content == "tool" }
                ?: error("tool history message missing")
            assertEquals("call_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue(toolMsg["content"]!!.jsonPrimitive.content.contains("\"results\""))
        } finally {
            server.stop(0)
        }
    }


    @Test
    fun mcpTools_areAdvertisedWhenEnabledInSettingsAndAssistant() = runBlocking {
        val recordedBodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/chat/completions") { ex ->
                val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                recordedBodies += body

                val response = """
                    {
                      "id": "cmpl_1",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "ok"},
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
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
                    "enableWebSearch" to JsonPrimitive(false),
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
                                    "mcpServers" to JsonArray(listOf(JsonPrimitive("s1"))),
                                    "tags" to JsonArray(emptyList()),
                                )
                            )
                        )
                    ),
                    "mcpServers" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("s1"),
                                    "type" to JsonPrimitive("streamable_http"),
                                    "url" to JsonPrimitive("http://127.0.0.1:9"),
                                    "commonOptions" to JsonObject(
                                        mapOf(
                                            "enable" to JsonPrimitive(true),
                                            "name" to JsonPrimitive("Demo"),
                                            "tools" to JsonArray(
                                                listOf(
                                                    JsonObject(
                                                        mapOf(
                                                            "enable" to JsonPrimitive(true),
                                                            "name" to JsonPrimitive("demo_tool"),
                                                            "description" to JsonPrimitive("demo"),
                                                            "needsApproval" to JsonPrimitive(true),
                                                            "inputSchema" to JsonObject(
                                                                mapOf(
                                                                    "type" to JsonPrimitive("object"),
                                                                    "properties" to JsonObject(emptyMap()),
                                                                )
                                                            ),
                                                        )
                                                    )
                                                )
                                            ),
                                        )
                                    ),
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
                                parts = listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("hi")))),
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

            val req = recordedBodies.firstOrNull() ?: error("request not recorded")
            val json = AppJson.parseToJsonElement(req) as JsonObject
            val tools = json["tools"] as? JsonArray ?: error("tools missing")

            val toolNames = tools.mapNotNull { it as? JsonObject }
                .mapNotNull { it["function"] as? JsonObject }
                .mapNotNull { it["name"] as? JsonPrimitive }
                .map { it.content }

            assertTrue(toolNames.contains("mcp__demo_tool"))
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