package me.rerere.rikkahub.backend.storage.sqlite.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.core.model.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.backend.core.settings.defaultSettings
import me.rerere.rikkahub.backend.core.util.stringValue
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import java.nio.file.Files

class SettingsJsonRepository(
    private val paths: BackendPaths,
    jwtEnabled: Boolean,
    accessPassword: String,
) {
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(loadOrCreate(jwtEnabled, accessPassword))

    val settingsFlow: StateFlow<JsonObject> = _settings

    suspend fun update(transform: (JsonObject) -> JsonObject): JsonObject = mutex.withLock {
        val next = transform(_settings.value)
        persist(next)
        _settings.value = next
        next
    }

    suspend fun replaceAll(value: JsonObject): JsonObject = mutex.withLock {
        persist(value)
        _settings.value = value
        value
    }

    suspend fun reloadFromDisk() = mutex.withLock {
        if (!Files.exists(paths.settingsPath)) return@withLock
        val parsed = runCatching {
            AppJson.parseToJsonElement(Files.readString(paths.settingsPath)) as? JsonObject
        }.getOrNull() ?: return@withLock
        _settings.value = parsed
    }

    fun current(): JsonObject = _settings.value

    fun currentAssistantId(): String = _settings.value.stringValue("assistantId") ?: DEFAULT_ASSISTANT_ID

    private fun loadOrCreate(jwtEnabled: Boolean, accessPassword: String): JsonObject {
        Files.createDirectories(paths.dataDir)

        val fromDisk: JsonObject? = runCatching {
            if (!Files.exists(paths.settingsPath)) return@runCatching null
            AppJson.parseToJsonElement(Files.readString(paths.settingsPath)) as? JsonObject
        }.getOrNull()

        val settings = fromDisk ?: defaultSettings(jwtEnabled = jwtEnabled, accessPassword = accessPassword)
        if (!Files.exists(paths.settingsPath)) {
            persist(settings)
        }

        if (settings.containsKey("webServerJwtEnabled") && settings.containsKey("webServerAccessPassword")) {
            return settings
        }

        val patched = JsonObject(settings.toMutableMap().apply {
            this["webServerJwtEnabled"] = JsonPrimitive(jwtEnabled)
            this["webServerAccessPassword"] = JsonPrimitive(accessPassword)
        })
        persist(patched)
        return patched
    }

    private fun persist(settings: JsonObject) {
        Files.createDirectories(paths.dataDir)
        Files.writeString(paths.settingsPath, AppJson.encodeToString(JsonObject.serializer(), settings))
    }
}