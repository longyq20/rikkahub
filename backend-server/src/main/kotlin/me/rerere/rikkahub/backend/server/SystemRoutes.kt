package me.rerere.rikkahub.backend.server

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import me.rerere.rikkahub.backend.core.api.SystemHealthDto
import me.rerere.rikkahub.backend.core.api.SystemInfoDto

fun Route.registerSystemRoutes(config: ServerConfig) {
    get("/system/health") {
        call.respond(SystemHealthDto(status = "ok"))
    }

    get("/system/info") {
        call.respond(
            SystemInfoDto(
                name = "RikkaHub Portable Backend",
                version = config.version,
                host = config.host,
                port = config.port,
                platform = System.getProperty("os.name") + " " + System.getProperty("os.version"),
                dataDir = config.dataDir.toAbsolutePath().toString(),
                jwtEnabled = config.jwtEnabled,
            )
        )
    }
}