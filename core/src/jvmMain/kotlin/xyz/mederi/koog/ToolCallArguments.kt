package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken

private val toolArgumentsJsonFactory = JsonFactory()
private const val MAX_DIAGNOSTIC_FIELDS = 32

private fun ToolDescriptor?.argumentFieldNames(): Set<String> = buildSet {
    fun collect(type: ToolParameterType) {
        when (type) {
            is ToolParameterType.Object -> type.properties.forEach {
                add(it.name)
                collect(it.type)
            }
            is ToolParameterType.List -> collect(type.itemsType)
            is ToolParameterType.AnyOf -> type.types.forEach { collect(it.type) }
            else -> Unit
        }
    }
    if (this@argumentFieldNames != null) {
        (requiredParameters + optionalParameters).forEach {
            add(it.name)
            collect(it.type)
        }
    }
}

/** Validate syntax without rewriting the provider's bytes. Tool decoding still uses kotlinx.serialization. */
internal actual fun requireToolCallArguments(
    arguments: String?,
    descriptor: ToolDescriptor?,
    kind: JsonRecordKind,
): String {
    fun invalid(reason: String): Nothing =
        throw jsonRecordException(reason, kind, arguments, descriptor.argumentFieldNames())

    if (arguments == null) invalid("Missing JSON arguments for tool")
    try {
        toolArgumentsJsonFactory.createParser(arguments).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                invalid("JSON arguments for tool must be an object")
            }
            // String values are decoded lazily. Force decoding so invalid escapes and
            // control characters are rejected even when the value is never read by a tool.
            while (true) {
                val token = parser.nextToken()
                if (token == null) {
                    invalid("Incomplete JSON arguments for tool")
                }
                if (token == JsonToken.VALUE_STRING) parser.finishToken()
                if (token == JsonToken.END_OBJECT && parser.parsingContext.inRoot()) break
            }
            if (parser.nextToken() != null) {
                invalid("Trailing content in JSON arguments for tool")
            }
        }
    } catch (_: JsonProcessingException) {
        invalid("Invalid JSON arguments for tool")
    }
    return arguments
}

internal actual fun jsonRecordException(
    message: String,
    kind: JsonRecordKind,
    json: String?,
    knownFields: Set<String>,
): JsonRecordException {
    val fields = linkedSetOf<String>()
    var location: JsonLocation? = null
    var truncated = false
    fun recordField(name: String) {
        val field = name.takeIf(knownFields::contains) ?: "<unknown field>"
        if (field !in fields && fields.size == MAX_DIAGNOSTIC_FIELDS) {
            truncated = true
        } else {
            fields.add(field)
        }
    }
    if (json != null) {
        toolArgumentsJsonFactory.createParser(json).use { parser ->
            try {
                while (!truncated) {
                    val token = parser.nextToken() ?: break
                    if (token == JsonToken.FIELD_NAME) recordField(parser.currentName())
                    if (token == JsonToken.VALUE_STRING) parser.finishToken()
                    if (parser.parsingContext.inRoot()) {
                        // A second root value is trailing content, not part of this record.
                        truncated = parser.nextToken() != null
                        break
                    }
                }
            } catch (error: JsonProcessingException) {
                // Jackson can read a field name then fail while looking ahead at its value.
                parser.currentName()?.let(::recordField)
                // Exception messages and source snippets can include names and values.
                location = error.location
            }
        }
    }
    val details = buildString {
        appendLine("JSON record: ${kind.label}")
        append("Fields: ")
        append(fields.joinToString().ifEmpty { "(none read)" })
        if (location != null || truncated) append(" (incomplete)")
        location?.let { appendLine(); append("JSON syntax location: line ${it.lineNr}, column ${it.columnNr}") }
    }
    return JsonRecordException(message, details)
}
