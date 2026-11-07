package xyz.mederi.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mederi.api.SessionApi
import xyz.mederi.core.bridge.MederiEventAggregator
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.domain.model.*
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.computeChatItems
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InitialInputOrderingRaceTest {
    @Test
    fun inputAcceptedBetweenHistoryReadAndSubscriptionPrecedesAllStreamedWork() = runTest {
        val timestamp = "2026-11-05T00:00:00Z"
        val session = Session("s", "p", "test", SessionStatus.IDLE, AgentMode.AUTONOMOUS,
            null, null, createdAt = timestamp, updatedAt = timestamp)
        val events = MutableSharedFlow<MederiEvent>() // 宿主总线同样 replay=0。
        var history = emptyList<Message>()
        val api = Proxy.newProxyInstance(SessionApi::class.java.classLoader, arrayOf(SessionApi::class.java)) { _, method, _ ->
            when (method.name) {
                "get" -> session
                "listMessages" -> history
                "contextUsedTokens" -> 0
                "listSubagentsForSession" -> emptyList<Any>()
                "events" -> events
                else -> error("Unexpected API ${method.name}")
            }
        } as SessionApi
        val hydrating = CompletableDeferred<Unit>()
        val finishHydration = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<ConversationState>()
        val observer = backgroundScope.launch {
            MederiEventAggregator.observe("s", api, { null }, workspaceInfo = {
                hydrating.complete(Unit)
                finishHydration.await()
                null
            }).collect { snapshots.add(it) }
        }
        hydrating.await()
        history = listOf(Message("input", "s", MessageRole.USER, listOf(MessagePart.Text("request")),
            createdAt = timestamp, turnId = "turn"))
        assertEquals(0, events.subscriptionCount.value)
        events.emit(MederiEvent(EventType.SESSION_UPDATED, "s", timestamp = timestamp))
        finishHydration.complete(Unit)
        runCurrent()
        assertEquals(1, events.subscriptionCount.value)
        assertTrue(snapshots.single().messages.isEmpty(), "Stable reproduction: initial snapshot missed input")
        for ((index, type) in listOf("response_started", "reasoning", "text").withIndex()) {
            events.emit(MederiEvent(EventType.MESSAGE_DELTA, "s", "response",
                mapOf("type" to type, "content" to "chunk-$index", "turnId" to "turn"), timestamp))
            runCurrent()
            val snapshot = snapshots.last()
            assertEquals(listOf("input", "response"), snapshot.messages.map { it.id })
            assertEquals(ChatRole.User, snapshot.messages.first().role)
            val items = computeChatItems(snapshot.messages, true, snapshot)
            assertTrue((items.first() as ChatListItem.TextMessage).isUser)
            assertEquals(1, items.filterIsInstance<ChatListItem.WorkTraceBlock>().count { it.isRunning })
        }
        observer.cancel()
    }
}
