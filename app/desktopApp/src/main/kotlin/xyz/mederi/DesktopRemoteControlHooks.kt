package xyz.mederi

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.server.Server
import xyz.mederi.ui.appstate.RemoteControlHooks
import xyz.mederi.ui.appstate.RemoteStartResult
import xyz.mederi.ui.appstate.TunnelStartResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * desktop 宿主实现：内嵌 server 启停 + Cloudflare 隧道。
 *
 * 隧道用 pty4j 以真实 pty 拉起 `cloudflared tunnel --no-autoupdate run --token <token>`
 * （连接器 token 模式）：本进程（含 kill -9 / 崩溃）退出时 OS 关闭 pty master，
 * 内核向 pty 前台进程组发 SIGHUP，cloudflared 随之退出 —— 与"终端关了进程就死"
 * 同语义，无需看门狗轮询。
 *
 * 启动后读 pty 输出确认连接建立（"Registered tunnel connection"）或报错退出，
 * 避免进程启动即退/连不上 Cloudflare 时误报成功（Cloudflare 面板显示 Inactive）。
 *
 * JVM-only：pty4j 无 KMP 替代品，此文件只在 desktop jvmMain 编译。
 */
class DesktopRemoteControlHooks(
    private val aiCore: MederiAiCore,
    private val webappDir: String?,
) : RemoteControlHooks {

    private var tunnelProcess: PtyProcess? = null
    private val homeDir: String = System.getProperty("user.home") ?: ""

    override suspend fun start(port: Int, password: String?): RemoteStartResult =
        Server.start(aiCore, port, password, webappDir)

    override fun stop() = Server.stop()

    override val isRunning: Boolean get() = Server.isRunning

    /** 本机局域网 IPv4（site-local，非回环）；拿不到返回 null。 */
    override val localAddress: String? by lazy {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { ip ->
                    // site-local：10/8、172.16/12、192.168/16
                    ip.startsWith("10.") ||
                        ip.startsWith("192.168.") ||
                        (ip.startsWith("172.") && ip.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true)
                }
        }.getOrNull()
    }

    /**
     * 解析 cloudflared 可执行路径：PATH 优先，失败回退各平台常见安装路径。
     *
     * GUI 进程的 PATH 通常很受限（macOS 只有 /usr/bin:/bin:/usr/sbin:/sbin，
     * Linux/Windows GUI 进程也常缺 homebrew / Program Files 路径），导致
     * ProcessBuilder("cloudflared",...) 找不到可执行文件；Windows 上还需
     * 显式带 .exe 扩展名。这里显式探测各平台常见安装位置兜底。
     */
    private fun resolveCloudflaredPath(): String? {
        // 1. PATH 上直接可执行（Windows 上 Java 会用 PATHEXT 补全扩展名）
        if (runVersionCheck("cloudflared")) return "cloudflared"
        // 2. 各平台常见绝对路径（File.exists 快速过滤，再 --version 确认可执行）
        val candidates = listOf(
            // macOS homebrew
            "/opt/homebrew/bin/cloudflared",
            "/usr/local/bin/cloudflared",
            // Linux 官方 .deb/.rpm 包 / 发行版 / snap / Linuxbrew
            "/usr/bin/cloudflared",
            "/snap/bin/cloudflared",
            "/home/linuxbrew/.linuxbrew/bin/cloudflared",
            // Windows 官方安装器（winget/choco/MSI）
            "C:\\Program Files (x86)\\cloudflared\\cloudflared.exe",
            "C:\\Program Files\\cloudflared\\cloudflared.exe",
        )
        return candidates.firstOrNull { path ->
            java.io.File(path).exists() && runVersionCheck(path)
        }
    }

    /** 跑 `cloudflared --version`，5 秒内正常退出即视为该路径可用。 */
    private fun runVersionCheck(executable: String): Boolean = runCatching {
        val p = ProcessBuilder(executable, "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS)
        p.exitValue() == 0
    }.getOrDefault(false)

    /** 探测 cloudflared：能解析到可执行路径即视为已安装。 */
    override fun isCloudflaredInstalled(): Boolean = resolveCloudflaredPath() != null

    /**
     * 启动隧道。启动后读 pty 输出确认 cloudflared 连上 Cloudflare 边缘网络，
     * 避免进程启动即退/连不上时误报成功（面板显示 Inactive）。
     *
     * cloudflared 健康启动输出 "Registered tunnel connection"（建立到边缘的连接，
     * 通常 4 条 = Healthy）；出错时输出 " ERR ..." 或直接退出。我们等到第一条
     * Registered 即返回 Started（后续连接异步建立），出错/退出返回 Failed（带日志行）。
     */
    override fun startTunnel(port: Int, token: String, domain: String?): TunnelStartResult {
        // token 为空 → 直接失败
        if (token.isBlank()) return TunnelStartResult.Failed("请先填写隧道 token")

        // 已在运行 → 幂等返回
        val running = tunnelProcess
        if (running != null && running.isAlive) {
            return TunnelStartResult.Started(formatTunnelUrl(domain))
        }
        val cloudflared = resolveCloudflaredPath() ?: return TunnelStartResult.NotInstalled

        return runCatching {
            // 用绝对路径启动，避免 pty 子进程 PATH 受限找不到 cloudflared。
            // --no-autoupdate 防止 cloudflared 自动更新重启导致 pty 断开（官方推荐）。
            // 不调 setEnvironment —— 默认继承父进程完整环境（含 PATH/HOME），
            // setEnvironment 会替换而非合并环境，丢掉 PATH 等会让 cloudflared 运行异常。
            val process = PtyProcessBuilder()
                .setCommand(arrayOf(cloudflared, "tunnel", "--no-autoupdate", "run", "--token", token))
                .setDirectory(homeDir)
                .start()
            tunnelProcess = process

            // 读 pty 输出，等待 "Registered tunnel connection" 或进程退出/超时。
            // pty 模式下 stdout+stderr 合并为单一流，getInputStream 可读到 cloudflared 全部日志。
            // 收集最近 20 行作为诊断信息附在失败消息里，方便用户排查（token 无效、防火墙拦截等）。
            val latch = CountDownLatch(1)
            val outcome = AtomicReference<TunnelStartResult?>(null)
            val recentLines = java.util.ArrayDeque<String>(20)
            fun diagLines(): String = synchronized(recentLines) { recentLines.joinToString("\n") }
            Thread {
                val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        synchronized(recentLines) {
                            if (recentLines.size >= 20) recentLines.pollFirst()
                            recentLines.addLast(line)
                        }
                        if (line.contains("Registered tunnel connection")) {
                            // 至少一条连接成功 → 隧道已连通 Cloudflare 边缘
                            outcome.compareAndSet(null, TunnelStartResult.Started(formatTunnelUrl(domain)))
                            latch.countDown()
                        }
                        line = reader.readLine()
                    }
                    // 进程输出结束（已退出）—— 附最近日志行诊断
                    if (tunnelProcess === process) tunnelProcess = null
                    outcome.compareAndSet(null, TunnelStartResult.Failed("cloudflared 进程已退出\n${diagLines()}"))
                    latch.countDown()
                } catch (_: Exception) {
                    if (tunnelProcess === process) tunnelProcess = null
                    outcome.compareAndSet(null, TunnelStartResult.Failed("cloudflared 进程异常退出\n${diagLines()}"))
                    latch.countDown()
                }
            }.apply { isDaemon = true }.start()

            // 最多等 20 秒（cloudflared 建立首条连接通常 1-5 秒，网络慢时更久）
            latch.await(20, TimeUnit.SECONDS)
            outcome.get() ?: run {
                // 超时未连上 —— 20 秒未建立连接大概率有问题，不再乐观返回 Started
                val msg = if (process.isAlive)
                    "20 秒内未建立到 Cloudflare 的连接。请检查：1) token 是否有效 2) 防火墙是否放行 UDP 7844（QUIC 协议）\n${diagLines()}"
                else
                    "cloudflared 连接超时\n${diagLines()}"
                TunnelStartResult.Failed(msg)
            }
        }.getOrElse { TunnelStartResult.Failed(it.message ?: "隧道启动失败") }
    }

    /** 停止隧道：destroy（pty 下等效于终端里 Ctrl-C / 关闭，cloudflared 收 SIGHUP 退出）。 */
    override fun stopTunnel() {
        tunnelProcess?.destroy()
        tunnelProcess = null
    }

    /** 用户填的域名拼成 https:// URL；为空返回 null。 */
    private fun formatTunnelUrl(domain: String?): String? {
        if (domain.isNullOrBlank()) return null
        val cleaned = domain.trim().removePrefix("https://").removePrefix("http://")
        return "https://$cleaned"
    }
}
