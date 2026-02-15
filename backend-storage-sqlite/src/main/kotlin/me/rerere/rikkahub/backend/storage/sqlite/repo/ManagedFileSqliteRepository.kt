package me.rerere.rikkahub.backend.storage.sqlite.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.backend.core.model.ManagedFileRecord
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

private const val DEFAULT_UPLOAD_FOLDER = "upload"

class ManagedFileSqliteRepository(
    private val paths: BackendPaths,
    private val database: SqliteDatabase,
) {
    suspend fun saveUploadFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): ManagedFileRecord = withContext(Dispatchers.IO) {
        val safeDisplayName = sanitizeDisplayName(displayName)
        val extension = safeDisplayName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""
        val storedName = "${UUID.randomUUID()}$extension"
        val relativePath = "$DEFAULT_UPLOAD_FOLDER/$storedName"
        val filePath = ensureSafeDataPath(relativePath)

        Files.createDirectories(filePath.parent)
        Files.write(filePath, bytes)

        val now = Instant.now().toEpochMilli()
        val record = database.withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO managed_files (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, DEFAULT_UPLOAD_FOLDER)
                statement.setString(2, relativePath)
                statement.setString(3, safeDisplayName)
                statement.setString(4, mimeType)
                statement.setLong(5, bytes.size.toLong())
                statement.setLong(6, now)
                statement.setLong(7, now)
                statement.executeUpdate()
                statement.generatedKeys.use { generated ->
                    generated.next()
                    ManagedFileRecord(
                        id = generated.getLong(1),
                        folder = DEFAULT_UPLOAD_FOLDER,
                        relativePath = relativePath,
                        displayName = safeDisplayName,
                        mimeType = mimeType,
                        sizeBytes = bytes.size.toLong(),
                        createdAt = now,
                        updatedAt = now,
                    )
                }
            }
        }

        record
    }

    suspend fun getById(id: Long): ManagedFileRecord? = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at
                FROM managed_files
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    ManagedFileRecord(
                        id = rs.getLong("id"),
                        folder = rs.getString("folder"),
                        relativePath = rs.getString("relative_path"),
                        displayName = rs.getString("display_name"),
                        mimeType = rs.getString("mime_type"),
                        sizeBytes = rs.getLong("size_bytes"),
                        createdAt = rs.getLong("created_at"),
                        updatedAt = rs.getLong("updated_at"),
                    )
                }
            }
        }
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean): Boolean = withContext(Dispatchers.IO) {
        val existing = getById(id) ?: return@withContext false
        val deleted = database.withConnection { connection ->
            connection.prepareStatement("DELETE FROM managed_files WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate() > 0
            }
        }
        if (deleted && deleteFromDisk) {
            runCatching {
                Files.deleteIfExists(ensureSafeDataPath(existing.relativePath))
            }
        }
        deleted
    }

    suspend fun countUploadFiles(): Int = withContext(Dispatchers.IO) {
        database.withConnection { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM managed_files").use { statement ->
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun ensureSafeDataPath(relativePath: String): Path {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        require(!normalized.contains("..")) { "Invalid relative path" }
        val candidate = paths.dataDir.resolve(normalized).normalize()
        require(candidate.startsWith(paths.dataDir.normalize())) { "Invalid relative path" }
        return candidate
    }

    private fun sanitizeDisplayName(fileName: String): String {
        val normalized = fileName.substringAfterLast('/').substringAfterLast('\\')
        return normalized.replace(Regex("[\\u0000-\\u001F\\u007F]"), "").ifBlank { "file" }
    }
}