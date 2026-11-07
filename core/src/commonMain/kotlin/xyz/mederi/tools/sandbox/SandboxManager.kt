package xyz.mederi.tools.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.SettingsStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
enum class ExecutionPermissionMode { NEEDS_APPROVAL, FULL_ACCESS }

@Serializable
data class SandboxSettings(
    val mode: ExecutionPermissionMode = ExecutionPermissionMode.NEEDS_APPROVAL,
    val extraWritablePaths: List<String> = emptyList(),
    val version: Long = 0,
    val deniedPaths: List<String> = emptyList(),
    val allowedCommands: List<String> = emptyList(),
    val deniedCommands: List<String> = emptyList(),
)

/** 只描述这一条不可变操作；批准不写入长期白名单，也不适用于下一次调用。 */
@Serializable
data class ExecutionApprovalRequest(
    val id: String,
    val conversationId: String,
    val executionSessionId: String,
    val toolName: String,
    val command: String? = null,
    val paths: List<String> = emptyList(),
    val cwd: String? = null,
    val access: String,
    val reason: String? = null,
)

/**
 * 每个 Mederi 实例的一份项目权限真理源。配置存 SettingsStore，活跃授权仅存内存。
 * 所有审批状态变更与通知入队在同一个 mutex 内排序，等待用户时绝不占锁。
 * 通知由单个自动排空的发布协程顺序发送；慢订阅者不能阻塞取消/批准状态机。
 */
class SandboxManager(
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
    private val eventBus: MutableSharedFlow<MederiEvent> = MutableSharedFlow(extraBufferCapacity = 64),
    val protectedDirectories: List<String> = emptyList(),
    private val projectManager: xyz.mederi.project.ProjectManager? = null,
) {
    companion object {
        fun settingsKey(projectId: String): String {
            require(projectId.isNotBlank()) { "Project ID must not be blank" }
            return "sandbox.projects.$projectId.settings"
        }
    }
    private data class Pending(val projectId: String, val request: ExecutionApprovalRequest, val generation: Long, val result: CompletableDeferred<Boolean>)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class ProjectState(val settings: SandboxSettings, val generation: Long)
    private val projects = ConcurrentHashMap<String, ProjectState>()
    internal fun generation(projectId: String): Long = projects[projectId]?.generation ?: 0L
    private val pending = ConcurrentHashMap<String, Pending>()
    private val notificationQueue = ConcurrentLinkedQueue<MederiEvent>()
    private val publishing = AtomicBoolean(false)
    private val publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var closed = false

    suspend fun getSettings(projectId: String): SandboxSettings = mutex.withLock {
        validateProject(projectId)
        loadSettings(projectId).settings
    }

    internal suspend fun snapshot(projectId: String): Pair<SandboxSettings, Long> = mutex.withLock {
        val state = loadSettings(projectId)
        state.settings to state.generation
    }

    private suspend fun validateProject(projectId: String) {
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        projectManager?.require(projectId)
    }

    private suspend fun loadSettings(projectId: String): ProjectState {
        ensureOpen()
        projects[projectId]?.let { return it }
        validateProject(projectId)
        // 旧宿主 key 和设备偏好不作为项目权限回退，避免隐式向所有项目授予完全访问。
        val raw = settingsStore.get(settingsKey(projectId))
        val loaded = if (raw == null) SandboxSettings() else try {
            json.decodeFromString<SandboxSettings>(raw)
        } catch (failure: Exception) {
            throw MederiValidationException("项目沙盒权限配置无法解析，请修复配置；命令未执行", failure)
        }
        validate(loaded)
        return ProjectState(loaded, 0L).also { projects[projectId] = it }
    }

    suspend fun updateSettings(projectId: String, settings: SandboxSettings, expectedVersion: Long? = null): SandboxSettings = mutex.withLock {
        currentCoroutineContext().ensureActive()
        ensureOpen()
        validateProject(projectId)
        validate(settings)
        val previous = loadSettings(projectId)
        if (expectedVersion != null && expectedVersion != previous.settings.version) {
            throw MederiStateException("Project execution permissions changed; reload settings before saving.")
        }
        val normalized = settings.copy(
            extraWritablePaths = settings.extraWritablePaths.map { java.io.File(it).canonicalPath }.distinct(),
            deniedPaths = settings.deniedPaths.map { java.io.File(it).canonicalPath }.distinct(),
            allowedCommands = settings.allowedCommands.map(String::trim).distinct(),
            deniedCommands = settings.deniedCommands.map(String::trim).distinct(),
            version = Math.addExact(previous.settings.version, 1),
        )
        // 单个项目的持久化、缓存、代次与申请失效作为一个完整提交；不撤销其它项目的请求。
        withContext(NonCancellable) {
            settingsStore.set(settingsKey(projectId), json.encodeToString(SandboxSettings.serializer(), normalized))
            projects[projectId] = ProjectState(normalized, previous.generation + 1)
            pending.values.filter { it.projectId == projectId }.forEach { finish(it, false, "SETTINGS_CHANGED") }
            emit(EventType.SANDBOX_SETTINGS_CHANGED, "", mapOf("projectId" to projectId,
                "settings" to json.encodeToString(SandboxSettings.serializer(), normalized)))
            normalized
        }
    }

    private fun validate(settings: SandboxSettings) {
        try {
            require(settings.version >= 0) { "Sandbox settings version must not be negative" }
            SandboxPaths(emptyList(), settings.extraWritablePaths, emptyList(), deniedPaths = settings.deniedPaths)
            CommandRules.validate(settings.allowedCommands + settings.deniedCommands)
        }
        catch (failure: IllegalArgumentException) { throw MederiValidationException(failure.message ?: "沙盒路径非法", failure) }
    }

    fun pendingApprovals(conversationId: String): List<ExecutionApprovalRequest> = pending.values
        .map { it.request }.filter { it.conversationId == conversationId }.sortedBy { it.id }

    suspend fun requestApproval(projectId: String, request: ExecutionApprovalRequest, expectedGeneration: Long? = null): Long {
        currentCoroutineContext().ensureActive()
        var registered: Pending? = null
        try {
            val (approval, acceptedGeneration) = mutex.withLock {
                val state = loadSettings(projectId)
                if (expectedGeneration != null && state.generation != expectedGeneration) throw MederiStateException("Execution permissions changed; retry the operation.")
                if (state.settings.mode == ExecutionPermissionMode.FULL_ACCESS) return@withLock null to state.generation
                val item = Pending(projectId, request, state.generation, CompletableDeferred())
                check(pending.putIfAbsent(request.id, item) == null)
                registered = item
                emit(EventType.EXECUTION_APPROVAL_REQUESTED, request.conversationId,
                    mapOf("request" to json.encodeToString(ExecutionApprovalRequest.serializer(), request)))
                item to state.generation
            }
            if (approval == null) return acceptedGeneration
            if (!approval.result.await()) throw MederiStateException("Execution permission denied or cancelled; nothing was executed.")
            currentCoroutineContext().ensureActive()
            if (generation(projectId) != approval.generation) throw MederiStateException("Execution permissions changed; request approval again.")
            return approval.generation
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { registered?.let { if (pending[request.id] === it) finish(it, false, "CANCELLED") } }
            }
        }
    }

    suspend fun resolve(conversationId: String, id: String, approved: Boolean) = mutex.withLock {
        val item = pending[id]?.takeIf { it.request.conversationId == conversationId && it.generation == generation(it.projectId) }
            ?: throw MederiStateException("Execution approval is no longer pending or belongs to another conversation.")
        finish(item, approved, if (approved) "APPROVED" else "DENIED")
    }

    /** abort/子代理退出只取消自己的调用；父代理阶段完成不得取消仍在后台等待的子代理。 */
    suspend fun cancelExecution(executionSessionId: String) = withContext(NonCancellable) {
        mutex.withLock { pending.values.filter { it.request.executionSessionId == executionSessionId }.forEach { finish(it, false, "CANCELLED") } }
    }

    /** 用户显式中止父会话时取消其全部申请；普通父 turn 阶段结束不能调用此方法。 */
    suspend fun cancelConversation(conversationId: String) = withContext(NonCancellable) {
        mutex.withLock { pending.values.filter { it.request.conversationId == conversationId }.forEach { finish(it, false, "CANCELLED") } }
    }

    /**
     * 宿主销毁时释放发布任务并拒绝所有剩余申请。关闭后不再接受新操作。
     * 关闭不是可恢复会话事件，因此丢弃尚未投递的通知；重建快照只会得到空活体申请。
     */
    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                projects.replaceAll { _, state -> state.copy(generation = state.generation + 1) }
                pending.values.toList().forEach { finish(it, false, "CANCELLED") }
                notificationQueue.clear()
            }
        }
        publisherScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun ensureOpen() {
        if (closed) throw MederiStateException("Sandbox permissions have been closed.")
    }

    private suspend fun finish(item: Pending, allowed: Boolean, status: String) = withContext(NonCancellable) {
        if (!pending.remove(item.request.id, item)) return@withContext
        // 先把终态按序入队再唤醒执行；通知背压不影响权威状态与等待者收敛。
        emit(EventType.EXECUTION_APPROVAL_RESOLVED, item.request.conversationId,
            mapOf("requestId" to item.request.id, "status" to status))
        item.result.complete(allowed)
    }

    private fun emit(type: EventType, sessionId: String, payload: Map<String, String>) {
        if (closed) return
        notificationQueue.add(MederiEvent(type, sessionId, payload = payload, timestamp = Instant.now().toString()))
        publishNotifications()
    }

    private fun publishNotifications() {
        if (closed || !publishing.compareAndSet(false, true)) return
        // 普通 buffered/无订阅场景同步按序公布，保持 RESOLVED 先于后续工具/完成事件。
        // 只有订阅者确实形成背压时才交给独立协程，状态机不能等待其消费。
        while (true) {
            val next = notificationQueue.peek()
            if (next == null) {
                finishPublishing()
                return
            }
            if (!eventBus.tryEmit(next)) break
            notificationQueue.poll()
        }
        publisherScope.launch {
            try {
                while (isActive) {
                    val next = notificationQueue.poll() ?: break
                    eventBus.emit(next)
                }
            } finally {
                finishPublishing()
            }
        }
    }

    private fun finishPublishing() {
        publishing.set(false)
        // 排空与入队可以交错；交回发布权之后再次检查，防止最后一条通知遗漏。
        if (!closed && notificationQueue.isNotEmpty()) publishNotifications()
    }
}
