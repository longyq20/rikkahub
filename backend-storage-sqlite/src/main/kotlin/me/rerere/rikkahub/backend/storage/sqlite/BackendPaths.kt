package me.rerere.rikkahub.backend.storage.sqlite

import java.nio.file.Path

class BackendPaths(val dataDir: Path) {
    val settingsPath: Path = dataDir.resolve("settings.json")
    val dbPath: Path = dataDir.resolve("rikka_hub.db")
    val dbWalPath: Path = dataDir.resolve("rikka_hub-wal")
    val dbShmPath: Path = dataDir.resolve("rikka_hub-shm")
    val uploadDir: Path = dataDir.resolve("upload")
    val logsDir: Path = dataDir.resolve("logs")
}