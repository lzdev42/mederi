package xyz.mederi.util

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mederi.config.MederiIdentity

/**
 * 产品名镜像**契约锁**（app/shared commonMain ↔ core）。
 *
 * app/shared commonMain 不能依赖 JVM-only 的 core，故 `MederiProductNames` 镜像 core
 * `MederiIdentity`（PromptComposer 镜像 SteeringPrompt 的同一模式，jvmTest 同时可见
 * commonMain 与 core）。本测试把两侧每个值逐字节一致固化为机器校验：core / 镜像任一侧
 * 改产品名派生值而另一侧没跟上时，测试必须失败（而不是改名时目录/环境变量静默错位）。
 */
class MederiProductNamesContractTest {

    @Test
    fun productNameMirrorsCore() {
        assertEquals(MederiIdentity.PRODUCT_NAME, MederiProductNames.PRODUCT_NAME)
    }

    @Test
    fun displayNameMirrorsCore() {
        assertEquals(MederiIdentity.displayName, MederiProductNames.displayName)
    }

    @Test
    fun projectWorkdirNameMirrorsCore() {
        assertEquals(MederiIdentity.projectWorkdirName, MederiProductNames.projectWorkdirName)
    }

    @Test
    fun homeConfigRootNameMirrorsCore() {
        assertEquals(MederiIdentity.homeConfigRootName, MederiProductNames.homeConfigRootName)
    }

    @Test
    fun configDirEnvVarMirrorsCore() {
        assertEquals(MederiIdentity.configDirEnvVar, MederiProductNames.configDirEnvVar)
    }
}
