package me.rerere.rikkahub.backend.storage.sqlite.db

import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

class SqliteDatabase(
    private val paths: BackendPaths,
) {
    private val lock = Any()

    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(paths.dataDir)
        Files.createDirectories(paths.uploadDir)
        Files.createDirectories(paths.logsDir)
        withConnection { connection ->
            initializeSchema(connection)
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        synchronized(lock) {
            DriverManager.getConnection("jdbc:sqlite:${paths.dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    statement.execute("PRAGMA journal_mode = WAL")
                }
                return block(connection)
            }
        }
    }

    private fun initializeSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS conversationentity (
                    id TEXT PRIMARY KEY,
                    assistant_id TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                    title TEXT NOT NULL,
                    nodes TEXT NOT NULL DEFAULT '[]',
                    create_at INTEGER NOT NULL,
                    update_at INTEGER NOT NULL,
                    truncate_index INTEGER NOT NULL DEFAULT -1,
                    suggestions TEXT NOT NULL DEFAULT '[]',
                    is_pinned INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS message_node (
                    id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    node_index INTEGER NOT NULL,
                    messages TEXT NOT NULL,
                    select_index INTEGER NOT NULL,
                    FOREIGN KEY(conversation_id) REFERENCES conversationentity(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_message_node_conversation ON message_node(conversation_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_conversation_assistant ON conversationentity(assistant_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_conversation_order ON conversationentity(is_pinned DESC, update_at DESC)")

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS managed_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    folder TEXT NOT NULL,
                    relative_path TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_managed_files_folder ON managed_files(folder)")

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS memoryentity (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    assistant_id TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """.trimIndent()
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memoryentity_assistant ON memoryentity(assistant_id)")
        }
    }
}
