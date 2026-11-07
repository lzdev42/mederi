package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.awaitSelectionTestContent(description: String, isReady: () -> Boolean) {
    val started = TimeSource.Monotonic.markNow()
    while (true) {
        waitForIdle()
        if (isReady()) return
        assertTrue(started.elapsedNow() < 10.seconds, "Timed out waiting for $description")
        // Skiko's synchronous waitUntil advances Compose frames but cannot yield to the browser event loop.
        withContext(Dispatchers.Default) { delay(10) }
    }
}
