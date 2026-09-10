package xyz.emuci.markdown.renderer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingDocumentStateTest {

    @Test
    fun should_begin_before_append_when_stream_starts() = runBlocking {
        val calls = mutableListOf<String>()

        val state = updateStreamingDocumentState(
            markdown = "abcd",
            isStreaming = true,
            state = StreamingDocumentState(),
            beginStream = {
                calls += "begin"
            },
            append = { chunk ->
                calls += "append:$chunk"
                "append:$chunk"
            },
            endStream = {
                calls += "end"
                "end"
            },
            parse = { markdown ->
                calls += "parse:$markdown"
                "parse:$markdown"
            }
        )

        assertEquals(listOf("begin", "append:abcd"), calls)
        assertEquals("append:abcd", state.document)
        assertEquals(4, state.lastParsedLength)
        assertTrue(state.wasStreaming)
    }

    @Test
    fun should_append_tail_before_end_when_stream_finishes() = runBlocking {
        val calls = mutableListOf<String>()

        val state = updateStreamingDocumentState(
            markdown = "abcd",
            isStreaming = false,
            state = StreamingDocumentState(
                lastParsedLength = 3,
                document = "append:abc",
                wasStreaming = true,
            ),
            beginStream = {
                calls += "begin"
            },
            append = { chunk ->
                calls += "append:$chunk"
                "append:$chunk"
            },
            endStream = {
                calls += "end"
                "end"
            },
            parse = { markdown ->
                calls += "parse:$markdown"
                "parse:$markdown"
            }
        )

        assertEquals(listOf("append:d", "end"), calls)
        assertEquals("end", state.document)
        assertEquals(4, state.lastParsedLength)
        assertFalse(state.wasStreaming)
        assertEquals("abcd", state.lastNonStreamingMarkdown)
    }

    @Test
    fun should_not_parse_when_finishing_stream() = runBlocking {
        val calls = mutableListOf<String>()

        updateStreamingDocumentState(
            markdown = "abc",
            isStreaming = false,
            state = StreamingDocumentState(
                lastParsedLength = 3,
                document = "append:abc",
                wasStreaming = true,
            ),
            beginStream = {
                calls += "begin"
            },
            append = { chunk ->
                calls += "append:$chunk"
                "append:$chunk"
            },
            endStream = {
                calls += "end"
                "end"
            },
            parse = { markdown ->
                calls += "parse:$markdown"
                "parse:$markdown"
            }
        )

        assertEquals(listOf("end"), calls)
    }

    @Test
    fun should_parse_full_markdown_when_not_streaming() = runBlocking {
        val calls = mutableListOf<String>()

        val state = updateStreamingDocumentState(
            markdown = "abc",
            isStreaming = false,
            state = StreamingDocumentState(),
            beginStream = {
                calls += "begin"
            },
            append = { chunk ->
                calls += "append:$chunk"
                "append:$chunk"
            },
            endStream = {
                calls += "end"
                "end"
            },
            parse = { markdown ->
                calls += "parse:$markdown"
                "parse:$markdown"
            }
        )

        assertEquals(listOf("parse:abc"), calls)
        assertEquals("parse:abc", state.document)
        assertEquals(3, state.lastParsedLength)
        assertFalse(state.wasStreaming)
        assertEquals("abc", state.lastNonStreamingMarkdown)
    }
}
