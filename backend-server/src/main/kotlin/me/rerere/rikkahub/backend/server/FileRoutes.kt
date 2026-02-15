package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.NotFoundException
import me.rerere.rikkahub.backend.core.api.UploadFilesResponseDto
import me.rerere.rikkahub.backend.core.api.UploadedFileDto
import me.rerere.rikkahub.backend.storage.sqlite.repo.ManagedFileSqliteRepository
import java.nio.file.Files

fun Route.registerFileRoutes(
    config: ServerConfig,
    fileRepository: ManagedFileSqliteRepository,
) {
    route("/files") {
        post("/upload") {
            val multipart = call.receiveMultipart()
            val uploadedFiles = mutableListOf<UploadedFileDto>()

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: "file"
                            val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                            val bytes = readPartBytes(part, config.uploadMaxBytes)
                            if (bytes.isEmpty()) throw BadRequestException("Uploaded file is empty")

                            val record = fileRepository.saveUploadFromBytes(
                                bytes = bytes,
                                displayName = originalFileName,
                                mimeType = mimeType,
                            )
                            uploadedFiles += UploadedFileDto(
                                id = record.id,
                                url = record.relativePath,
                                fileName = record.displayName,
                                mime = record.mimeType,
                                size = record.sizeBytes,
                            )
                        }

                        else -> Unit
                    }
                } finally {
                    part.dispose()
                }
            }

            if (uploadedFiles.isEmpty()) throw BadRequestException("No files uploaded")
            call.respond(HttpStatusCode.Created, UploadFilesResponseDto(files = uploadedFiles))
        }

        delete("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("Invalid file id")
            val deleted = fileRepository.delete(id = id, deleteFromDisk = true)
            if (!deleted) throw NotFoundException("File not found")
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        get("/id/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("Invalid file id")
            val file = fileRepository.getById(id) ?: throw NotFoundException("File not found")
            val path = fileRepository.ensureSafeDataPath(file.relativePath)
            if (!Files.exists(path)) throw NotFoundException("File not found on disk")
            call.response.header(HttpHeaders.ContentType, file.mimeType)
            call.respondFile(path.toFile())
        }

        get("/path/{path...}") {
            val relativePath = call.parameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing file path")
            val path = runCatching { fileRepository.ensureSafeDataPath(relativePath) }
                .getOrElse { throw BadRequestException("Invalid file path") }
            if (!Files.exists(path) || Files.isDirectory(path)) throw NotFoundException("File not found")
            call.response.header(HttpHeaders.ContentType, detectContentType(path))
            call.respondFile(path.toFile())
        }
    }
}