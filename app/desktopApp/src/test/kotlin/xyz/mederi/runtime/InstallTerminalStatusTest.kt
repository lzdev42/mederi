package xyz.mederi.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallTerminalStatusTest {
    @Test
    fun recognizesAuthorizationPromptsAcrossTerminalReads() {
        val status = InstallTerminalStatus()
        assertNull(status.accept("[Mederi pass"))
        assertEquals(InstallStage.PASSWORD, status.accept("word] "))
        assertEquals(InstallStage.INSTALLING, status.accept("\r\n[Mederi] Installing\r\n"))
        assertEquals(
            InstallStage.CONTINUE_HOMEBREW,
            status.accept("Press \u001b[1mRETURN\u001b[0m/\u001b[1mENTER\u001b[0m to continue"),
        )
        assertEquals(
            InstallStage.WINDOWS_APPROVAL,
            status.accept("\r\n[Mederi] Windows approval\r\n"),
        )
    }
}
