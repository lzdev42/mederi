package xyz.mederi.core.contract

import kotlinx.browser.localStorage
import kotlinx.browser.window
import xyz.mederi.core.bridge.ServerAiCore

actual object AiCoreProvider {
    /**
     * wasmJs 浏览器端：UI 由 server 同源托管（http://<host>:8081/），默认连自己所在的 origin。
     * 密码从 localStorage 读取（首次使用由密码门 UI 输入验证后写入），不依赖 URL 参数。
     * dev 场景（vite 跨端口）仍可用 ?server= 覆盖 baseUrl。
     */
    actual fun default(): AiCore = ServerAiCore(
        baseUrl = queryParam("server") ?: window.location.origin,
        passwordProvider = { localStorage.getItem("mederi.remote.password") }
    )

    /** URL 查询参数（?server=），未传返回 null */
    private fun queryParam(name: String): String? {
        val query = window.location.search.removePrefix("?")
        return query.split("&")
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("$name=")
            ?.takeIf { it.isNotBlank() }
    }
}
