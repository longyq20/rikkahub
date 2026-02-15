package me.rerere.rikkahub.backend.core.util

import me.rerere.rikkahub.backend.core.api.BadRequestException
import java.util.UUID

fun String?.requireUuid(name: String): String {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) {
        throw BadRequestException("Missing $name")
    }
    runCatching { UUID.fromString(value) }.getOrNull()
        ?: throw BadRequestException("Invalid $name")
    return value
}

fun randomId(): String = UUID.randomUUID().toString()