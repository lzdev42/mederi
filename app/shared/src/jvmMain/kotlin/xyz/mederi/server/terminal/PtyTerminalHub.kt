package xyz.mederi.server.terminal

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.TerminalException
import xyz.mederi.core.contract.TerminalManager
import xyz.mederi.core.contract.TerminalSession
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 终端会话注册表（会话唯一拥有者，desktop 与 server 共用）。
 *
 * - 一个 key = 一个常驻 shell（pty4j 拉起）；key 约定 `"project:<id>"` / `"local"`（无项目）
 * - scrollback 环形缓冲（64KB 文本）留服务端：新客户端 attach 先回放再续流
 * - 生命周期：会话随宿主进程（进程退出 → OS 关 pty master → SIGHUP）；
 *   kill() = 显式销毁；exit 后自动从 registry 移除
 * - JVM-only：pty4j 无 KMP 替代品，仅 jvmMain
 */
class PtyTerminalHub : TerminalManager {

    private val sessions = ConcurrentHashMap<String, PtyTerminalSession>()
    private val privateSessions = ConcurrentHashMap<String, PtyTerminalSession>()
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getOrCreate(key: String, cwd: String?, title: String): TerminalSession =
        sessions.computeIfAbsent(key) { k ->
            PtyTerminalSession.create(k, cwd, title, hubScope) { sessions.remove(k, it) }
        }

    override fun find(key: String): TerminalSession? = sessions[key]

    override fun all(): List<TerminalSession> = sessions.values.toList()

    /** Starts a one-shot command in a local PTY that is absent from terminal lookup and remote routes. */
    fun startCommand(command: List<String>, title: String, cwd: String? = null): TerminalSession {
        require(command.isNotEmpty())
        val key = Uuid.random().toString()
        return privateSessions.computeIfAbsent(key) {
            PtyTerminalSession.createCommand(key, command, cwd, title, hubScope) {
                privateSessions.remove(key, it)
            }
        }
    }

    /** 宿主退出时显式收尾（正常路径下进程退出本身即 SIGHUP 全部会话）。 */
    fun shutdown() {
        sessions.values.forEach { it.kill() }
        privateSessions.values.forEach { it.kill() }
        sessions.clear()
        privateSessions.clear()
    }
}

/**
 * 一个常驻 shell 会话。output 为 pty 原始字节流（含 ANSI 与回显），渲染方负责 VT 解释。
 *
 * 专属读线程更新 scrollback，并向当前订阅者广播。回放与注册订阅使用同一把锁，
 * 因此订阅建立时不会丢掉或重复输出。读完尾部输出后关闭订阅。
 */
class PtyTerminalSession private constructor(
    override val key: String,
    override val title: String,
    private val process: PtyProcess,
    private val stdin: OutputStream,
    private val scope: CoroutineScope,
    private val oneShot: Boolean,
    private val onEnded: (PtyTerminalSession) -> Unit,
) : TerminalSession {

    private val scrollback = StringBuilder()
    private val outputLock = Any()
    private val subscribers = mutableSetOf<ProducerScope<String>>()
    private var outputFinished = false
    private val _isRunning = MutableStateFlow(true)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    override fun output(): Flow<String> = callbackFlow<String> {
        synchronized(outputLock) {
            if (scrollback.isNotEmpty()) trySend(scrollback.toString())
            if (outputFinished) close() else subscribers += this
        }
        awaitClose {
            synchronized(outputLock) { subscribers -= this }
        }
    }.buffer(if (oneShot) Channel.UNLIMITED else Channel.BUFFERED)

    override suspend fun write(text: String) {
        if (!_isRunning.value) return
        stdin.write(text.toByteArray(StandardCharsets.UTF_8))
        stdin.flush()
    }

    override fun resize(cols: Int, rows: Int) {
        if (!_isRunning.value) return
        if (cols > 0 && rows > 0) runCatching { process.setWinSize(WinSize(cols, rows)) }
    }

    override fun kill() {
        if (_isRunning.value) runCatching { process.destroy() }
    }

    private fun startReading() {
        scope.launch(Dispatchers.IO) {
            val buffer = CharArray(8192)
            try {
                process.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        val chunk = String(buffer, 0, n)
                        synchronized(outputLock) {
                            scrollback.append(chunk)
                            if (scrollback.length > SCROLLBACK_MAX_CHARS) {
                                scrollback.delete(0, scrollback.length - SCROLLBACK_MAX_CHARS)
                            }
                            subscribers.toList().forEach { it.trySend(chunk) }
                        }
                    }
                }
            } catch (_: Exception) {
                // pty 关闭（kill / 进程退出）引发的流异常属正常路径
            } finally {
                // 读到 EOF = pty master 已关（kill/进程退出）；顽固子进程给 5s 宽限
                val exited = runCatching { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
                val code = if (exited) runCatching { process.exitValue() }.getOrNull() else null
                runCatching { stdin.close() }
                _exitCode.value = code
                synchronized(outputLock) {
                    outputFinished = true
                    val closing = subscribers.toList()
                    subscribers.clear()
                    closing.forEach { it.close() }
                }
                _isRunning.value = false
                onEnded(this@PtyTerminalSession)
            }
        }
    }

    companion object {
        private const val SCROLLBACK_MAX_CHARS = 64 * 1024

        /** shell 探测链：$SHELL → bash → sh；Windows 交 cmd.exe。 */
        private fun detectShellCommand(): Array<String> {
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                return arrayOf("cmd.exe")
            }
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() }
                ?: listOf("/bin/bash", "/bin/sh").firstOrNull { File(it).canExecute() }
                ?: "/bin/sh"
            // 登录 shell：加载用户 profile，PATH 等环境与真实终端一致
            return arrayOf(shell, "--login")
        }

        fun create(
            key: String,
            cwd: String?,
            title: String,
            scope: CoroutineScope,
            onEnded: (PtyTerminalSession) -> Unit,
        ): PtyTerminalSession = start(key, detectShellCommand(), cwd, title, scope, oneShot = false, onEnded)

        internal fun createCommand(
            key: String,
            command: List<String>,
            cwd: String?,
            title: String,
            scope: CoroutineScope,
            onEnded: (PtyTerminalSession) -> Unit,
        ): PtyTerminalSession = start(key, command.toTypedArray(), cwd, title, scope, oneShot = true, onEnded)

        private fun start(
            key: String,
            command: Array<String>,
            cwd: String?,
            title: String,
            scope: CoroutineScope,
            oneShot: Boolean,
            onEnded: (PtyTerminalSession) -> Unit,
        ): PtyTerminalSession {
            val workingDir = cwd?.takeIf { File(it).isDirectory } ?: System.getProperty("user.home")
            val process = runCatching {
                PtyProcessBuilder()
                    .setCommand(command)
                    .setDirectory(workingDir)
                    .setEnvironment(ptyEnvironment())
                    .setRedirectErrorStream(oneShot)
                    .setUnixOpenTtyToPreserveOutputAfterTermination(oneShot)
                    .setInitialColumns(80)
                    .setInitialRows(24)
                    .start()
            }.getOrElse { throw TerminalException("终端启动失败: ${it.message}", it) }

            val session = PtyTerminalSession(key, title, process, process.outputStream, scope, oneShot, onEnded)
            session.startReading()
            return session
        }

        /** 终端环境：继承宿主环境 + 强制 TERM，保证颜色/TUI 正常。 */
        private fun ptyEnvironment(): Map<String, String> {
            val env = System.getenv().toMutableMap()
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            return env
        }
    }
}
