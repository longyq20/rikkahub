package me.rerere.rikkahub.backend.server.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.backend.core.util.intValue
import me.rerere.rikkahub.backend.core.util.objectValue
import me.rerere.rikkahub.backend.core.util.stringValue
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration

internal data class ProviderProxyConfig(
    val type: String,
    val address: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)

internal fun parseProviderProxy(provider: JsonObject): ProviderProxyConfig? {
    val proxy = provider.objectValue("proxy") ?: return null
    val type = proxy.stringValue("type")?.trim()?.lowercase().orEmpty()
    if (type.isBlank() || type == "none") return null

    val address = proxy.stringValue("address")?.trim().orEmpty()
    val port = proxy.intValue("port") ?: 0
    if (address.isBlank() || port !in 1..65535) return null

    return ProviderProxyConfig(
        type = type,
        address = address,
        port = port,
        username = proxy.stringValue("username")?.trim().orEmpty(),
        password = proxy.stringValue("password")?.trim().orEmpty(),
    )
}

internal fun httpClientWithProxy(baseClient: HttpClient, proxy: ProviderProxyConfig?): HttpClient {
    if (proxy == null) return baseClient

    // Java HttpClient uses ProxySelector for outbound requests.
    val builder = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .proxy(ProxySelector.of(InetSocketAddress(proxy.address, proxy.port)))

    if (proxy.username.isNotBlank()) {
        val username = proxy.username
        val passwordChars = proxy.password.toCharArray()
        builder.authenticator(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, passwordChars)
            }
        })
    }

    return builder.build()
}
