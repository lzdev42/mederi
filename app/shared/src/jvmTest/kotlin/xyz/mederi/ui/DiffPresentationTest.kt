package xyz.mederi.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.core.mock.MockAiCore
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiffPresentationTest {
    private fun diff(path: String) = FileDiff(path, "before", "after", 1, 1)
    private class Fake : AiCore by MockAiCore() {
        val reads = mutableListOf<Pair<String, String?>>()
        var read: suspend (String, String?) -> Result<List<FileDiff>> = { _, _ -> Result.success(listOf(FileDiff("one", "a", "b", 1, 1))) }
        override suspend fun getFileDiffs(conversationId: String, messageId: String?): Result<List<FileDiff>> {
            reads += conversationId to messageId; return read(conversationId, messageId)
        }
    }
    @Test fun opensOnlyWhenVisibleAndRepeatedInFlightOpenCoalesces() = runTest {
        val core = Fake(); val gate = CompletableDeferred<Result<List<FileDiff>>>()
        core.read = { _, _ -> gate.await() }
        val p = DiffPresenter(core, backgroundScope)
        p.bind("a"); p.open("turn", "two"); runCurrent(); assertTrue(core.reads.isEmpty())
        p.setVisible(true); runCurrent(); repeat(10) { p.open("turn", "two"); p.refresh() }; runCurrent()
        assertEquals(listOf<Pair<String, String?>>("a" to "turn"), core.reads); assertTrue(p.state.loading)
        gate.complete(Result.success(listOf(diff("one"), diff("two")))); runCurrent()
        assertEquals("two", p.state.selectedPath); assertTrue(p.state.loaded); assertFalse(p.state.loading); p.close()
    }
    @Test fun refreshKeepsMessageScopeValidSelectionAndSnapshotUntilSuccess() = runTest {
        val core = Fake(); var values = listOf(diff("one"), diff("two"))
        val gate = CompletableDeferred<Result<List<FileDiff>>>()
        core.read = { _, _ -> if (core.reads.size == 2) gate.await() else Result.success(values) }
        val p = DiffPresenter(core, backgroundScope); p.bind("a"); p.open("turn", "two"); p.setVisible(true); runCurrent()
        p.refresh(); runCurrent(); assertEquals(values, p.state.items); assertEquals("turn", p.state.messageId)
        gate.complete(Result.success(values)); runCurrent(); assertEquals("two", p.state.selectedPath)
        values = listOf(diff("one")); p.refresh(); runCurrent(); assertEquals("one", p.state.selectedPath)
        p.select("missing"); assertEquals("one", p.state.selectedPath)
        assertTrue(core.reads.all { it == "a" to "turn" }); p.close()
    }
    @Test fun sessionSwitchRejectsNonCancellableLateResultAndRestoresPerSessionState() = runTest {
        val core = Fake(); val old = CompletableDeferred<Result<List<FileDiff>>>()
        core.read = { id, _ -> if (id == "a") withContext(NonCancellable) { old.await() } else Result.success(listOf(diff("b"))) }
        val p = DiffPresenter(core, backgroundScope); p.bind("a"); p.open("a-turn", "a/wanted"); p.setVisible(true); runCurrent()
        p.bind("b"); assertTrue(p.state.items.isEmpty()); p.open("b-turn", null); p.setVisible(true); runCurrent()
        old.complete(Result.success(listOf(diff("stale-a")))); runCurrent()
        assertEquals("b", p.state.items.single().filePath); assertEquals("b-turn", p.state.messageId)
        p.bind("a"); assertFalse(p.state.loaded); assertEquals("a-turn", p.state.messageId); assertEquals("a/wanted", p.state.selectedPath)
        p.bind("b"); assertEquals("b", p.state.selectedPath); p.close()
    }
    @Test fun switchingTurnClearsOldSnapshotAndRejectsOldSameSessionResult() = runTest {
        val core = Fake(); val old = CompletableDeferred<Result<List<FileDiff>>>()
        core.read = { _, turn -> if (turn == "old") withContext(NonCancellable) { old.await() } else Result.success(listOf(diff("fresh"))) }
        val p = DiffPresenter(core, backgroundScope); p.bind("a"); p.setVisible(true); p.open("old", null); runCurrent()
        p.open("new", "missing"); assertTrue(p.state.items.isEmpty()); runCurrent()
        old.complete(Result.success(listOf(diff("old")))); runCurrent()
        assertEquals("new", p.state.messageId); assertEquals("fresh", p.state.selectedPath); p.close()
    }
    @Test fun hidingDoesNotAcceptLateResultsAndReopeningResumesWithoutErrors() = runTest {
        val core = Fake(); val old = CompletableDeferred<Result<List<FileDiff>>>()
        core.read = { _, _ -> if (core.reads.size == 1) withContext(NonCancellable) { old.await() } else Result.success(listOf(diff("fresh"))) }
        val p = DiffPresenter(core, backgroundScope); p.bind("a"); p.open(null, null); p.setVisible(true); runCurrent()
        p.setVisible(false); assertFalse(p.state.loading); p.refresh(); runCurrent(); assertEquals(1, core.reads.size)
        p.setVisible(true); runCurrent(); old.complete(Result.success(listOf(diff("stale")))); runCurrent()
        assertEquals("fresh", p.state.selectedPath); assertNull(p.state.error); p.close()
    }
    @Test fun failureRetainsSnapshotAndRetryCancellationAndEmptyHaveDistinctStates() = runTest {
        val core = Fake(); var mode = "success"
        core.read = { _, _ -> when (mode) {
            "fail" -> Result.failure(IllegalStateException("permission"))
            "throw" -> throw IllegalStateException("io")
            "cancel" -> Result.failure(CancellationException("cancel"))
            "empty" -> Result.success(emptyList())
            else -> Result.success(listOf(diff("one")))
        } }
        val p = DiffPresenter(core, backgroundScope); p.bind("a"); p.open(null, null); p.setVisible(true); runCurrent()
        mode = "fail"; p.refresh(); runCurrent(); assertTrue(p.state.loaded); assertEquals("one", p.state.selectedPath)
        assertEquals(listOf("permission"), p.state.error?.args)
        mode = "throw"; p.refresh(); runCurrent(); assertEquals(listOf("io"), p.state.error?.args)
        mode = "cancel"; p.refresh(); runCurrent(); assertFalse(p.state.loading); assertNull(p.state.error)
        mode = "empty"; p.refresh(); runCurrent(); assertTrue(p.state.loaded); assertTrue(p.state.items.isEmpty()); assertNull(p.state.selectedPath); p.close()
    }
    @Test fun initialFailureDoesNotMasqueradeAsLoadedEmptyAndCloseRejectsFurtherQueries() = runTest {
        val core = Fake(); core.read = { _, _ -> Result.failure(IllegalStateException("offline")) }
        val p = DiffPresenter(core, backgroundScope); p.bind("a"); p.open(null, null); p.setVisible(true); runCurrent()
        assertFalse(p.state.loaded); assertNotNull(p.state.error)
        p.close(); p.bind("b"); p.open(null, null); p.setVisible(true); p.refresh(); runCurrent(); assertEquals(1, core.reads.size)
    }
    @Test fun hiddenExplicitReopenOfCachedTargetActuallyRefreshes() = runTest {
        val core = Fake(); val p = DiffPresenter(core, backgroundScope)
        p.bind("a"); p.open("turn", null); p.setVisible(true); runCurrent(); p.setVisible(false)
        p.open("turn", null); p.setVisible(true); runCurrent(); assertEquals(2, core.reads.size); p.close()
    }
}
