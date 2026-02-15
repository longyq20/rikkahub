package me.rerere.rikkahub.backend.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonPrimitive.valueOrNull(): String? {
    val value = this.content
    return if (value == "null") null else value
}

fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.valueOrNull()

fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.valueOrNull()?.toBooleanStrictOrNull()

fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.valueOrNull()?.toIntOrNull()

fun JsonObject.longValue(key: String): Long? =
    (this[key] as? JsonPrimitive)?.valueOrNull()?.toLongOrNull()

fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.withValue(key: String, value: JsonElement?): JsonObject {
    val mutable = this.toMutableMap()
    if (value == null || value is JsonNull) {
        mutable.remove(key)
    } else {
        mutable[key] = value
    }
    return JsonObject(mutable)
}

fun JsonObject.withString(key: String, value: String?): JsonObject =
    withValue(key, value?.let(::JsonPrimitive))

fun JsonObject.withBoolean(key: String, value: Boolean): JsonObject =
    withValue(key, JsonPrimitive(value))

fun JsonObject.withInt(key: String, value: Int): JsonObject =
    withValue(key, JsonPrimitive(value))

fun JsonObject.withArray(key: String, values: List<JsonElement>): JsonObject =
    withValue(key, JsonArray(values))