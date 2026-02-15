package me.rerere.rikkahub.backend.migration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.backend.core.api.MigrationImportReportDto
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.repo.ConversationSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.ManagedFileSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class MigrationImporter(
    private val paths: BackendPaths,
    private val conversationRepository: ConversationSqliteRepository,
    private val fileRepository: ManagedFileSqliteRepository,
    private val settingsRepository: SettingsJsonRepository,
) {
    suspend fun importBackup(zipBytes: ByteArray): MigrationImportReportDto = withContext(Dispatchers.IO) {
        val importRoot = Files.createTempDirectory(paths.dataDir, "import-")
        val backupRoot = paths.dataDir.resolve("backup").resolve("backup-${Instant.now().toEpochMilli()}")

        try {
            unzip(zipBytes, importRoot)
            val settingsSrc = importRoot.resolve("settings.json")
            val dbSrc = importRoot.resolve("rikka_hub.db")
            val walSrc = importRoot.resolve("rikka_hub-wal")
            val shmSrc = importRoot.resolve("rikka_hub-shm")
            val uploadSrc = importRoot.resolve("upload")

            if (!settingsSrc.exists()) {
                return@withContext MigrationImportReportDto(
                    success = false,
                    rolledBack = false,
                    message = "Invalid backup: settings.json is missing",
                    importedConversations = 0,
                    importedMessageNodes = 0,
                    importedFiles = 0,
                )
            }
            if (!dbSrc.exists()) {
                return@withContext MigrationImportReportDto(
                    success = false,
                    rolledBack = false,
                    message = "Invalid backup: rikka_hub.db is missing",
                    importedConversations = 0,
                    importedMessageNodes = 0,
                    importedFiles = 0,
                )
            }

            backupCurrentData(backupRoot)

            try {
                Files.createDirectories(paths.dataDir)
                Files.copy(settingsSrc, paths.settingsPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Files.copy(dbSrc, paths.dbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

                if (walSrc.exists()) {
                    Files.copy(walSrc, paths.dbWalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.deleteIfExists(paths.dbWalPath)
                }

                if (shmSrc.exists()) {
                    Files.copy(shmSrc, paths.dbShmPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.deleteIfExists(paths.dbShmPath)
                }

                if (uploadSrc.exists() && uploadSrc.isDirectory()) {
                    deleteDirectory(paths.uploadDir)
                    copyDirectory(uploadSrc, paths.uploadDir)
                }

                settingsRepository.reloadFromDisk()

                val conversationCount = conversationRepository.countConversations()
                val nodeCount = conversationRepository.countMessageNodes()
                val fileCount = fileRepository.countUploadFiles()

                MigrationImportReportDto(
                    success = true,
                    rolledBack = false,
                    message = "Import completed",
                    importedConversations = conversationCount,
                    importedMessageNodes = nodeCount,
                    importedFiles = fileCount,
                )
            } catch (t: Throwable) {
                restoreFromBackup(backupRoot)
                settingsRepository.reloadFromDisk()
                MigrationImportReportDto(
                    success = false,
                    rolledBack = true,
                    message = "Import failed and rolled back: ${t.message ?: t::class.simpleName}",
                    importedConversations = 0,
                    importedMessageNodes = 0,
                    importedFiles = 0,
                )
            }
        } finally {
            deleteDirectory(importRoot)
        }
    }

    private fun unzip(zipBytes: ByteArray, targetDir: Path) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (normalized.contains("..")) {
                    zipIn.closeEntry()
                    continue
                }
                val outputPath = targetDir.resolve(normalized).normalize()
                if (!outputPath.startsWith(targetDir)) {
                    zipIn.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outputPath)
                } else {
                    Files.createDirectories(outputPath.parent)
                    Files.newOutputStream(outputPath).use { output ->
                        zipIn.copyTo(output)
                    }
                }
                zipIn.closeEntry()
            }
        }
    }

    private fun backupCurrentData(backupRoot: Path) {
        Files.createDirectories(backupRoot)
        copyIfExists(paths.settingsPath, backupRoot.resolve("settings.json"))
        copyIfExists(paths.dbPath, backupRoot.resolve("rikka_hub.db"))
        copyIfExists(paths.dbWalPath, backupRoot.resolve("rikka_hub-wal"))
        copyIfExists(paths.dbShmPath, backupRoot.resolve("rikka_hub-shm"))

        if (paths.uploadDir.exists() && paths.uploadDir.isDirectory()) {
            copyDirectory(paths.uploadDir, backupRoot.resolve("upload"))
        }
    }

    private fun restoreFromBackup(backupRoot: Path) {
        copyIfExists(backupRoot.resolve("settings.json"), paths.settingsPath)
        copyIfExists(backupRoot.resolve("rikka_hub.db"), paths.dbPath)

        if (backupRoot.resolve("rikka_hub-wal").exists()) {
            copyIfExists(backupRoot.resolve("rikka_hub-wal"), paths.dbWalPath)
        } else {
            Files.deleteIfExists(paths.dbWalPath)
        }

        if (backupRoot.resolve("rikka_hub-shm").exists()) {
            copyIfExists(backupRoot.resolve("rikka_hub-shm"), paths.dbShmPath)
        } else {
            Files.deleteIfExists(paths.dbShmPath)
        }

        val backupUpload = backupRoot.resolve("upload")
        if (backupUpload.exists() && backupUpload.isDirectory()) {
            deleteDirectory(paths.uploadDir)
            copyDirectory(backupUpload, paths.uploadDir)
        }
    }

    private fun copyIfExists(from: Path, to: Path) {
        if (!from.exists()) return
        Files.createDirectories(to.parent)
        Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyDirectory(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.walk(from).use { stream ->
            stream.forEach { source ->
                val relative = from.relativize(source)
                val target = to.resolve(relative)
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteDirectory(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(java.util.Comparator.reverseOrder()).forEach { current ->
                Files.deleteIfExists(current)
            }
        }
    }
}