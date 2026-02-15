package me.rerere.rikkahub.backend.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.util.computeAIIconByName
import java.nio.file.Files

fun Route.registerAiIconRoutes(config: ServerConfig) {
    route("/ai-icon") {
        get {
            val name = call.request.queryParameters["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                throw BadRequestException("Missing name")
            }

            val iconPath = computeAIIconByName(name)
            if (iconPath != null) {
                val resolved = resolveSafePath(config.assetsDir.resolve("icons"), iconPath)
                if (resolved != null && Files.exists(resolved) && !Files.isDirectory(resolved)) {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
                    call.response.header(HttpHeaders.ContentType, detectContentType(resolved))
                    call.respondFile(resolved.toFile())
                    return@get
                }
            }

            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondText(buildFallbackSvg(name), contentType = ContentType.Image.SVG)
        }
    }
}

private fun buildFallbackSvg(name: String): String {
    val text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val escapedText = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
          <rect x="0" y="0" width="64" height="64" rx="32" fill="#E9EAEE"/>
          <text x="32" y="36" font-family="system-ui, sans-serif" font-size="24" font-weight="600" text-anchor="middle" fill="#4E5969">$escapedText</text>
        </svg>
    """.trimIndent()
}