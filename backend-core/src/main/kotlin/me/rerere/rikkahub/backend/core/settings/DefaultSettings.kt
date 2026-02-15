package me.rerere.rikkahub.backend.core.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.backend.core.model.DEFAULT_ASSISTANT_ID

fun defaultSettings(jwtEnabled: Boolean, accessPassword: String): JsonObject {
    return JsonObject(
        mapOf(
            "dynamicColor" to JsonPrimitive(true),
            "themeId" to JsonPrimitive("default"),
            "developerMode" to JsonPrimitive(false),
            "displaySetting" to JsonObject(
                mapOf(
                    "userNickname" to JsonPrimitive(""),
                    "showUserAvatar" to JsonPrimitive(true),
                    "showModelIcon" to JsonPrimitive(true),
                    "showModelName" to JsonPrimitive(true),
                    "showTokenUsage" to JsonPrimitive(true),
                    "autoCloseThinking" to JsonPrimitive(true),
                    "codeBlockAutoWrap" to JsonPrimitive(false),
                    "codeBlockAutoCollapse" to JsonPrimitive(false),
                    "showLineNumbers" to JsonPrimitive(false),
                    "sendOnEnter" to JsonPrimitive(false),
                    "enableAutoScroll" to JsonPrimitive(true),
                    "fontSizeRatio" to JsonPrimitive(1.0),
                )
            ),
            "enableWebSearch" to JsonPrimitive(false),
            "favoriteModels" to JsonArray(emptyList()),
            "chatModelId" to JsonPrimitive("auto"),
            "assistantId" to JsonPrimitive(DEFAULT_ASSISTANT_ID),
            "providers" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("builtin"),
                            "enabled" to JsonPrimitive(true),
                            "name" to JsonPrimitive("Builtin"),
                            "models" to JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "id" to JsonPrimitive("auto"),
                                            "modelId" to JsonPrimitive("auto"),
                                            "displayName" to JsonPrimitive("Auto"),
                                            "type" to JsonPrimitive("CHAT"),
                                            "inputModalities" to JsonArray(listOf(JsonPrimitive("TEXT"))),
                                            "outputModalities" to JsonArray(listOf(JsonPrimitive("TEXT"))),
                                            "abilities" to JsonArray(emptyList()),
                                            "tools" to JsonArray(emptyList()),
                                        )
                                    )
                                )
                            ),
                        )
                    )
                )
            ),
            "assistants" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(DEFAULT_ASSISTANT_ID),
                            "name" to JsonPrimitive("Assistant"),
                            "tags" to JsonArray(emptyList()),
                            "chatModelId" to JsonPrimitive("auto"),
                            "mcpServers" to JsonArray(emptyList()),
                            "modeInjectionIds" to JsonArray(emptyList()),
                            "lorebookIds" to JsonArray(emptyList()),
                        )
                    )
                )
            ),
            "assistantTags" to JsonArray(emptyList()),
            "modeInjections" to JsonArray(emptyList()),
            "lorebooks" to JsonArray(emptyList()),
            "mcpServers" to JsonArray(emptyList()),
            "searchServices" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("default"))))),
            "searchServiceSelected" to JsonPrimitive(0),
            "webServerJwtEnabled" to JsonPrimitive(jwtEnabled),
            "webServerAccessPassword" to JsonPrimitive(accessPassword),
        )
    )
}