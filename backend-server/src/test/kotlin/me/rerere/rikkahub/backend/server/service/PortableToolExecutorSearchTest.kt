package me.rerere.rikkahub.backend.server.service

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import me.rerere.rikkahub.backend.storage.sqlite.repo.MemorySqliteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PortableToolExecutorSearchTest {
    @Test
    fun searchWeb_usesSelectedSearxngServiceWithoutAuth() = runBlocking {
        var requestPath: String? = null
        var authHeader: String? = null

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/search") { exchange ->
                requestPath = exchange.requestURI.toString()
                authHeader = exchange.requestHeaders.getFirst("Authorization")

                val body = """
                    {
                      "query": "kotlin",
                      "results": [
                        {"url": "https://example.com/a", "title": "A", "content": "alpha"},
                        {"url": "https://example.com/b", "title": "B", "content": "beta"}
                      ]
                    }
                """.trimIndent()

                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()

            val dataDir = Files.createTempDirectory("tool-search-test-")
            val paths = BackendPaths(dataDir)
            val database = SqliteDatabase(paths)
            val memoryRepository = MemorySqliteRepository(database)
            val executor = PortableToolExecutor(memoryRepository)

            val settings = JsonObject(
                mapOf(
                    "searchServiceSelected" to JsonPrimitive(0),
                    "searchCommonOptions" to JsonObject(
                        mapOf("resultSize" to JsonPrimitive(10))
                    ),
                    "searchServices" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("svc-1"),
                                    "type" to JsonPrimitive("searxng"),
                                    "url" to JsonPrimitive("http://127.0.0.1:${server.address.port}"),
                                    "engines" to JsonPrimitive(""),
                                    "language" to JsonPrimitive(""),
                                    "username" to JsonPrimitive(""),
                                    "password" to JsonPrimitive(""),
                                )
                            )
                        )
                    ),
                )
            )

            val output = executor.execute(
                settings = settings,
                assistantId = "assistant-1",
                toolName = "search_web",
                input = "{\"query\":\"kotlin\"}",
            )

            val text = output.firstOrNull()?.stringValue("text")
            assertNotNull(text)

            val payload = AppJson.parseToJsonElement(text!!) as JsonObject
            val items = payload["items"] as? JsonArray
            assertNotNull(items)
            assertEquals(2, items!!.size)

            assertNotNull(requestPath)
            assertTrue(requestPath?.startsWith("/search?") == true)
            assertTrue(requestPath?.contains("format=json") == true)
            assertTrue(requestPath?.contains("q=kotlin") == true)
            assertNull(authHeader)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun searchWeb_usesSelectedExaServiceWithBearerAuth() = runBlocking {
        var authHeader: String? = null
        var method: String? = null
        var requestBody: String? = null

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/search") { exchange ->
                method = exchange.requestMethod
                authHeader = exchange.requestHeaders.getFirst("Authorization")
                requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

                val body = """
                    {
                      "results": [
                        {"url": "https://exa.example/1", "title": "Exa 1", "text": "one"},
                        {"url": "https://exa.example/2", "title": "Exa 2", "text": "two"}
                      ]
                    }
                """.trimIndent()

                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()

            val dataDir = Files.createTempDirectory("tool-search-exa-test-")
            val paths = BackendPaths(dataDir)
            val database = SqliteDatabase(paths)
            val memoryRepository = MemorySqliteRepository(database)
            val executor = PortableToolExecutor(memoryRepository)

            val settings = JsonObject(
                mapOf(
                    "searchServiceSelected" to JsonPrimitive(0),
                    "searchCommonOptions" to JsonObject(
                        mapOf("resultSize" to JsonPrimitive(10))
                    ),
                    "searchServices" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("svc-exa"),
                                    "type" to JsonPrimitive("exa"),
                                    "apiKey" to JsonPrimitive("exa-test-key"),
                                    "url" to JsonPrimitive("http://127.0.0.1:${server.address.port}"),
                                )
                            )
                        )
                    ),
                )
            )

            val output = executor.execute(
                settings = settings,
                assistantId = "assistant-1",
                toolName = "search_web",
                input = "{\"query\":\"kotlin\"}",
            )

            val text = output.firstOrNull()?.stringValue("text")
            assertNotNull(text)

            val payload = AppJson.parseToJsonElement(text!!) as JsonObject
            val items = payload["items"] as? JsonArray
            assertNotNull(items)
            assertEquals(2, items!!.size)

            assertEquals("Bearer exa-test-key", authHeader)
            assertEquals("POST", method)
            assertTrue(requestBody?.contains("\"query\":\"kotlin\"") == true)
            assertTrue(requestBody?.contains("\"numResults\":10") == true)
        } finally {
            server.stop(0)
        }
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
