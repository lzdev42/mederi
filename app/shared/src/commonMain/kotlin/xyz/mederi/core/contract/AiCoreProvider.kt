package xyz.mederi.core.contract

/**
 * 平台相关的默认 [AiCore] 提供者。
 *
 * - jvmMain 返回基于真实 mederi core 的 [xyz.mederi.core.bridge.MederiAiCore]。
 * - android / ios / wasmJs 暂返回 [xyz.mederi.core.mock.MockAiCore] 作为占位，
 *   等平台 actual 接入真实 core 后再替换。
 */
expect object AiCoreProvider {
    fun default(): AiCore
}
