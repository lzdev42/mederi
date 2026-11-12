package xyz.mederi.config

/**
 * 产品名唯一真理源——软件改名时只改 [PRODUCT_NAME] 一处，其余目录名/标签/环境变量全部派生。
 *
 * 当前值保持 "mederi"：本常量是为可能的改名做准备（纯常量抽取，行为零变化）。
 *
 * 派生关系（全部保持与旧字面量逐字节一致）：
 * - 项目内工作目录名 = ".mederi"——plans / research / notebook / environments / mermaid /
 *   未来的 attachments、documents 等全部工作产物目录必须落在其下（布局不变量）
 * - 家目录配置根名 = "~/.mederi"（config.db / data.db / skills / mcp runtime / preferences）
 * - 配置目录环境变量 = MEDERI_CONFIG_DIR
 * - 环境信息标签前缀 = "Mederi"（EnvironmentInfoProvider 的 "Mederi workdir:"）
 * - 以产品名开头的标识（线程名 / clientName / 策略名 / 临时目录与 db 文件前缀 / JVM 属性 /
 *   进程 argv[0] / HTTP User-Agent / 日志与错误消息标签等）= PRODUCT_NAME 或 displayName + 后缀，
 *   由 core jvmTest 守卫测试扫描拦截"以产品名开头"的裸字面量。
 *
 * 注意：app/shared commonMain 无法依赖 JVM-only 的 core，镜像常量在 app/shared
 * （见 `xyz.mederi.util.MederiProductNames`），由 app/shared jvmTest 契约测试锁定与本体一致
 * （PromptComposer 镜像模式）。core 内新增代码禁止再写 ".mederi" 字面量——
 * core jvmTest 的源码扫描守卫测试会拦截。
 */
object MederiIdentity {
    /** 产品名（小写标识符，目录/环境变量/标记的派生源）。 */
    const val PRODUCT_NAME = "mederi"

    /** 展示名（首字母大写，AI/用户可见标签用）。 */
    val displayName: String get() = PRODUCT_NAME.replaceFirstChar { it.uppercase() }

    /** 项目内工作目录名（项目根下；全部工作产物的根）。 */
    val projectWorkdirName: String get() = ".$PRODUCT_NAME"

    /** 家目录配置根目录名（~ 下）。 */
    val homeConfigRootName: String get() = ".$PRODUCT_NAME"

    /** 配置目录环境变量名（对外契约）。 */
    val configDirEnvVar: String get() = PRODUCT_NAME.uppercase() + "_CONFIG_DIR"
}
