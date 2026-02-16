package me.rerere.rikkahub.backend.server

import java.nio.file.Path
import kotlin.io.path.Path

data class ServerConfig(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val webUiDir: Path,
    val assetsDir: Path,
    val jwtEnabled: Boolean,
    val accessPassword: String,
    val uploadMaxBytes: Int,
    val version: String,
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val host = env("HOST", "0.0.0.0")
            val port = env("PORT", "8080").toIntOrNull() ?: 8080
            val dataDir = Path(env("DATA_DIR", "data"))
            val webUiDir = Path(env("WEB_UI_DIR", "web-ui/build/client"))
            val assetsDir = Path(env("ASSETS_DIR", "assets"))
            val jwtEnabled = env("JWT_ENABLED", "false").equals("true", ignoreCase = true)
            val accessPassword = env("ACCESS_PASSWORD", "")
            val uploadMaxMb = env("UPLOAD_MAX_MB", "20").toIntOrNull() ?: 20
            val version = env("APP_VERSION", "dev")

            return ServerConfig(
                host = host,
                port = port,
                dataDir = dataDir,
                webUiDir = webUiDir,
                assetsDir = assetsDir,
                jwtEnabled = jwtEnabled,
                accessPassword = accessPassword,
                uploadMaxBytes = uploadMaxMb * 1024 * 1024,
                version = version,
            )
        }

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
    }
}