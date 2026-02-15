package me.rerere.rikkahub.backend.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.ConversationRecord
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import me.rerere.rikkahub.backend.storage.sqlite.repo.ConversationSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ApiContractIntegrationTest {
    @Test
    fun conversationsPaged_validatesParamsAndSupportsQuery() {
        val fixture = createFixture()
        val assistantId = fixture.settingsRepository.currentAssistantId()
        runBlocking {
            fixture.conversationRepository.upsertConversation(
                ConversationRecord(
                    id = "11111111-1111-1111-1111-111111111111",
                    assistantId = assistantId,
                    title = "Pinned Conversation",
                    messageNodes = emptyList(),
                    isPinned = true,
                    createAt = 1,
                    updateAt = 300,
                )
            )
            fixture.conversationRepository.upsertConversation(
                ConversationRecord(
                    id = "22222222-2222-2222-2222-222222222222",
                    assistantId = assistantId,
                    title = "Alpha Topic",
                    messageNodes = emptyList(),
                    createAt = 2,
                    updateAt = 200,
                )
            )
            fixture.conversationRepository.upsertConversation(
                ConversationRecord(
                    id = "33333333-3333-3333-3333-333333333333",
                    assistantId = assistantId,
                    title = "Beta Topic",
                    messageNodes = emptyList(),
                    createAt = 3,
                    updateAt = 100,
                )
            )
        }

        try {
            testApplication {
                application { rikkaBackendModule(fixture.config) }

                val invalidLimit = client.get("/api/conversations/paged?offset=0&limit=101")
                assertEquals(HttpStatusCode.BadRequest, invalidLimit.status)

                val pageResponse = client.get("/api/conversations/paged?offset=0&limit=2")
                assertEquals(HttpStatusCode.OK, pageResponse.status)
                val pageBody = pageResponse.bodyAsJsonObject()
                val items = pageBody.array("items")
                assertEquals(2, items.size)
                assertEquals("11111111-1111-1111-1111-111111111111", items.objectAt(0).string("id"))
                assertEquals(2, pageBody.int("nextOffset"))

                val queryResponse = client.get("/api/conversations/paged?offset=0&limit=20&query=Alpha")
                assertEquals(HttpStatusCode.OK, queryResponse.status)
                val queryItems = queryResponse.bodyAsJsonObject().array("items")
                assertEquals(1, queryItems.size)
                assertEquals("22222222-2222-2222-2222-222222222222", queryItems.objectAt(0).string("id"))
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun conversationCrudAndSendMessageRoutes_workForHappyAndErrorPaths() {
        val fixture = createFixture()
        val conversationId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

        try {
            testApplication {
                application { rikkaBackendModule(fixture.config) }

                val invalidId = client.get("/api/conversations/not-a-uuid")
                assertEquals(HttpStatusCode.BadRequest, invalidId.status)

                val notFound = client.get("/api/conversations/$conversationId")
                assertEquals(HttpStatusCode.NotFound, notFound.status)

                val sendResponse = client.post("/api/conversations/$conversationId/messages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"parts":[{"type":"text","text":"hello portable backend"}]}""")
                }
                assertEquals(HttpStatusCode.Accepted, sendResponse.status)

                val detailResponse = client.get("/api/conversations/$conversationId")
                assertEquals(HttpStatusCode.OK, detailResponse.status)
                val conversation = detailResponse.bodyAsJsonObject()
                assertEquals(conversationId, conversation.string("id"))
                assertTrue(conversation.array("messages").isNotEmpty())

                val deleteResponse = client.delete("/api/conversations/$conversationId")
                assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

                val deletedMissing = client.get("/api/conversations/$conversationId")
                assertEquals(HttpStatusCode.NotFound, deletedMissing.status)
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun settingsStream_emitsUpdateAndReflectsMutations() {
        val fixture = createFixture()
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        try {
            withRunningServer(fixture) { baseUrl ->
                openSseConnection(httpClient, "$baseUrl/api/settings/stream").use { settingsSse ->
                    val first = readSseEvent(settingsSse.reader)
                    assertEquals("update", first.event)
                    assertTrue(first.data.toJsonObject().containsKey("assistantId"))

                    val updateResponse = postJson(httpClient, "$baseUrl/api/settings/search/enabled", """{"enabled":true}""")
                    assertEquals(200, updateResponse.statusCode())

                    val second = readSseEvent(settingsSse.reader)
                    assertEquals("update", second.event)
                    assertTrue(second.data.toJsonObject().boolean("enableWebSearch"))
                }
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun conversationStreams_emitInvalidateSnapshotNodeUpdateAndError() {
        val fixture = createFixture()
        val conversationId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        runBlocking {
            fixture.conversationRepository.upsertConversation(
                ConversationRecord(
                    id = conversationId,
                    assistantId = fixture.settingsRepository.currentAssistantId(),
                    title = "Seed title",
                    messageNodes = emptyList(),
                    createAt = 10,
                    updateAt = 10,
                )
            )
        }

        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        try {
            withRunningServer(fixture) { baseUrl ->
                openSseConnection(httpClient, "$baseUrl/api/conversations/stream").use { listSse ->
                    openSseConnection(httpClient, "$baseUrl/api/conversations/$conversationId/stream").use { detailSse ->
                        val initialInvalidate = readSseEvent(listSse.reader)
                        assertEquals("invalidate", initialInvalidate.event)
                        assertTrue(initialInvalidate.data.toJsonObject().containsKey("assistantId"))

                        val snapshot = readSseEvent(detailSse.reader)
                        assertEquals("snapshot", snapshot.event)
                        assertEquals(conversationId, snapshot.data.toJsonObject().objectValue("conversation").string("id"))

                        val switchedAssistantId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
                        val switchResponse = postJson(
                            httpClient,
                            "$baseUrl/api/settings/assistant",
                            """{"assistantId":"$switchedAssistantId"}""",
                        )
                        assertEquals(200, switchResponse.statusCode())

                        var sawSwitchedInvalidate = false
                        for (attempt in 0 until 6) {
                            val nextInvalidate = readSseEvent(listSse.reader)
                            if (nextInvalidate.event != "invalidate") {
                                continue
                            }
                            val assistantId = nextInvalidate.data.toJsonObject().string("assistantId")
                            if (assistantId == switchedAssistantId) {
                                sawSwitchedInvalidate = true
                                break
                            }
                        }
                        assertTrue("expected invalidate for switched assistant", sawSwitchedInvalidate)

                        val sendResponse = postJson(
                            httpClient,
                            "$baseUrl/api/conversations/$conversationId/messages",
                            """{"parts":[{"type":"text","text":"trigger stream events"}]}""",
                        )
                        assertEquals(202, sendResponse.statusCode())

                        var sawNodeUpdate = false
                        var sawError = false
                        for (attempt in 0 until 12) {
                            val event = readSseEvent(detailSse.reader, timeoutMillis = 8_000)
                            when (event.event) {
                                "node_update" -> {
                                    sawNodeUpdate = true
                                    assertEquals(conversationId, event.data.toJsonObject().string("conversationId"))
                                }

                                "error" -> {
                                    sawError = true
                                    val message = event.data.toJsonObject().string("message")
                                    assertFalse(message.isNullOrBlank())
                                }
                            }
                            if (sawNodeUpdate && sawError) {
                                break
                            }
                        }

                        assertTrue("expected node_update event", sawNodeUpdate)
                        assertTrue("expected error event", sawError)
                    }
                }
            }
        } finally {
            fixture.cleanup()
        }
    }

    private data class SseEvent(val event: String, val data: String)

    private class SseConnection(
        private val stream: InputStream,
        val reader: BufferedReader,
    ) : AutoCloseable {
        override fun close() {
            runCatching { reader.close() }
            runCatching { stream.close() }
        }
    }

    private data class TestFixture(
        val config: ServerConfig,
        val dataDir: Path,
        val webDir: Path,
        val assetsDir: Path,
        val settingsRepository: SettingsJsonRepository,
        val conversationRepository: ConversationSqliteRepository,
    ) {
        fun cleanup() {
            dataDir.toFile().deleteRecursively()
            webDir.toFile().deleteRecursively()
            assetsDir.toFile().deleteRecursively()
        }
    }

    private fun createFixture(jwtEnabled: Boolean = false): TestFixture {
        val dataDir = Files.createTempDirectory("backend-api-test-")
        val webDir = Files.createTempDirectory("backend-web-test-")
        val assetsDir = Files.createTempDirectory("backend-assets-test-")
        Files.writeString(webDir.resolve("index.html"), "<html><body>test</body></html>")

        val accessPassword = if (jwtEnabled) "test-password" else ""
        val config = ServerConfig(
            host = "127.0.0.1",
            port = 18080,
            dataDir = dataDir,
            webUiDir = webDir,
            assetsDir = assetsDir,
            jwtEnabled = jwtEnabled,
            accessPassword = accessPassword,
            uploadMaxBytes = 20 * 1024 * 1024,
            version = "test",
        )

        val backendPaths = BackendPaths(dataDir)
        val database = SqliteDatabase(backendPaths)
        val settingsRepository = SettingsJsonRepository(
            paths = backendPaths,
            jwtEnabled = jwtEnabled,
            accessPassword = accessPassword,
        )
        val conversationRepository = ConversationSqliteRepository(database)

        return TestFixture(
            config = config,
            dataDir = dataDir,
            webDir = webDir,
            assetsDir = assetsDir,
            settingsRepository = settingsRepository,
            conversationRepository = conversationRepository,
        )
    }

    private fun withRunningServer(fixture: TestFixture, block: (baseUrl: String) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val runtimeConfig = fixture.config.copy(host = "127.0.0.1", port = port)
        val baseUrl = "http://127.0.0.1:$port"
        val server = embeddedServer(CIO, host = runtimeConfig.host, port = runtimeConfig.port) {
            rikkaBackendModule(runtimeConfig)
        }
        try {
            server.start(wait = false)
            val probeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            waitForServerReady(probeClient, "$baseUrl/api/system/health")
            block(baseUrl)
        } finally {
            server.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        }
    }

    private fun waitForServerReady(client: HttpClient, healthUrl: String) {
        repeat(60) {
            val ok = runCatching {
                val request = HttpRequest.newBuilder(URI.create(healthUrl)).GET().build()
                client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
            }.getOrDefault(false)
            if (ok) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Server did not become ready: $healthUrl")
    }

    private fun openSseConnection(client: HttpClient, url: String): SseConnection {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(200, response.statusCode())
        val stream = response.body()
        val reader = stream.bufferedReader(StandardCharsets.UTF_8)
        return SseConnection(stream = stream, reader = reader)
    }

    private fun postJson(client: HttpClient, url: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun readSseEvent(reader: BufferedReader, timeoutMillis: Long = 5_000): SseEvent {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var eventName = "message"
        val dataLines = mutableListOf<String>()

        while (System.currentTimeMillis() <= deadline) {
            if (!reader.ready()) {
                Thread.sleep(10)
                continue
            }

            val rawLine = reader.readLine() ?: throw AssertionError("SSE stream closed unexpectedly")
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) {
                    return SseEvent(event = eventName, data = dataLines.joinToString("\n"))
                }
                eventName = "message"
                continue
            }

            when {
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
            }
        }

        throw AssertionError("Timed out waiting for SSE event")
    }

    private fun String.toJsonObject(): JsonObject {
        return AppJson.parseToJsonElement(this) as JsonObject
    }

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJsonObject(): JsonObject {
        return bodyAsText().toJsonObject()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonArray.objectAt(index: Int): JsonObject = this[index] as JsonObject

    private fun JsonObject.objectValue(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
}
