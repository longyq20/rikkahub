package me.rerere.rikkahub.backend.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import me.rerere.rikkahub.backend.core.api.NotFoundException
import java.nio.file.Files

fun Routing.registerStaticWeb(config: ServerConfig) {
    if (!Files.exists(config.webUiDir) || Files.isDirectory(config.webUiDir).not()) {
        get("/") {
            call.respondText(
                "web-ui build not found at ${config.webUiDir.toAbsolutePath()}",
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.NotFound,
            )
        }
        return
    }

    staticFiles("/", config.webUiDir.toFile()) {
        default("index.html")
        enableAutoHeadResponse()
    }

    get("/{...}") {
        val requestPath = call.request.path()
        if (requestPath.startsWith("/api/")) {
            throw NotFoundException("Not found")
        }
        val index = config.webUiDir.resolve("index.html")
        if (!Files.exists(index)) {
            throw NotFoundException("index.html not found")
        }
        call.respondFile(index.toFile())
    }
}