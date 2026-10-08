package xyz.mederi.core.contract

import xyz.mederi.core.bridge.MederiAiCore

internal actual fun createDefaultAiCore(): AiCore = MederiAiCore(configDir = "~/.mederi")
