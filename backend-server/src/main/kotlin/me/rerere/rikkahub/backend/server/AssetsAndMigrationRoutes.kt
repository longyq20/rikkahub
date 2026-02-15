package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.NotFoundException
import me.rerere.rikkahub.backend.migration.MigrationImporter
import java.nio.file.Files

fun Route.registerAssetsRoutes(config: ServerConfig) {
    route("/assets") {
        get("/{path...}") {
            val relativePath = call.parameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing asset path")
            val path = resolveSafePath(config.assetsDir, relativePath)
                ?: throw BadRequestException("Invalid asset path")
            if (!Files.exists(path) || Files.isDirectory(path)) {
                throw NotFoundException("Asset not found")
            }
            call.response.header(HttpHeaders.ContentType, detectContentType(path))
            call.respondFile(path.toFile())
        }
    }
}

fun Route.registerMigrationRoutes(importer: MigrationImporter) {
    route("/migration") {
        post("/import") {
            val multipart = call.receiveMultipart()
            var zipBytes: ByteArray? = null
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        zipBytes = readPartBytes(part, 256 * 1024 * 1024)
                        break
                    }
                } finally {
                    part.dispose()
                }
            }

            val payload = zipBytes ?: throw BadRequestException("Missing backup file")
            val report = importer.importBackup(payload)
            call.respond(if (report.success) HttpStatusCode.OK else HttpStatusCode.BadRequest, report)
        }
    }
}