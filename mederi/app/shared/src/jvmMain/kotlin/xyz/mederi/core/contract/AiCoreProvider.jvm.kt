package xyz.mederi.core.contract

import xyz.mederi.core.bridge.MederiAiCore

actual object AiCoreProvider {
    actual fun default(): AiCore = MederiAiCore(configDir = "~/.mederi")
}
