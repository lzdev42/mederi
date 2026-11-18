package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.SerializationException

internal enum class JsonRecordKind(val label: String) {
    SENT_TOOL_ARGUMENTS("send / tool arguments"),
    RECEIVED_TOOL_ARGUMENTS("receive / tool arguments"),
    RESPONSE_BODY("receive / response body"),
    SSE_EVENT("receive / SSE event"),
}

internal class JsonRecordException(message: String, val record: String) : SerializationException(message)

internal expect fun jsonRecordException(
    message: String,
    kind: JsonRecordKind,
    json: String?,
    knownFields: Set<String>,
): JsonRecordException

internal expect fun requireToolCallArguments(
    arguments: String?,
    descriptor: ToolDescriptor? = null,
    kind: JsonRecordKind = JsonRecordKind.RECEIVED_TOOL_ARGUMENTS,
): String
