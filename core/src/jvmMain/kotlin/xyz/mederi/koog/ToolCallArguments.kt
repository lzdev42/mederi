package xyz.mederi.infrastructure.koog

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import kotlinx.serialization.SerializationException

private val toolArgumentsJsonFactory = JsonFactory()

/** Validate syntax without rewriting the provider's bytes. Tool decoding still uses kotlinx.serialization. */
internal actual fun requireToolCallArguments(tool: String, id: String?, arguments: String?): String {
    if (arguments == null) {
        throw SerializationException("Missing JSON arguments for tool '$tool' (call ID: $id)")
    }
    try {
        toolArgumentsJsonFactory.createParser(arguments).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw SerializationException("JSON arguments for tool '$tool' (call ID: $id) must be an object")
            }
            // String values are decoded lazily. Force decoding so invalid escapes and
            // control characters are rejected even when the value is never read by a tool.
            while (true) {
                val token = parser.nextToken()
                if (token == null) {
                    throw SerializationException("Incomplete JSON arguments for tool '$tool' (call ID: $id)")
                }
                if (token == JsonToken.VALUE_STRING) parser.finishToken()
                if (token == JsonToken.END_OBJECT && parser.parsingContext.inRoot()) break
            }
            if (parser.nextToken() != null) {
                throw SerializationException("Trailing content in JSON arguments for tool '$tool' (call ID: $id)")
            }
        }
    } catch (error: JsonProcessingException) {
        throw SerializationException("Invalid JSON arguments for tool '$tool' (call ID: $id)", error)
    }
    return arguments
}
