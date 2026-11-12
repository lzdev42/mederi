package xyz.mederi.util

/**
 * app/shared 侧的产品名镜像常量（镜像 core `xyz.mederi.config.MederiIdentity`）。
 *
 * commonMain 是多平台源集，无法依赖 JVM-only 的 core 模块，故在 util 下镜像一份
 * （PromptComposer 镜像 core SteeringPrompt 的同一模式）。派生逻辑与 core 完全一致，
 * 当前值保持 "mederi"（纯常量抽取，行为零变化）。
 *
 * 改动必须与 core MederiIdentity 同步——两侧一致性由 app/shared jvmTest 的
 * MederiProductNamesContractTest 契约测试逐字节锁定。
 */
object MederiProductNames {

    /** 产品名（小写标识符，目录/环境变量/标记的派生源）。镜像 MederiIdentity.PRODUCT_NAME。 */
    const val PRODUCT_NAME = "mederi"

    /** 展示名（首字母大写，AI/用户可见标签用）。镜像 MederiIdentity.displayName。 */
    val displayName: String get() = PRODUCT_NAME.replaceFirstChar { it.uppercase() }

    /** 项目内工作目录名（项目根下；全部工作产物的根）。镜像 MederiIdentity.projectWorkdirName。 */
    val projectWorkdirName: String get() = ".$PRODUCT_NAME"

    /** 家目录配置根目录名（~ 下）。镜像 MederiIdentity.homeConfigRootName。 */
    val homeConfigRootName: String get() = ".$PRODUCT_NAME"

    /** 配置目录环境变量名（对外契约）。镜像 MederiIdentity.configDirEnvVar。 */
    val configDirEnvVar: String get() = PRODUCT_NAME.uppercase() + "_CONFIG_DIR"
}
