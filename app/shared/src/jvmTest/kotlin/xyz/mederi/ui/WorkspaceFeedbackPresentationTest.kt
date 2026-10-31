package xyz.mederi.ui

import mederi.app.shared.generated.resources.*
import kotlin.test.*

class WorkspaceFeedbackPresentationTest {
    private val a = FeedbackOwner.Session("a")
    private val b = FeedbackOwner.Session("b")
    private fun message(text: String) = UiMessage(Res.string.err_generic, listOf(text))
    private fun core(text: String = "core", id: String = "error-id") = ErrorState(message(text), "diagnostic-$text", id, true)

    @Test fun localFailureHasNoInheritedCoreDiagnosticIdOrContinueCapability() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); p.accept(a, core()); p.showDetail()
        p.showLocal(a, message("local"))
        assertEquals(ErrorState(message("local")), p.state)
        p.showDetail(); assertTrue(p.state.isDetailOpen); assertNull(p.state.diagnostic); assertFalse(p.state.isStreamInterrupted)
    }
    @Test fun dismissedIdenticalCoreSnapshotStaysDismissedButNewErrorReappears() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); p.accept(a, core()); p.dismiss()
        repeat(3) { p.accept(a, core()) }; assertEquals(ErrorState(), p.state)
        p.bind(b); p.bind(a); p.accept(a, core()); assertNull(p.state.message)
        p.accept(a, core(id = "new-id")); assertEquals("new-id", p.state.id); assertTrue(p.state.isStreamInterrupted)
        p.dismiss(); p.accept(a, ErrorState()); p.accept(a, core(id = "new-id")); assertNotNull(p.state.message)
    }
    @Test fun lateLocalFailureBelongsToOriginalSessionAndDraftProject() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); val op = p.begin(a); p.bind(b)
        p.publish(op, message("a failure")); assertNull(p.state.message)
        p.bind(a); assertEquals(message("a failure"), p.state.message)
        val one = FeedbackOwner.Draft("one"); val two = FeedbackOwner.Draft("two")
        p.bind(one); val draft = p.begin(one); p.bind(two); p.publish(draft, message("draft failure"))
        assertNull(p.state.message); p.bind(one); assertEquals(message("draft failure"), p.state.message)
    }
    @Test fun newerOperationAndExplicitDismissInvalidateOlderFeedback() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); val old = p.begin(a); val fresh = p.begin(a)
        p.publish(fresh, message("new")); p.publish(old, message("old")); assertEquals(message("new"), p.state.message)
        p.dismiss(); p.publish(fresh, message("dismissed")); assertNull(p.state.message); assertFalse(p.isCurrent(fresh))
    }
    @Test fun newCoreErrorSupersedesOlderLocalOperationButOrdinarySnapshotDoesNotEraseLocalFailure() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); val old = p.begin(a)
        p.publish(old, message("local")); p.accept(a, ErrorState()); assertEquals(message("local"), p.state.message)
        p.accept(a, core()); p.publish(old, message("late local")); assertEquals(core(), p.state)
    }
    @Test fun detailsAreBoundToErrorIdentityAndNeverFollowOwnerSwitchOrReplacement() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); p.accept(a, core()); p.showDetail(); assertTrue(p.state.isDetailOpen)
        p.accept(a, core()); assertTrue(p.state.isDetailOpen)
        p.bind(b); assertFalse(p.state.isDetailOpen); p.bind(a); assertFalse(p.state.isDetailOpen)
        p.showDetail(); p.accept(a, core("replacement")); assertFalse(p.state.isDetailOpen)
        p.showDetail(); p.dismissDetail(); assertFalse(p.state.isDetailOpen); assertNotNull(p.state.message)
    }
    @Test fun clearingCoreWarningResetsInterruptionAndEmptyStateCannotOpenDetails() {
        val p = WorkspaceFeedbackPresenter(); p.bind(a); p.accept(a, core()); p.accept(a, ErrorState())
        assertEquals(ErrorState(), p.state); p.showDetail(); assertFalse(p.state.isDetailOpen)
    }
}
