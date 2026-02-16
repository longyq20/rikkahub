import java.io.File
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("me.rerere.rikkahub.backend.server.MainKt")
}

dependencies {
    implementation(project(":backend-core"))
    implementation(project(":backend-storage-sqlite"))
    implementation(project(":backend-migration"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.modelcontextprotocol.kotlin.sdk)


    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.java.jwt)
    implementation(libs.jsoup)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}

tasks.named<JavaExec>("run") {
    val rootDirPath = rootProject.projectDir

    fun resolveEnvPath(name: String, fallbackRelative: String): String {
        val raw = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallbackRelative
        val path = File(raw)
        return if (path.isAbsolute) path.absolutePath else rootDirPath.resolve(raw).absolutePath
    }

    environment("DATA_DIR", resolveEnvPath("DATA_DIR", "data"))
    environment("WEB_UI_DIR", resolveEnvPath("WEB_UI_DIR", "web-ui/build/client"))
    environment("ASSETS_DIR", resolveEnvPath("ASSETS_DIR", "app/src/main/assets"))
}
