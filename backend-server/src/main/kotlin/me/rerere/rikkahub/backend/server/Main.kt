package me.rerere.rikkahub.backend.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import me.rerere.rikkahub.backend.core.api.ApiException
import me.rerere.rikkahub.backend.core.api.ErrorResponse
import me.rerere.rikkahub.backend.core.json.AppJson
import me.rerere.rikkahub.backend.migration.MigrationImporter
import me.rerere.rikkahub.backend.server.service.ConversationEngine
import me.rerere.rikkahub.backend.storage.sqlite.BackendPaths
import me.rerere.rikkahub.backend.storage.sqlite.db.SqliteDatabase
import me.rerere.rikkahub.backend.storage.sqlite.repo.ConversationSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.ManagedFileSqliteRepository
import me.rerere.rikkahub.backend.storage.sqlite.repo.SettingsJsonRepository

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(CIO, host = config.host, port = config.port) {
        rikkaBackendModule(config)
    }.start(wait = true)
}

fun Application.rikkaBackendModule(config: ServerConfig = ServerConfig.fromEnvironment()) {
    val paths = BackendPaths(config.dataDir)
    val sqliteDatabase = SqliteDatabase(paths)
    val conversationRepository = ConversationSqliteRepository(sqliteDatabase)
    val fileRepository = ManagedFileSqliteRepository(paths, sqliteDatabase)
    val settingsRepository = SettingsJsonRepository(paths, config.jwtEnabled, config.accessPassword)
    val migrationImporter = MigrationImporter(paths, conversationRepository, fileRepository, settingsRepository)
    val conversationEngine = ConversationEngine(conversationRepository, settingsRepository)

    install(ContentNegotiation) { json(AppJson) }
    install(DefaultHeaders)
    install(Compression)
    install(SSE)
    install(CORS) {
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowNonSimpleContentTypes = true
        anyHost()
        anyMethod()
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", HttpStatusCode.InternalServerError.value)
            )
        }
    }

    if (config.jwtEnabled) {
        install(Authentication) {
            installWebJwt(config)
        }
    }

    routing {
        route("/api") {
            registerSystemRoutes(config)
            registerAuthRoutes(config)
            registerAiIconRoutes(config)

            if (config.jwtEnabled) {
                authenticate("auth-jwt") {
                    registerSettingsRoutes(settingsRepository)
                    registerConversationRoutes(settingsRepository, conversationRepository, conversationEngine)
                    registerFileRoutes(config, fileRepository)
                    registerAssetsRoutes(config)
                    registerMigrationRoutes(migrationImporter)
                }
            } else {
                registerSettingsRoutes(settingsRepository)
                registerConversationRoutes(settingsRepository, conversationRepository, conversationEngine)
                registerFileRoutes(config, fileRepository)
                registerAssetsRoutes(config)
                registerMigrationRoutes(migrationImporter)
            }
        }

        registerStaticWeb(config)
    }
}