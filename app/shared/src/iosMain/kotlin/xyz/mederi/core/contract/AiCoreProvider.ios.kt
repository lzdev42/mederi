package xyz.mederi.core.contract

import xyz.mederi.core.bridge.ServerAiCore

/**
 * iOS：100% 遥控端。core 跑在远端 JVM server 进程里，UI 经 REST + SSE 遥控。
 *
 * 连接配置 UI 接入后改为从持久化偏好读（defaultPreferencesStore 已落 Documents/preferences.json）；
 * 先默认本机 8081，适配模拟器本地调试。
 */
internal actual fun createDefaultAiCore(): AiCore = ServerAiCore(
    baseUrl = "http://127.0.0.1:8081",
    passwordProvider = { null }
)
