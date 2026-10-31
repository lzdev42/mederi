package xyz.mederi.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.BackgroundTaskInfo
import xyz.mederi.core.mock.MockAiCore
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTaskPresentationTest {
    private fun task(id: String, status: String = "running") = BackgroundTaskInfo(id, "command", status, command = "fixture-$id", startedAt = "2026-10-30T00:00:00Z")
    private class Fake : AiCore by MockAiCore() {
        override val isReady = MutableStateFlow(true)
        val reads = mutableListOf<String>()
        val stops = mutableListOf<String>()
        var list: suspend (String) -> Result<List<BackgroundTaskInfo>> = { Result.success(emptyList()) }
        var stop: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        override suspend fun listBackgroundTasks(sessionId: String): Result<List<BackgroundTaskInfo>> { reads += sessionId; return list(sessionId) }
        override suspend fun stopBackgroundTask(taskId: String): Result<Unit> { stops += taskId; return stop(taskId) }
    }

    @Test
    fun repeatedSubscriptionsAndRefreshesShareOneSerialRequestAndReleaseIsIdempotent() = runTest {
        val core = Fake(); val gate = CompletableDeferred<Result<List<BackgroundTaskInfo>>>()
        var inFlight = 0; var peak = 0
        core.list = { inFlight++; peak = maxOf(peak, inFlight)
            try { if (core.reads.size == 1) gate.await() else Result.success(listOf(task("a"))) }
            finally { inFlight-- } }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val first = p.observe(); val second = p.observe(); runCurrent()
        repeat(20) { p.refresh() }; advanceTimeBy(5000); runCurrent()
        assertEquals(listOf("a"), core.reads)
        gate.complete(Result.success(listOf(task("a")))); runCurrent()
        assertEquals(listOf("a", "a"), core.reads); assertEquals(1, peak)
        first(); first(); advanceTimeBy(1000); runCurrent(); assertEquals(3, core.reads.size)
        second(); advanceTimeBy(10000); runCurrent(); assertEquals(3, core.reads.size)
        assertTrue(core.stops.isEmpty()); assertFalse(p.state.loading); p.close()
    }

    @Test
    fun lateNonCancellableSessionAResponseCannotOverwriteBOrItsOwnNewGeneration() = runTest {
        val core = Fake(); val old = CompletableDeferred<Result<List<BackgroundTaskInfo>>>()
        val fresh = CompletableDeferred<Result<List<BackgroundTaskInfo>>>()
        var aCalls = 0
        core.list = { id -> when {
            id == "b" -> Result.success(listOf(task("b")))
            ++aCalls == 1 -> withContext(NonCancellable) { old.await() }
            else -> fresh.await()
        } }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val release = p.observe(); runCurrent(); assertTrue(p.state.loading)
        p.bind("b"); runCurrent(); assertEquals(listOf("b"), p.state.tasks.map { it.taskId })
        old.complete(Result.success(listOf(task("stale-a")))); runCurrent()
        assertEquals("b", p.state.sessionId); assertEquals(listOf("b"), p.state.tasks.map { it.taskId })
        p.bind("a"); runCurrent(); assertTrue(p.state.tasks.isEmpty()); assertFalse(p.state.loaded)
        fresh.complete(Result.success(listOf(task("fresh-a")))); runCurrent()
        assertEquals(listOf("fresh-a"), p.state.tasks.map { it.taskId }); release(); p.close()
    }

    @Test
    fun hiddenThenShownSameSessionRejectsOldResponseAndNeverStopsCoreWork() = runTest {
        val core = Fake(); val old = CompletableDeferred<Result<List<BackgroundTaskInfo>>>()
        core.list = { if (core.reads.size == 1) withContext(NonCancellable) { old.await() } else Result.success(listOf(task("fresh"))) }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val release = p.observe(); runCurrent(); release()
        val next = p.observe(); runCurrent(); assertEquals(listOf("fresh"), p.state.tasks.map { it.taskId })
        old.complete(Result.success(listOf(task("old")))); runCurrent()
        assertEquals(listOf("fresh"), p.state.tasks.map { it.taskId }); assertTrue(core.stops.isEmpty())
        next(); p.close()
    }

    @Test
    fun failuresRetainLastKnownTasksUntilSuccessfulReadAndCancellationIsNotAnError() = runTest {
        val core = Fake(); var mode = "success"
        core.list = { when (mode) {
            "failure" -> Result.failure(IllegalStateException("fixture-query"))
            "cancel" -> throw CancellationException("fixture-cancel")
            else -> Result.success(listOf(task("a")))
        } }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val release = p.observe(); runCurrent()
        mode = "failure"; p.refresh(); runCurrent()
        assertTrue(p.state.loaded); assertEquals("a", p.state.tasks.single().taskId)
        assertEquals(listOf("fixture-query"), p.state.queryError?.args)
        mode = "success"; p.refresh(); runCurrent(); assertNull(p.state.queryError)
        mode = "cancel"; p.refresh(); runCurrent()
        assertFalse(p.state.loading); assertNull(p.state.queryError)
        mode = "success"; p.refresh(); runCurrent(); assertFalse(p.state.loading); assertEquals("a", p.state.tasks.single().taskId)
        release(); p.close()
    }

    @Test
    fun lateStopFailureBelongsToOriginalSessionSurvivesQuerySuccessAndCanBeDismissed() = runTest {
        val core = Fake(); val result = CompletableDeferred<Result<Unit>>()
        core.list = { id -> Result.success(listOf(task(id))) }; core.stop = { result.await() }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val release = p.observe(); runCurrent()
        p.stop("a"); p.stop("a"); runCurrent(); assertEquals(listOf("a"), core.stops)
        p.bind("b"); runCurrent(); assertTrue(p.state.stoppingTaskIds.isEmpty())
        result.complete(Result.failure(IllegalStateException("fixture-stop"))); runCurrent()
        assertTrue(p.state.stopErrors.isEmpty()); assertEquals("b", p.state.tasks.single().taskId)
        p.bind("a"); runCurrent()
        assertEquals(listOf("a", "fixture-stop"), p.state.stopErrors["a"]?.args)
        p.refresh(); runCurrent(); assertTrue("a" in p.state.stopErrors)
        p.stop("b"); runCurrent(); assertEquals(listOf("a"), core.stops, "Stale action must not stop another session's task")
        p.dismissStopError("a"); assertTrue(p.state.stopErrors.isEmpty()); release(); p.close()
    }

    @Test
    fun successfulStopOnlyRefreshesCoreAndDoesNotInventTerminalStatus() = runTest {
        val core = Fake(); val stopResult = CompletableDeferred<Result<Unit>>()
        val readResult = CompletableDeferred<Result<List<BackgroundTaskInfo>>>()
        core.list = { if (core.reads.size == 1) Result.success(listOf(task("a"))) else readResult.await() }
        core.stop = { stopResult.await() }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val release = p.observe(); runCurrent()
        p.stop("a"); runCurrent(); assertEquals(setOf("a"), p.state.stoppingTaskIds)
        stopResult.complete(Result.success(Unit)); runCurrent()
        assertTrue(p.state.loading); assertEquals("running", p.state.tasks.single().status)
        readResult.complete(Result.success(listOf(task("a", "stopped")))); runCurrent()
        assertEquals("stopped", p.state.tasks.single().status); assertTrue(p.state.stoppingTaskIds.isEmpty())
        p.stop("a"); runCurrent(); assertEquals(listOf("a"), core.stops); release(); p.close()
    }

    @Test
    fun stoppingCancellationClearsBusyWithoutInventingFailureAndRetryRemainsPossible() = runTest {
        val core = Fake(); core.list = { Result.success(listOf(task("a"))) }
        core.stop = { if (core.stops.size == 1) throw CancellationException("cancel") else Result.failure(CancellationException("cancel-result")) }
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        p.bind("a"); val release = p.observe(); runCurrent()
        repeat(2) { p.stop("a"); runCurrent(); assertTrue(p.state.stoppingTaskIds.isEmpty()); assertTrue(p.state.stopErrors.isEmpty()) }
        assertEquals(listOf("a", "a"), core.stops); release(); p.close()
    }

    @Test
    fun readinessNoSessionAndCloseDoNotTriggerFakeQueriesOrCoreStop() = runTest {
        val core = Fake(); core.isReady.value = false
        val p = BackgroundTaskPresenter(core, backgroundScope, 1000)
        val release = p.observe(); runCurrent(); assertNull(p.state.sessionId); assertTrue(core.reads.isEmpty())
        p.bind("a"); runCurrent(); assertTrue(p.state.loading); assertTrue(core.reads.isEmpty())
        core.isReady.value = true; runCurrent(); assertEquals(listOf("a"), core.reads)
        p.bind(null); assertNull(p.state.sessionId); assertFalse(p.state.loaded); assertTrue(p.state.tasks.isEmpty())
        p.close(); p.bind("b"); p.refresh(); p.observe()(); advanceTimeBy(10000); runCurrent()
        assertEquals(listOf("a"), core.reads); assertTrue(core.stops.isEmpty()); release()
    }
}
