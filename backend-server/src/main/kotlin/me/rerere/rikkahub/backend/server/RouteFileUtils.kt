package me.rerere.rikkahub.backend.server

import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.utils.io.readAvailable
import me.rerere.rikkahub.backend.core.api.BadRequestException
import java.io.ByteArrayOutputStream
import java.nio.file.Path

suspend fun readPartBytes(part: PartData.FileItem, maxBytes: Int): ByteArray {
    val input = part.provider()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break

        totalBytes += read
        if (totalBytes > maxBytes) {
            throw BadRequestException("File too large: max ${maxBytes / (1024 * 1024)} MB")
        }

        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

fun detectContentType(path: Path): String = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> ContentType.Image.JPEG.toString()
    "png" -> ContentType.Image.PNG.toString()
    "gif" -> ContentType.Image.GIF.toString()
    "webp" -> ContentType("image", "webp").toString()
    "svg" -> ContentType.Image.SVG.toString()
    "pdf" -> ContentType.Application.Pdf.toString()
    "json" -> ContentType.Application.Json.toString()
    "txt" -> ContentType.Text.Plain.toString()
    "html" -> ContentType.Text.Html.toString()
    "mp4" -> ContentType("video", "mp4").toString()
    "webm" -> ContentType("video", "webm").toString()
    "mp3" -> ContentType.Audio.MPEG.toString()
    "wav" -> ContentType("audio", "wav").toString()
    "ogg" -> ContentType("audio", "ogg").toString()
    else -> ContentType.Application.OctetStream.toString()
}

fun resolveSafePath(base: Path, relativePath: String): Path? {
    val normalized = relativePath.replace('\\', '/').trimStart('/')
    if (normalized.contains("..")) return null
    val resolved = base.resolve(normalized).normalize()
    if (!resolved.startsWith(base.normalize())) return null
    return resolved
}