package xyz.mederi.core.contract

import xyz.mederi.core.bridge.MederiAiCore
import xyz.mederi.config.MederiIdentity

internal actual fun createDefaultAiCore(): AiCore = MederiAiCore(configDir = "~/" + MederiIdentity.homeConfigRootName)
