package xyz.emuci.markdown.parser.streaming

import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.ast.BlankLine
import xyz.emuci.markdown.parser.ast.ContainerNode
import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.parser.ast.Node
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Fuzz tests that simulate LLM token streams: the same source markdown is split into
 * many random chunk sequences (sometimes single-byte) and replayed via
 * [MarkdownParser.beginStream]/[MarkdownParser.append]/[MarkdownParser.endStream].
 *
 * Goals:
 *  1. The parser must never throw on intermediate states (incomplete fences, half emphasis,
 *     truncated URLs, partial table rows, etc.).
 *  2. Once the full input has been streamed and the stream is closed, the resulting
 *     [Document] must structurally match a one-shot [MarkdownParser.parse] of the same
 *     source (identical block-type sequence ignoring [BlankLine]).
 *  3. Intermediate snapshots (after each append) must also be safe to read from
 *     (no exceptions when traversing children/text content).
 *
 * These tests are deliberately seeded so failures are reproducible.
 */
class StreamingChunkBoundaryFuzzTest {

    private val seeds: List<Pair<String, String>> = listOf(
        "atx-heading" to "# Title\n\nA paragraph.\n",
        "fenced-code" to """
            Some intro.
            
            ```kotlin
            fun main() {
                println("Hello, world!")
            }
            ```
            
            Trailing text.
        """.trimIndent() + "\n",
        "nested-list" to """
            - top
              - mid
                - leaf
              - mid2
            - second
        """.trimIndent() + "\n",
        "table" to """
            Header text.
            
            | a | b | c |
            | - | - | - |
            | 1 | 2 | 3 |
            | 4 | 5 | 6 |
        """.trimIndent() + "\n",
        "links-and-emphasis" to "Visit [GitHub](https://github.com) for **bold _and italic_** text and `inline code`.\n",
        "blockquote" to "> first line\n> second line\n>\n> third paragraph\n\nAfter.\n",
        "math-and-html" to """
            Inline math: ${"$"}a^2 + b^2 = c^2${"$"}.
            
            ${"$"}${"$"}
            \int_0^1 x^2 dx = \frac{1}{3}
            ${"$"}${"$"}
            
            <div class="warn">html block</div>
        """.trimIndent() + "\n",
        "mixed-llm-like" to """
            # Answer
            
            Sure! Here's an example in Kotlin:
            
            ```kotlin
            data class Foo(val x: Int)
            ```
            
            And in a list:
            1. Step one
            2. Step two with `code`
            3. Step three: see [docs](https://example.com)
            
            > Note: this is a quote.
            
            | col | col |
            | --- | --- |
            | 1   | 2   |
        """.trimIndent() + "\n",
    )

    @Test
    fun should_match_one_shot_parse_for_random_chunkings() {
        val rng = Random(0x5EED_CAFE)
        for ((label, src) in seeds) {
            val expected = MarkdownParser().parse(src).blockTypeSequence()
            // Try several random chunkings per seed.
            repeat(8) { trial ->
                val chunks = randomChunking(src, rng)
                val parser = MarkdownParser()
                parser.beginStream()
                for ((idx, chunk) in chunks.withIndex()) {
                    try {
                        parser.append(chunk)
                    } catch (t: Throwable) {
                        fail(
                            "[$label trial=$trial] parser.append crashed at chunk #$idx " +
                                    "(chunkLen=${chunk.length}, offset=${chunks.take(idx).sumOf { it.length }}): $t"
                        )
                    }
                    // Intermediate snapshot must be readable.
                    try {
                        traverseSnapshot(parser.document)
                    } catch (t: Throwable) {
                        fail("[$label trial=$trial] snapshot traversal crashed at chunk #$idx: $t")
                    }
                }
                val finalDoc = parser.endStream()
                val actual = finalDoc.blockTypeSequence()
                assertEquals(
                    expected,
                    actual,
                    "[$label trial=$trial] streamed AST block sequence != one-shot " +
                            "(chunkSizes=${chunks.map { it.length }})"
                )
            }
        }
    }

    @Test
    fun should_survive_one_byte_at_a_time_streaming() {
        for ((label, src) in seeds) {
            val expected = MarkdownParser().parse(src).blockTypeSequence()
            val parser = MarkdownParser()
            parser.beginStream()
            for (i in src.indices) {
                try {
                    parser.append(src[i].toString())
                } catch (t: Throwable) {
                    fail("[$label] parser.append crashed at single-byte offset $i: $t")
                }
            }
            val finalDoc = parser.endStream()
            assertEquals(
                expected,
                finalDoc.blockTypeSequence(),
                "[$label] single-byte streamed AST block sequence != one-shot"
            )
        }
    }

    @Test
    fun should_handle_pathological_inflight_tokens_without_throwing() {
        // Truncations chosen to land mid-syntax to stress-test inline/block parsers.
        val partials = listOf(
            "# Heading\n\nVisit https://exa", // partial autolink
            "Some `inline cod",                // unterminated inline code
            "**bold without clos",             // unterminated emphasis
            "[link without close](http",       // partial link
            "```kot",                          // partial fence open
            "```kotlin\nfun main(",            // open fence with partial body
            "| a | b\n| -",                    // partial table delimiter row
            "> quote\n> more\n> ",             // trailing-empty blockquote
            "$$\n\\int_0^1",                   // partial math block
            "\\begin{equation}\nx",            // partial latex env (if supported)
            "<div class=\"x\">",               // unterminated html block
            "1. item\n   continu",             // partial list continuation
        )
        for ((idx, src) in partials.withIndex()) {
            // 1) Streamed in arbitrary chunks must not throw at any step.
            val parser = MarkdownParser()
            parser.beginStream()
            val chunks = randomChunking(src, Random(0xC0FFEE + idx.toLong()))
            for ((cIdx, chunk) in chunks.withIndex()) {
                try {
                    parser.append(chunk)
                    traverseSnapshot(parser.document)
                } catch (t: Throwable) {
                    fail("[partial #$idx] crashed at chunk $cIdx (\"${chunk.take(20)}...\"): $t")
                }
            }
            // 2) endStream must not throw and must return a Document.
            try {
                parser.endStream()
            } catch (t: Throwable) {
                fail("[partial #$idx] endStream crashed: $t")
            }
            // 3) One-shot parse of the same partial must also not throw (sanity).
            try {
                MarkdownParser().parse(src)
            } catch (t: Throwable) {
                fail("[partial #$idx] one-shot parse crashed on incomplete source: $t")
            }
        }
    }

    // ────── helpers ──────

    private fun randomChunking(src: String, rng: Random): List<String> {
        if (src.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        var pos = 0
        while (pos < src.length) {
            // 60% small (1-4 chars), 30% medium (5-32), 10% large (33-128).
            val size = when (rng.nextInt(10)) {
                in 0..5 -> rng.nextInt(1, 5)
                in 6..8 -> rng.nextInt(5, 33)
                else -> rng.nextInt(33, 129)
            }.coerceAtMost(src.length - pos)
            result.add(src.substring(pos, pos + size))
            pos += size
        }
        return result
    }

    private fun Document.blockTypeSequence(): List<String> =
        children.asSequence()
            .filter { it !is BlankLine }
            .map { it::class.simpleName ?: "?" }
            .toList()

    private fun traverseSnapshot(node: Node) {
        // Touch children recursively to catch ConcurrentModification / NPE / lazy-init bugs.
        if (node is ContainerNode) {
            for (c in node.children) {
                traverseSnapshot(c)
            }
        }
    }
}
