package xyz.mederi.core.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * 终端会话契约（同 RemoteControlHooks/SandboxHooks 模式——平台能力注入，不进 AiCore 主契约）。
 *
 * 仅本机进程实现（desktop / server 的 jvmMain PtyTerminalHub）；
 * wasmJs/移动端是遥控端，UI 用 `as?` 安全转型，null = 该端无本地终端（后续接远程 WS 客户端）。
 *
 * 会话语义：一个会话 = 一个常驻 shell（pty4j 拉起），key 由调用方给定
 * （约定 `"project:<id>"` / `"local"`）；同一 key 重复 get = 复用已有会话。
 * 会话随宿主进程生命周期（进程退出 → OS 关 pty master → SIGHUP）。
 */
interface TerminalManager {

    /** 获取或创建会话；[cwd] 为 null 时继承用户 home。创建失败抛 [TerminalException]。 */
    fun getOrCreate(key: String, cwd: String?, title: String): TerminalSession

    /** 查找已有会话；不存在返回 null。 */
    fun find(key: String): TerminalSession?

    /** 所有活跃会话（dock 终端列表/恢复用）。 */
    fun all(): List<TerminalSession>
}

/**
 * 一个常驻 shell 会话。output 是 pty 原始字节流（含 ANSI/回显），
 * 渲染方负责 VT 解释（xterm.js）。
 */
interface TerminalSession {
    val key: String
    val title: String
    val isRunning: StateFlow<Boolean>
    val exitCode: StateFlow<Int?>

    /** 订阅增量输出（冷流：每个订阅者从当前缓冲尾部开始，保证新客户端先回放 scrollback 再续流）。 */
    fun output(): kotlinx.coroutines.flow.Flow<String>

    /** 写入 stdin（含控制字符，如 "\u0003" = Ctrl-C）。 */
    suspend fun write(text: String)

    /** 通知会话当前视口尺寸（控制权持有者才调用）。 */
    fun resize(cols: Int, rows: Int)

    /** 终止会话（等效终端里关窗口，SIGHUP 语义），并从 registry 移除。 */
    fun kill()
}

/** 终端会话创建/操作失败（shell 探测失败、pty 拉起失败等）。 */
class TerminalException(message: String, cause: Throwable? = null) : Exception(message, cause)
