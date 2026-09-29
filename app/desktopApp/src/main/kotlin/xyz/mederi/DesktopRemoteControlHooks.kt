package xyz.mederi

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.server.Server
import xyz.mederi.ui.appstate.RemoteControlHooks
import xyz.mederi.ui.appstate.RemoteStartResult
import xyz.mederi.ui.appstate.TunnelStartResult
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * desktop 宿主实现：内嵌 server 启停 + Cloudflare 隧道。
 *
 * 隧道用 pty4j 以真实 pty 拉起 `cloudflared tunnel run`（读用户 ~/.cloudflared/config.yml）：
 * 本进程（含 kill -9 / 崩溃）退出时 OS 关闭 pty master，内核向 pty 前台进程组发 SIGHUP，
 * cloudflared 随之退出 —— 与"终端关了进程就死"同语义，无需看门狗轮询。
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

    /** 探测 cloudflared：PATH 上可执行且 --version 正常退出即视为已安装。 */
    override fun isCloudflaredInstalled(): Boolean = runCatching {
        val p = ProcessBuilder("cloudflared", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS)
        p.exitValue() == 0
    }.getOrDefault(false)

    /** 启动隧道。返回结果由调用方转成 UI 状态。 */
    override fun startTunnel(port: Int): TunnelStartResult {
        // 已在运行 → 幂等返回
        val running = tunnelProcess
        if (running != null && running.isAlive) {
            return TunnelStartResult.Started(parseTunnelDomain())
        }
        if (!isCloudflaredInstalled()) return TunnelStartResult.NotInstalled

        return runCatching {
            val process = PtyProcessBuilder()
                .setCommand(arrayOf("cloudflared", "tunnel", "run"))
                .setDirectory(homeDir)
                .setEnvironment(mapOf("HOME" to homeDir))
                .start()
            tunnelProcess = process
            // 进程意外退出时清引用（正常退出由 stopTunnel 走 destroy 路径）
            Thread {
                runCatching { process.waitFor() }
                if (tunnelProcess === process) tunnelProcess = null
            }.apply { isDaemon = true }.start()
            TunnelStartResult.Started(parseTunnelDomain())
        }.getOrElse { TunnelStartResult.Failed(it.message ?: "隧道启动失败") }
    }

    /** 停止隧道：destroy（pty 下等效于终端里 Ctrl-C / 关闭，cloudflared 收 SIGHUP 退出）。 */
    override fun stopTunnel() {
        tunnelProcess?.destroy()
        tunnelProcess = null
    }

    /**
     * 从 ~/.cloudflared/config.yml 解析隧道公网地址：取第一条带 hostname 的 ingress 规则，
     * 拼成 https://<hostname>。解析不到返回 null（UI 提示看 cloudflared 日志）。
     */
    private fun parseTunnelDomain(): String? {
        val config = File(homeDir, ".cloudflared/config.yml")
        if (!config.exists()) return null
        return runCatching {
            config.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("hostname:") }
                ?.substringAfter("hostname:")
                ?.trim()
                ?.removePrefix("\"")?.removeSuffix("\"")
                ?.let { host -> "https://$host" }
        }.getOrNull()
    }
}
