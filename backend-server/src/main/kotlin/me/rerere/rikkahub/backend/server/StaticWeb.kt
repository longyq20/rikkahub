package me.rerere.rikkahub.backend.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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

    val webRoot = config.webUiDir.toAbsolutePath().normalize()
    val assetRoot = webRoot.resolve("assets").normalize()

    get("/assets/{assetPath...}") {
        val rawParts = call.parameters.getAll("assetPath") ?: throw NotFoundException("Asset not found")
        val relativePath = rawParts.joinToString("/")
        val target = assetRoot.resolve(relativePath).normalize()

        if (!target.startsWith(assetRoot) || !Files.exists(target) || Files.isRegularFile(target).not()) {
            throw NotFoundException("Asset not found: " + target.toString() + " (assetRoot=" + assetRoot.toString() + ")")
        }

        call.respondFile(target.toFile())
    }

    get("/favicon.svg") {
        val favicon = webRoot.resolve("favicon.svg")
        if (!Files.exists(favicon) || Files.isRegularFile(favicon).not()) {
            throw NotFoundException("favicon.svg not found")
        }
        call.respondFile(favicon.toFile())
    }

    get("/") {
        val index = webRoot.resolve("index.html")
        if (!Files.exists(index) || Files.isRegularFile(index).not()) {
            throw NotFoundException("index.html not found")
        }
        call.respondFile(index.toFile())
    }

    get("/{...}") {
        val requestPath = call.request.path()
        if (requestPath.startsWith("/api/")) {
            throw NotFoundException("Not found")
        }

        // Only SPA routes (without file extension) fallback to index.html.
        val lastSegment = requestPath.substringAfterLast('/')
        if (lastSegment.contains('.')) {
            throw NotFoundException("Not found")
        }

        val index = webRoot.resolve("index.html")
        if (!Files.exists(index) || Files.isRegularFile(index).not()) {
            throw NotFoundException("index.html not found")
        }
        call.respondFile(index.toFile())
    }
}
