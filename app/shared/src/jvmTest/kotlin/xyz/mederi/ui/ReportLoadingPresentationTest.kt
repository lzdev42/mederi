package xyz.mederi.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportLoadingPresentationTest {
    private fun request(session: String = "a", event: String = "e", path: String? = "report.md", summary: String = "summary") =
        ReportRequest(ReportKey(session, event), path, summary)

    @Test fun duplicateOpenSharesReadAndCachesExactContent() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        val r = request(); val source = Any(); var calls = 0
        val gate = CompletableDeferred<Result<String?>>()
        val read: suspend () -> Result<String?> = { calls++; gate.await() }
        p.select(r, source, read); assertEquals(ReportLoadState.Loading, p.state(r.key))
        repeat(10) { p.select(r, source, read) }; runCurrent(); assertEquals(1, calls)
        gate.complete(Result.success("  正文\n\n")); runCurrent()
        assertEquals(ReportLoadState.Content("  正文\n\n"), p.state(r.key))
        p.deactivate(); p.activate(r.key); runCurrent(); assertEquals(1, calls); p.close()
    }

    @Test fun absentPathSourceNullAndBlankAreMissingWithoutFakeContent() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        for ((index, path) in listOf(null, "", "report.md", "report.md").withIndex()) {
            val r = request(event = "$index", path = path)
            p.select(r, null, if (index == 2) null else { { Result.success(" \n") } }); runCurrent()
            assertEquals(ReportLoadState.Missing, p.state(r.key)); assertEquals("summary", r.summary)
        }; p.close()
    }

    @Test fun failureAndThrownIoAreExplicitRetryCanRecoverAndCancellationIsNotError() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        val r = request(); var mode = 0
        p.select(r, Any(), { when (mode) {
            0 -> Result.failure(IllegalStateException("permission"))
            1 -> throw IllegalStateException("io")
            2 -> Result.failure(CancellationException("cancel"))
            else -> Result.success("fresh")
        } }); runCurrent()
        assertEquals(listOf("permission"), (p.state(r.key) as ReportLoadState.Error).message.args)
        mode = 1; p.retry(r.key); runCurrent()
        assertEquals(listOf("io"), (p.state(r.key) as ReportLoadState.Error).message.args)
        mode = 2; p.retry(r.key); runCurrent(); assertEquals(ReportLoadState.Idle, p.state(r.key))
        mode = 3; p.retry(r.key); runCurrent(); assertEquals(ReportLoadState.Content("fresh"), p.state(r.key)); p.close()
    }

    @Test fun lateCancelledReadCannotOverwriteNewSessionWithSameEventId() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        val a = request(); val b = request(session = "b"); val old = CompletableDeferred<Result<String?>>()
        p.select(a, Any(), { withContext(NonCancellable) { old.await() } }); runCurrent()
        p.select(b, Any(), { Result.success("b") }); runCurrent()
        old.complete(Result.success("old")); runCurrent()
        assertEquals(ReportLoadState.Idle, p.state(a.key)); assertEquals(ReportLoadState.Content("b"), p.state(b.key)); p.close()
    }

    @Test fun sameKeyRequestAndSourceReplacementInvalidateOldGenerationAndCache() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        val r = request(); val old = CompletableDeferred<Result<String?>>()
        val source = Any()
        p.select(r, source, { withContext(NonCancellable) { old.await() } }); runCurrent()
        val updated = r.copy(path = "new.md", summary = "new summary")
        p.select(updated, source, { Result.success("new") }); runCurrent()
        old.complete(Result.success("old")); runCurrent(); assertEquals(ReportLoadState.Content("new"), p.state(r.key))
        p.select(updated, Any(), { Result.success("replacement source") }); runCurrent()
        assertEquals(ReportLoadState.Content("replacement source"), p.state(r.key)); p.close()
    }

    @Test fun hiddenReadResumesOriginalSourceAndInactiveRetryDoesNothing() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        val r = request(); var calls = 0; val old = CompletableDeferred<Result<String?>>()
        p.select(r, Any(), { if (++calls == 1) withContext(NonCancellable) { old.await() } else Result.success("original source") }); runCurrent()
        p.deactivate(); assertEquals(ReportLoadState.Idle, p.state(r.key))
        p.retry(r.key); runCurrent(); assertEquals(1, calls)
        p.activate(r.key); runCurrent(); assertEquals(ReportLoadState.Content("original source"), p.state(r.key))
        old.complete(Result.success("stale")); runCurrent(); assertEquals(ReportLoadState.Content("original source"), p.state(r.key)); p.close()
    }

    @Test fun forcedRetryRejectsLateResultAndClosePreventsFurtherReads() = runTest {
        val p = ReportLoadPresenter(backgroundScope, StandardTestDispatcher(testScheduler))
        val r = request(); var calls = 0; val old = CompletableDeferred<Result<String?>>()
        p.select(r, Any(), { if (++calls == 1) withContext(NonCancellable) { old.await() } else Result.success("retry") }); runCurrent()
        p.retry(r.key); runCurrent(); old.complete(Result.success("old")); runCurrent()
        assertEquals(ReportLoadState.Content("retry"), p.state(r.key)); assertEquals(2, calls)
        p.close(); p.retry(r.key); p.activate(r.key); p.select(request(session = "new"), Any(), { calls++; Result.success("bad") }); runCurrent()
        assertEquals(2, calls)
    }
}
