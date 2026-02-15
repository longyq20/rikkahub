package me.rerere.rikkahub.backend.server

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class ApiParityIntegrationTest {
    @Test
    fun filesUploadDownloadDelete_roundTrip() {
        val fixture = createFixture()
        val fileContent = "portable-file-content"

        try {
            testApplication {
                application { rikkaBackendModule(fixture.config) }

                val uploadResponse = client.post("/api/files/upload") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = fileContent.toByteArray(),
                                    headers = Headers.build {
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "form-data; name=\"file\"; filename=\"hello.txt\"",
                                        )
                                        append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                                    }
                                )
                            }
                        )
                    )
                }
                assertEquals(HttpStatusCode.Created, uploadResponse.status)

                val uploadBody = uploadResponse.bodyAsJsonObject()
                val uploaded = uploadBody.array("files").objectAt(0)
                val fileId = uploaded.long("id")
                val relativePath = uploaded.string("url") ?: error("missing upload url")

                val byIdResponse = client.get("/api/files/id/$fileId")
                assertEquals(HttpStatusCode.OK, byIdResponse.status)
                assertEquals(fileContent, byIdResponse.bodyAsText())

                val byPathResponse = client.get("/api/files/path/$relativePath")
                assertEquals(HttpStatusCode.OK, byPathResponse.status)
                assertEquals(fileContent, byPathResponse.bodyAsText())

                val deleteResponse = client.delete("/api/files/$fileId")
                assertEquals(HttpStatusCode.OK, deleteResponse.status)

                val notFoundResponse = client.get("/api/files/id/$fileId")
                assertEquals(HttpStatusCode.NotFound, notFoundResponse.status)
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun authTokenAndJwtProtection_workWhenEnabled() {
        val fixture = createFixture(jwtEnabled = true, accessPassword = "secret-pass")

        try {
            testApplication {
                application { rikkaBackendModule(fixture.config) }

                val protectedWithoutToken = client.get("/api/conversations/paged?offset=0&limit=1")
                assertEquals(HttpStatusCode.Unauthorized, protectedWithoutToken.status)

                val badTokenResponse = client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"wrong-pass"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, badTokenResponse.status)

                val tokenResponse = client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"secret-pass"}""")
                }
                assertEquals(HttpStatusCode.OK, tokenResponse.status)
                val token = tokenResponse.bodyAsJsonObject().string("token") ?: error("missing token")

                val protectedWithToken = client.get("/api/conversations/paged?offset=0&limit=1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                assertEquals(HttpStatusCode.OK, protectedWithToken.status)
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun migrationExportAndImport_endpointsWork() {
        val fixture = createFixture()

        runBlocking {
            fixture.conversationRepository.upsertConversation(
                ConversationRecord(
                    id = "99999999-1111-2222-3333-444444444444",
                    assistantId = fixture.settingsRepository.currentAssistantId(),
                    title = "seed",
                    messageNodes = emptyList(),
                    createAt = 1,
                    updateAt = 1,
                )
            )
        }

        try {
            testApplication {
                application { rikkaBackendModule(fixture.config) }

                val exportResponse = client.get("/api/migration/export")
                assertEquals(HttpStatusCode.OK, exportResponse.status)
                assertTrue(
                    exportResponse.headers[HttpHeaders.ContentType]
                        ?.startsWith(ContentType.Application.Zip.toString()) == true
                )

                val zipBytes = exportResponse.body<ByteArray>()
                val entries = zipEntryNames(zipBytes)
                assertTrue(entries.contains("settings.json"))
                assertTrue(entries.contains("rikka_hub.db"))

                val missingImportResponse = client.post("/api/migration/import") {
                    setBody(MultiPartFormDataContent(formData { }))
                }
                assertEquals(HttpStatusCode.BadRequest, missingImportResponse.status)

                val importResponse = client.post("/api/migration/import") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = zipBytes,
                                    headers = Headers.build {
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "form-data; name=\"file\"; filename=\"backup.zip\"",
                                        )
                                        append(HttpHeaders.ContentType, ContentType.Application.Zip.toString())
                                    }
                                )
                            }
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, importResponse.status)
                assertTrue(importResponse.bodyAsJsonObject().boolean("success"))
            }
        } finally {
            fixture.cleanup()
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

    private fun createFixture(
        jwtEnabled: Boolean = false,
        accessPassword: String = if (jwtEnabled) "test-password" else "",
    ): TestFixture {
        val dataDir = Files.createTempDirectory("backend-parity-test-")
        val webDir = Files.createTempDirectory("backend-web-test-")
        val assetsDir = Files.createTempDirectory("backend-assets-test-")
        Files.writeString(webDir.resolve("index.html"), "<html><body>test</body></html>")

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

    private fun zipEntryNames(bytes: ByteArray): Set<String> {
        val entries = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                entries += entry.name
                zipIn.closeEntry()
            }
        }
        return entries
    }

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJsonObject(): JsonObject {
        return bodyAsText().toJsonObject()
    }

    private fun String.toJsonObject(): JsonObject {
        return AppJson.parseToJsonElement(this) as JsonObject
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonArray.objectAt(index: Int): JsonObject = this[index] as JsonObject
}
