package xyz.mederi.tools.patch

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Codex apply_patch wire contract, pinned to openai/codex
 * 23934d31b15b1127c83535f84854e5009f7cd51d (Apache-2.0).
 * Keep tool instructions and the Responses grammar together when updating upstream.
 */
object ApplyPatchProtocol {
    const val TOOL_NAME = "apply_patch"
    const val FREEFORM_DESCRIPTION = "The `apply_patch` tool can be used to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON."
    const val PATCH_DESCRIPTION = "Complete patch text beginning with *** Begin Patch and ending with *** End Patch; use the documented apply_patch format, not a unified diff."
    const val TOOL_DESCRIPTION = """Use apply_patch to edit UTF-8 text files. Send the complete patch in the patch parameter.
The patch language is a file-oriented diff:
*** Begin Patch
*** Add File: path/to/new.txt
+Every line of a new file must start with +.
*** Update File: path/to/existing.txt
*** Move to: path/to/renamed.txt
@@ optional class or function context
 unchanged context line
-old line
+new line
*** End of File
*** Delete File: path/to/obsolete.txt
*** End Patch
Add, Update and Delete sections may be combined in one patch. Move to is optional, belongs immediately after Update File, and moves the updated content. Each update may contain multiple @@ chunks; use surrounding context to locate each change (normally three lines before and after). @@ context is a single source line, not unified-diff line numbers. Space prefixes unchanged lines, - removes lines and + adds lines. End of File is optional and anchors the preceding chunk at EOF. Paths are relative to project directories (new files use the primary directory), or absolute. In Needs Approval mode, outside or protected targets request one-time user approval for this entire patch before any write. Add and Move may overwrite existing regular files. Read relevant files first; do not include read_file line-number prefixes, Markdown fences, or shell/heredoc commands. The executor checks the whole patch before writing, rejects concurrent writes to any affected path, and returns A/M/D paths or an actionable error. After a failed match, reread the file and correct the patch; do not blindly retry."""

    // Exact upstream grammar: core/assets/tools/apply_patch.lark.
    val LARK_GRAMMAR: String = """
start: begin_patch hunk+ end_patch
begin_patch: "*** Begin Patch" LF
end_patch: "*** End Patch" LF?

hunk: add_hunk | delete_hunk | update_hunk
add_hunk: "*** Add File: " filename LF add_line+
delete_hunk: "*** Delete File: " filename LF
update_hunk: "*** Update File: " filename LF change_move? change?

filename: /(.+)/
add_line: "+" /(.*)/ LF -> line

change_move: "*** Move to: " filename LF
change: (change_context | change_line)+ eof_line?
change_context: ("@@" | "@@ " /(.+)/) LF
change_line: ("+" | "-" | " ") /(.*)/ LF
eof_line: "*** End of File" LF

%import common.LF
""".trimStart()

    @Serializable
    data class Arguments(val patch: String)

    fun encodeArguments(patch: String): String = Json.encodeToString(Arguments.serializer(), Arguments(patch))
    fun decodeArguments(arguments: String): String = Json.decodeFromString(Arguments.serializer(), arguments).patch

    /**
     * Incremental JSON string encoder for Koog's JSON-only tool arguments. Only the fixed
     * DTO envelope is framed here; EVERY input fragment is escaped by kotlinx.serialization.
     * No patch text is interpolated into JSON. Fragments are emitted on arrival; the retained
     * raw text is solely for checking that final metadata agrees with the streamed input.
     */
    class StreamingArguments {
        private val received = StringBuilder()
        private var opened = false
        private var closed = false
        private var pendingHighSurrogate: Char? = null
        // Both envelope tokens come from the DTO serializer, so field names, schema and
        // framing are never duplicated in hand-written JSON. The final two characters are
        // the serializer's closing string quote and object delimiter (this DTO has one string).
        // This partitions encoder output at a known token boundary; it never parses or
        // repairs model/server JSON. Input decoding always uses the DTO serializer above.
        private val emptyEnvelope = encodeArguments("")
        private val prefix = emptyEnvelope.dropLast(2)
        private val suffix = emptyEnvelope.takeLast(2)

        fun open(initialInput: String): String {
            check(!opened)
            opened = true
            return prefix + append(initialInput)
        }

        fun append(delta: String): String {
            check(opened && !closed)
            received.append(delta)
            // A serialized string's quotes are the only framing removed. JSON escaping
            // remains entirely serializer-owned, including quotes, backslashes and controls.
            val combined = pendingHighSurrogate?.let { it.toString() + delta } ?: delta
            pendingHighSurrogate = null
            val safe = if (combined.lastOrNull()?.isHighSurrogate() == true) {
                pendingHighSurrogate = combined.last()
                combined.dropLast(1)
            } else combined
            var index = 0
            while (index < safe.length) {
                val char = safe[index]
                if (char.isHighSurrogate()) {
                    require(index + 1 < safe.length && safe[index + 1].isLowSurrogate()) { "Invalid Unicode surrogate in apply_patch input" }
                    index += 2
                } else {
                    require(!char.isLowSurrogate()) { "Unpaired low surrogate in apply_patch input" }
                    index++
                }
            }
            val encoded = Json.encodeToString(String.serializer(), safe)
            return encoded.substring(1, encoded.length - 1)
        }

        fun completeArguments(finalInput: String? = null): String {
            check(closed)
            require(finalInput == null || finalInput == received.toString()) { "apply_patch final input disagrees with completed input" }
            return encodeArguments(received.toString())
        }

        fun close(finalInput: String?): String {
            check(opened && !closed)
            val remaining = if (finalInput != null) {
                require(finalInput.startsWith(received.toString())) { "apply_patch input does not match its streamed deltas" }
                append(finalInput.substring(received.length))
            } else ""
            require(pendingHighSurrogate == null) { "Truncated Unicode surrogate in apply_patch input" }
            closed = true
            return remaining + suffix
        }
    }

}
