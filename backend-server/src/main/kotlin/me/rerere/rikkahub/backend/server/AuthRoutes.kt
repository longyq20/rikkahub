package me.rerere.rikkahub.backend.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import me.rerere.rikkahub.backend.core.api.BadRequestException
import me.rerere.rikkahub.backend.core.api.ErrorResponse
import me.rerere.rikkahub.backend.core.api.UnauthorizedException
import me.rerere.rikkahub.backend.core.api.WebAuthTokenRequest
import me.rerere.rikkahub.backend.core.api.WebAuthTokenResponse
import java.security.MessageDigest
import java.util.Date

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_REALM = "rikkahub-web-api"

fun AuthenticationConfig.installWebJwt(config: ServerConfig) {
    val verifierSecret = config.accessPassword.ifBlank { "__missing_password__" }
    jwt("auth-jwt") {
        realm = WEB_AUTH_REALM
        verifier(buildWebJwtVerifier(verifierSecret))
        authHeader { call ->
            extractAccessToken(
                authorizationHeader = call.request.headers[HttpHeaders.Authorization],
                queryToken = call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY],
            )?.let { token ->
                HttpAuthHeader.Single("Bearer", token)
            }
        }
        validate { credential ->
            if (config.accessPassword.isBlank()) {
                null
            } else {
                credential.payload.subject?.takeIf { it == WEB_JWT_SUBJECT }?.let {
                    io.ktor.server.auth.jwt.JWTPrincipal(credential.payload)
                }
            }
        }
        challenge { _, _ ->
            if (config.accessPassword.isBlank()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access password is not configured", 403))
            } else {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", 401))
            }
        }
    }
}

fun Route.registerAuthRoutes(config: ServerConfig) {
    post("/auth/token") {
        if (!config.jwtEnabled) {
            throw BadRequestException("JWT auth is disabled")
        }
        if (config.accessPassword.isBlank()) {
            throw BadRequestException("Access password is not configured")
        }

        val request = call.receive<WebAuthTokenRequest>()
        if (!secureEquals(request.password, config.accessPassword)) {
            throw UnauthorizedException("Invalid password")
        }

        val (token, expiresAt) = createWebJwt(config.accessPassword)
        call.respond(WebAuthTokenResponse(token = token, expiresAt = expiresAt))
    }
}

private fun createWebJwt(secret: String): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = JWT.create()
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .withIssuedAt(Date(now))
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))
    return token to expiresAt
}

private fun buildWebJwtVerifier(secret: String): JWTVerifier {
    return JWT.require(Algorithm.HMAC256(secret))
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .build()
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}