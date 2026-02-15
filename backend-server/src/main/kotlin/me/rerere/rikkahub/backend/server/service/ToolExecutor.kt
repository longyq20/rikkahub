package me.rerere.rikkahub.backend.server.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.util.arrayValue
import me.rerere.rikkahub.backend.core.util.booleanValue
import me.rerere.rikkahub.backend.core.util.randomId
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.server.mcp.transport.SseClientTransport
import me.rerere.rikkahub.backend.server.mcp.transport.StreamableHttpClientTransport
import me.rerere.rikkahub.backend.storage.sqlite.repo.MemorySqliteRepository
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

interface ToolExecutor {
    suspend fun execute(settings: JsonObject, assistantId: String, toolName: String, input: String): List<JsonObject>
}

object NoopToolExecutor : ToolExecutor {
    override suspend fun execute(settings: JsonObject, assistantId: String, toolName: String, input: String): List<JsonObject> {
        return listOf(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive("{\"error\":\"tool executor not configured\",\"toolName\":\"$toolName\"}"),
                )
            )
        )
    }
}

class PortableToolExecutor(
    private val memoryRepository: MemorySqliteRepository,
    private val httpClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
    private val mcpHttpClient: HttpClient = HttpClient(OkHttp) {
        install(SSE)
    },
) : ToolExecutor {
    override suspend fun execute(
        settings: JsonObject,
        assistantId: String,
        toolName: String,
        input: String,
    ): List<JsonObject> {
        return runCatching {
            val trimmed = toolName.trim()
            val payload: JsonElement = when {
                trimmed == "get_time_info" -> getTimeInfo()
                trimmed == "memory_tool" -> executeMemoryTool(settings, assistantId, input)
                trimmed == "search_web" -> executeSearchWeb(input)
                trimmed == "scrape_web" -> executeScrapeWeb(input)
                trimmed == "clipboard_tool" -> JsonObject(
                    mapOf(
                        "success" to JsonPrimitive(false),
                        "error" to JsonPrimitive("clipboard_tool is not available in portable backend runtime"),
                    )
                )

                trimmed.startsWith("mcp__") -> executeMcpTool(
                    settings = settings,
                    assistantId = assistantId,
                    toolName = trimmed.removePrefix("mcp__"),
                    input = input,
                )

                else -> JsonObject(
                    mapOf(
                        "error" to JsonPrimitive("unsupported tool"),
                        "toolName" to JsonPrimitive(toolName),
                    )
                )
            }

            textOutputElement(payload)
        }.getOrElse { throwable ->
            textOutput(
                JsonObject(
                    mapOf(
                        "error" to JsonPrimitive(throwable.message ?: "tool execution failed"),
                        "toolName" to JsonPrimitive(toolName),
                    )
                )
            )
        }
    }

    private suspend fun executeMcpTool(
        settings: JsonObject,
        assistantId: String,
        toolName: String,
        input: String,
    ): JsonElement {
        val args = parseInput(input)

        val assistant = settings.arrayValue("assistants")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.stringValue("id") == assistantId }

        val assistantServerIds = assistant?.arrayValue("mcpServers")
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        if (assistantServerIds.isEmpty()) {
            return JsonObject(mapOf("error" to JsonPrimitive("no mcp servers enabled for assistant")))
        }

        val servers = settings.arrayValue("mcpServers")
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

        val matched = servers.firstNotNullOfOrNull { server ->
            val serverId = server.stringValue("id")?.trim().orEmpty()
            if (serverId.isBlank() || serverId !in assistantServerIds) return@firstNotNullOfOrNull null

            val commonObj = server["commonOptions"] as? JsonObject ?: JsonObject(emptyMap())
            if (commonObj.booleanValue("enable") != true) return@firstNotNullOfOrNull null

            val tools = commonObj.arrayValue("tools")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()

            val tool = tools.firstOrNull { t ->
                t.booleanValue("enable") != false && t.stringValue("name")?.trim() == toolName
            } ?: return@firstNotNullOfOrNull null

            val url = server.stringValue("url")?.trim().orEmpty()
            if (url.isBlank()) return@firstNotNullOfOrNull null

            val serverType = server.stringValue("type")?.trim()?.lowercase().orEmpty()
            val headers = parseMcpHeaders(commonObj)
            ResolvedMcpTool(
                serverType = serverType,
                url = url,
                headers = headers,
                clientName = commonObj.stringValue("name")?.trim().orEmpty(),
                toolNeedsApproval = tool.booleanValue("needsApproval") == true,
            )
        }

        if (matched == null) {
            return JsonObject(mapOf("error" to JsonPrimitive("mcp tool not found or server not configured")))
        }

        val transport = buildMcpTransport(matched)
        val client = Client(
            clientInfo = Implementation(
                name = matched.clientName.ifBlank { "RikkaHub-Portable" },
                version = "1.0",
            )
        )

        try {
            client.connect(transport)
            val result = client.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(
                        name = toolName,
                        arguments = args,
                    ),
                ),
                options = RequestOptions(timeout = 120.seconds),
            )
            return McpJson.encodeToJsonElement(result.content)
        } finally {
            runCatching { client.close() }
        }
    }

    private data class ResolvedMcpTool(
        val serverType: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val clientName: String,
        val toolNeedsApproval: Boolean,
    )

    private fun buildMcpTransport(resolved: ResolvedMcpTool): AbstractTransport {
        val req: HttpRequestBuilder.() -> Unit = {
            headers {
                resolved.headers.forEach { (k, v) -> append(k, v) }
            }
        }

        return when (resolved.serverType) {
            "sse" -> SseClientTransport(
                client = mcpHttpClient,
                urlString = resolved.url,
                requestBuilder = req,
            )

            "streamable_http", "streamable-http", "streamablehttp" -> StreamableHttpClientTransport(
                client = mcpHttpClient,
                url = resolved.url,
                requestBuilder = req,
            )

            else -> StreamableHttpClientTransport(
                client = mcpHttpClient,
                url = resolved.url,
                requestBuilder = req,
            )
        }
    }

    private fun parseMcpHeaders(commonOptions: JsonObject): List<Pair<String, String>> {
        val raw = commonOptions.arrayValue("headers") ?: return emptyList()
        return raw.mapNotNull { elem ->
            when (elem) {
                is JsonArray -> {
                    val name = (elem.getOrNull(0) as? JsonPrimitive)?.content?.trim().orEmpty()
                    val value = (elem.getOrNull(1) as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }

                is JsonObject -> {
                    val name = elem.stringValue("first")?.trim()
                        ?: elem.stringValue("name")?.trim()
                        ?: ""
                    val value = elem.stringValue("second")?.trim()
                        ?: elem.stringValue("value")?.trim()
                        ?: ""
                    if (name.isBlank() || value.isBlank()) null else name to value
                }

                else -> null
            }
        }
    }

    private suspend fun executeSearchWeb(input: String): JsonObject {
        val args = parseInput(input)
        val query = args.stringValue("query")?.trim().orEmpty()
        require(query.isNotBlank()) { "query is required" }

        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "RikkaHub-Portable/1.0")
            .GET()
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        require(response.statusCode() in 200..299) { "search request failed (${response.statusCode()})" }

        val json = (AppJson.parseToJsonElement(response.body()) as? JsonObject) ?: JsonObject(emptyMap())
        val items = mutableListOf<JsonObject>()
        collectDuckItems(json.arrayValue("RelatedTopics"), items)

        val indexedItems = items.take(8).mapIndexed { index, item ->
            JsonObject(
                item.toMutableMap().apply {
                    this["id"] = JsonPrimitive(randomId().take(6))
                    this["index"] = JsonPrimitive(index + 1)
                }
            )
        }

        return JsonObject(
            mapOf(
                "answer" to JsonPrimitive(json.stringValue("AbstractText")?.trim().orEmpty()),
                "items" to JsonArray(indexedItems),
            )
        )
    }

    private suspend fun executeScrapeWeb(input: String): JsonObject {
        val args = parseInput(input)
        val urls = mutableListOf<String>()
        args.stringValue("url")?.trim()?.takeIf { it.isNotBlank() }?.let(urls::add)
        args.arrayValue("urls")
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach(urls::add)

        require(urls.isNotEmpty()) { "url is required" }

        val contents = urls.distinct().take(5).map { url ->
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "RikkaHub-Portable/1.0")
                .GET()
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
            require(response.statusCode() in 200..299) { "scrape request failed (${response.statusCode()})" }

            val body = Jsoup.parse(response.body()).body().text()
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(12000)

            JsonObject(
                mapOf(
                    "url" to JsonPrimitive(url),
                    "content" to JsonPrimitive(body),
                )
            )
        }

        return JsonObject(mapOf("urls" to JsonArray(contents)))
    }

    private suspend fun executeMemoryTool(settings: JsonObject, assistantId: String, input: String): JsonObject {
        val args = parseInput(input)
        val action = args.stringValue("action")?.trim().orEmpty()
        require(action.isNotBlank()) { "action is required" }
        val targetAssistantId = resolveMemoryAssistantId(settings, assistantId)

        return when (action) {
            "create" -> {
                val content = args.stringValue("content")?.trim().orEmpty()
                require(content.isNotBlank()) { "content is required" }
                val created = memoryRepository.addMemory(targetAssistantId, content)
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(created.id),
                        "content" to JsonPrimitive(created.content),
                    )
                )
            }

            "edit" -> {
                val id = (args["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val content = args.stringValue("content")?.trim().orEmpty()
                require(content.isNotBlank()) { "content is required" }
                val old = memoryRepository.getMemoryById(id) ?: throw IllegalStateException("memory #$id not found")
                require(old.assistantId == targetAssistantId) { "memory #$id does not belong to current assistant" }

                val updated = memoryRepository.updateContent(id, content)
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(updated.id),
                        "content" to JsonPrimitive(updated.content),
                    )
                )
            }

            "delete" -> {
                val id = (args["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val old = memoryRepository.getMemoryById(id) ?: throw IllegalStateException("memory #$id not found")
                require(old.assistantId == targetAssistantId) { "memory #$id does not belong to current assistant" }

                memoryRepository.deleteMemory(id)
                JsonObject(
                    mapOf(
                        "success" to JsonPrimitive(true),
                        "id" to JsonPrimitive(id),
                    )
                )
            }

            else -> throw IllegalArgumentException("unknown action: $action")
        }
    }

    private fun getTimeInfo(): JsonObject {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val time = now.toLocalTime().withNano(0)
        val weekday = now.dayOfWeek

        return JsonObject(
            mapOf(
                "year" to JsonPrimitive(date.year),
                "month" to JsonPrimitive(date.monthValue),
                "day" to JsonPrimitive(date.dayOfMonth),
                "weekday" to JsonPrimitive(weekday.getDisplayName(TextStyle.FULL, Locale.getDefault())),
                "weekday_en" to JsonPrimitive(weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH)),
                "weekday_index" to JsonPrimitive(weekday.value),
                "date" to JsonPrimitive(date.toString()),
                "time" to JsonPrimitive(time.toString()),
                "datetime" to JsonPrimitive(now.withNano(0).toString()),
                "timezone" to JsonPrimitive(now.zone.id),
                "utc_offset" to JsonPrimitive(now.offset.id),
                "timestamp_ms" to JsonPrimitive(now.toInstant().toEpochMilli()),
            )
        )
    }

    private fun parseInput(input: String): JsonObject {
        if (input.isBlank()) return JsonObject(emptyMap())
        return (runCatching { AppJson.parseToJsonElement(input) }.getOrNull() as? JsonObject)
            ?: JsonObject(emptyMap())
    }

    private fun resolveMemoryAssistantId(settings: JsonObject, assistantId: String): String {
        val assistant = settings.arrayValue("assistants")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.stringValue("id") == assistantId }

        return if (assistant?.booleanValue("useGlobalMemory") == true) {
            MemorySqliteRepository.GLOBAL_MEMORY_ID
        } else {
            assistantId
        }
    }

    private fun collectDuckItems(nodes: JsonArray?, out: MutableList<JsonObject>) {
        if (nodes == null) return
        nodes.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            obj.arrayValue("Topics")?.let {
                collectDuckItems(it, out)
                return@forEach
            }

            val url = obj.stringValue("FirstURL")?.trim().orEmpty()
            val text = obj.stringValue("Text")?.trim().orEmpty()
            if (url.isBlank() || text.isBlank()) return@forEach
            val title = text.substringBefore(" - ").ifBlank { text }

            out += JsonObject(
                mapOf(
                    "title" to JsonPrimitive(title),
                    "url" to JsonPrimitive(url),
                    "text" to JsonPrimitive(text),
                )
            )
        }
    }

    private fun textOutput(payload: JsonObject): List<JsonObject> {
        val text = AppJson.encodeToString(JsonObject.serializer(), payload)
        return listOf(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(text),
                )
            )
        )
    }

    private fun textOutputElement(payload: JsonElement): List<JsonObject> {
        val text = payload.toString()
        return listOf(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(text),
                )
            )
        )
    }
}