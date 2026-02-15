package me.rerere.rikkahub.backend.migration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class MigrationExporter(
    private val paths: BackendPaths,
) {
    suspend fun exportBackup(): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                addRequiredFile(zip, paths.settingsPath, "settings.json")
                addRequiredFile(zip, paths.dbPath, "rikka_hub.db")
                addOptionalFile(zip, paths.dbWalPath, "rikka_hub-wal")
                addOptionalFile(zip, paths.dbShmPath, "rikka_hub-shm")
                addOptionalDirectory(zip, paths.uploadDir, "upload")
            }
            buffer.toByteArray()
        }
    }

    private fun addRequiredFile(zip: ZipOutputStream, file: Path, entryName: String) {
        if (!file.exists() || Files.isDirectory(file)) {
            throw IllegalStateException("Required backup file missing: $entryName")
        }
        addFile(zip, file, entryName)
    }

    private fun addOptionalFile(zip: ZipOutputStream, file: Path, entryName: String) {
        if (!file.exists() || Files.isDirectory(file)) return
        addFile(zip, file, entryName)
    }

    private fun addOptionalDirectory(zip: ZipOutputStream, dir: Path, entryPrefix: String) {
        if (!dir.exists() || !dir.isDirectory()) return
        Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { source ->
                    val relative = dir.relativize(source).toString().replace('\\', '/')
                    addFile(zip, source, "$entryPrefix/$relative")
                }
        }
    }

    private fun addFile(zip: ZipOutputStream, source: Path, entryName: String) {
        val normalized = entryName.trimStart('/').replace("..", "")
        val entry = ZipEntry(normalized)
        zip.putNextEntry(entry)
        Files.newInputStream(source).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }
}
