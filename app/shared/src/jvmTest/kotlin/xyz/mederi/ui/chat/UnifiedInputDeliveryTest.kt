package xyz.mederi.ui.chat

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ImageAttachment
import xyz.mederi.core.contract.models.PastedTextAttachment
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.AppState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnifiedInputDeliveryTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }

    private suspend fun app(api: AiCore, scope: CoroutineScope): AppState =
        AppState(api, InMemoryPreferencesStore(), scope).also {
            it.hydrate()
            it.selectModel(it.availableModels.value.first())
            it.selectProject("proj_1")
            it.selectConversation(null)
        }

    private fun image() = ImageAttachment("img", "input.png", "image/png", byteArrayOf(1, 2), "data:image/png;base64,AQI=")

    @Test
    fun `failed submission restores text and attachments without replacing newer draft`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val backend = MockAiCore().also { it.initialize() }
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val api = object : AiCore by backend {
                override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> {
                    entered.complete(Unit)
                    finish.await()
                    return Result.failure(IllegalStateException("transport failed"))
                }
            }
            val state = app(api, scope)
            val conv = backend.createConversation("proj_1", null).getOrThrow()
            val vm = WorkspaceViewModel(state)
            state.selectConversation(conv.id)
            until { vm.conversationId == conv.id && vm.isAttached }
            vm.pendingImages.add(image())
            vm.pendingPastedTexts.add(PastedTextAttachment("paste", 1, "source", 1, 6))
            vm.updateInputDraft(TextFieldValue("original input"))
            vm.send("original input")
            entered.await()
            vm.updateInputDraft(TextFieldValue("newer draft"))
            finish.complete(Unit)
            until { vm.inputDraft.text.contains("original input") }
            assertTrue(vm.inputDraft.text.contains("newer draft"))
            assertEquals(listOf("img"), vm.pendingImages.map { it.id })
            assertEquals(listOf("paste"), vm.pendingPastedTexts.map { it.id })
        } finally { scope.cancel() }
    }

    @Test
    fun `rapid first submissions share creation and retain draft typed during creation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val backend = MockAiCore().also { it.initialize() }
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val creates = AtomicInteger()
            val destinations = CopyOnWriteArrayList<String>()
            val received = CopyOnWriteArrayList<ChatPromptInput>()
            val api = object : AiCore by backend {
                override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> {
                    creates.incrementAndGet()
                    entered.complete(Unit)
                    finish.await()
                    return backend.createConversation(projectId, agent)
                }
                override suspend fun createConversation(input: CreateConversationInput): Result<Conversation> {
                    creates.incrementAndGet()
                    entered.complete(Unit)
                    finish.await()
                    return backend.createConversation(input)
                }
                override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> {
                    destinations.add(conversationId)
                    received.add(input)
                    return Result.success(Unit)
                }
            }
            val state = app(api, scope)
            val vm = WorkspaceViewModel(state)
            vm.send("first")
            vm.send("second")
            entered.await()
            vm.updateInputDraft(TextFieldValue("still typing"))
            finish.complete(Unit)
            until { received.size == 2 && vm.isAttached }
            assertEquals(1, creates.get())
            assertEquals(1, destinations.distinct().size)
            assertEquals(setOf("first", "second"), received.map { it.text }.toSet())
            assertEquals(2, received.map { it.messageId }.distinct().size)
            assertEquals("still typing", vm.inputDraft.text)
        } finally { scope.cancel() }
    }

    @Test
    fun `creation completion does not navigate away from another conversation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val backend = MockAiCore().also { it.initialize() }
            val other = backend.createConversation("proj_1", null).getOrThrow()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val received = CompletableDeferred<Unit>()
            val api = object : AiCore by backend {
                override suspend fun createConversation(projectId: String, agent: AgentOption?): Result<Conversation> {
                    entered.complete(Unit)
                    finish.await()
                    return backend.createConversation(projectId, agent)
                }
                override suspend fun createConversation(input: CreateConversationInput): Result<Conversation> {
                    entered.complete(Unit)
                    finish.await()
                    return backend.createConversation(input)
                }
                override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> {
                    received.complete(Unit)
                    return Result.success(Unit)
                }
            }
            val state = app(api, scope)
            val vm = WorkspaceViewModel(state)
            vm.send("background creation")
            entered.await()
            state.selectConversation(other.id)
            until { vm.conversationId == other.id && vm.isAttached }
            vm.updateInputDraft(TextFieldValue("other conversation draft"))
            finish.complete(Unit)
            received.await()
            assertEquals(other.id, vm.conversationId)
            assertEquals("other conversation draft", vm.inputDraft.text)
        } finally { scope.cancel() }
    }

    @Test
    fun `failure after switching restores original conversation instead of contaminating current draft`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val backend = MockAiCore().also { it.initialize() }
            val original = backend.createConversation("proj_1", null).getOrThrow()
            val other = backend.createConversation("proj_1", null).getOrThrow()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val api = object : AiCore by backend {
                override suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit> {
                    entered.complete(Unit)
                    finish.await()
                    return Result.failure(IllegalStateException("transport failed"))
                }
            }
            val state = app(api, scope)
            val vm = WorkspaceViewModel(state)
            state.selectConversation(original.id)
            until { vm.conversationId == original.id && vm.isAttached }
            vm.pendingImages.add(image())
            vm.send("restore original")
            entered.await()
            state.selectConversation(other.id)
            until { vm.conversationId == other.id && vm.isAttached }
            vm.updateInputDraft(TextFieldValue("keep other draft"))
            finish.complete(Unit)
            // 原会话的失败不应显示到当前会话；切回原会话后再检查反馈。
            assertNull(vm.error)
            assertEquals("keep other draft", vm.inputDraft.text)
            assertTrue(vm.pendingImages.isEmpty())
            state.selectConversation(original.id)
            until { vm.conversationId == original.id && vm.inputDraft.text.contains("restore original") }
            assertNotNull(vm.error)
            assertEquals(listOf("img"), vm.pendingImages.map { it.id })
        } finally { scope.cancel() }
    }
}
