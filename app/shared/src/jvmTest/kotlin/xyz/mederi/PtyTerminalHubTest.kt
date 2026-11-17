package xyz.mederi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.server.terminal.PtyTerminalHub
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtyTerminalHubTest {
    @Test
    fun oneShotSessionStreamsInputAndOutputAndClosesWithoutPublishingToTheHub() = runBlocking {
        if (!File("/bin/sh").canExecute()) return@runBlocking
        val hub = PtyTerminalHub()
        try {
            withTimeout(10_000) {
                val session = hub.startCommand(
                    listOf(
                        "/bin/sh", "-c",
                        "printf 'ready\\n'; IFS= read -r reply; printf 'result=%s\\n' \"\$reply\" >&2; exit 7",
                    ),
                    "Setup test",
                )
                assertNull(hub.find(session.key))
                assertTrue(hub.all().isEmpty())
                val ready = CompletableDeferred<Unit>()
                val output = async {
                    buildString {
                        session.output().collect { chunk ->
                            append(chunk)
                            if ("ready" in this) ready.complete(Unit)
                        }
                    }
                }
                ready.await()
                session.write("ok\n")
                val transcript = output.await()
                session.isRunning.first { !it }
                assertEquals(7, session.exitCode.value)
                assertContains(transcript, "result=ok")
                assertEquals(1, Regex("ready").findAll(transcript).count())
                assertEquals(transcript, session.output().toList().joinToString(""))
            }
        } finally {
            hub.shutdown()
        }
    }
}
