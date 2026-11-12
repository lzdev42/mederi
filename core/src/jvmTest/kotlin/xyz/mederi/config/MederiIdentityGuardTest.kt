package xyz.mederi.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫测试：core 生产源码（commonMain + jvmMain）禁止散落产品名派生字面量。
 *
 * 产品名唯一真理源 = [MederiIdentity]（`PRODUCT_NAME` / `displayName`），改名时只改常量一处；
 * 所有以产品名开头的派生值（目录/文件、线程名、clientName、策略名、临时目录前缀、JVM 属性、
 * User-Agent、日志与消息标签、进程 argv[0] 等）必须引用该常量拼接，杜绝散落字面量悄悄漂移。
 *
 * 判定规则 = 字符串字面量引号后紧跟产品名开头——即行文本出现 `".mederi` / `"~/.mederi`
 * （工作目录/家目录点前缀）或 `"mederi` / `"Mederi`（其余产品名标识，区分大小写）。
 * 注释里的描述文本不受影响，除非其中恰好出现带引号的上述字面量形式。
 *
 * 规则与豁免：
 * - 只扫 main sources（commonMain + jvmMain），不扫测试源码——测试里硬编码磁盘路径/标识是
 *   行为断言（真实落盘/线上值必须与产品名逐字节一致），保留合理。
 * - [MederiIdentity.kt] 自身豁免（其 KDoc 描述派生关系时必然提及字面量本身）。
 * - 显式豁免清单 [exemptedOccurrences]：非产品身份、但与类名/组件声明同名的自引用字面量
 *   （调试标签、同名 clientName、插件名等），随代码标识符改名，不需要 PRODUCT_NAME 派生；
 *   条目不命中（行号漂移/字面量已删除）时测试同样失败，强制同步清单。
 */
class MederiIdentityGuardTest {

    /**
     * 显式豁免：key = "relDir/relFile:行号"（相对各自扫描根），value = 理由。
     * 仅限"非产品身份、但与类名/组件声明同名"的自引用字面量；新增此类豁免必须写明理由。
     */
    private val exemptedOccurrences: Map<String, String> = linkedMapOf(
        "commonMain/kotlin/xyz/mederi/provider/infrastructure/koog/sanitize/MederiOpenAILLMClient.kt:87" to
            "clientName 与类名同名（MederiOpenAILLMClient）的组件自引用，随代码标识符改名，非产品命名空间派生",
        "commonMain/kotlin/xyz/mederi/provider/infrastructure/koog/sanitize/MederiOpenAILLMClient.kt:136" to
            "调试标签引用类名 MederiOpenAILLMClient（与代码标识符一致），非产品身份派生",
        "commonMain/kotlin/xyz/mederi/provider/infrastructure/koog/sanitize/MederiOpenAILLMClient.kt:207" to
            "调试标签引用类名 MederiOpenAILLMClient（与代码标识符一致），非产品身份派生",
        "commonMain/kotlin/xyz/mederi/http/MederiHttpClientFactory.kt:71" to
            "Ktor 插件名与声明标识 ProviderFailureMetadata 同名（含类名前缀），随代码标识符改名，非产品身份派生",
    )

    /** 扫描前缀：引号后紧跟产品名（含点前缀与区分大小写的展示名）。 */
    private val productNamePrefixes = listOf("\".mederi", "\"~/.mederi", "\"mederi", "\"Mederi")

    @Test
    fun productionSourcesMustNotHardcodeProductNamePrefixLiterals() {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "gradlew").exists() }
        val violations = mutableListOf<String>()
        val matchedExemptions = mutableSetOf<String>()
        scanSourceDir(repo, "core/src/commonMain", violations, matchedExemptions)
        scanSourceDir(repo, "core/src/jvmMain", violations, matchedExemptions)
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("core 生产源码存在硬编码产品名前缀字符串字面量（命中文件:行与原文如下）。")
                appendLine("产品名唯一真理源是 xyz.mederi.config.MederiIdentity——请改为引用常量派生（值逐字节不变）：")
                appendLine("  MederiIdentity.PRODUCT_NAME          小写产品名（拼接 \"-worktrees\" / \"-patch-\" / \"_single_run\" 等后缀标识）")
                appendLine("  MederiIdentity.displayName           展示名（\"Mederi\"：User-Agent / 日志标签 / 用户可见消息）")
                appendLine("  MederiIdentity.projectWorkdirName    项目内工作目录名（\".mederi\"）")
                appendLine("  MederiIdentity.homeConfigRootName    家目录配置根名（\"~/.mederi\"）")
                appendLine("  MederiIdentity.configDirEnvVar       配置目录环境变量（MEDERI_CONFIG_DIR）")
                appendLine("非产品身份、但与类名同名的自引用字面量可加入 exemptedOccurrences 显式豁免（须写明理由）。")
                appendLine()
                violations.forEach { appendLine("  $it") }
            }
        )
        val stale = exemptedOccurrences.keys - matchedExemptions
        assertTrue(
            stale.isEmpty(),
            "守卫测试豁免清单存在失效条目（行号漂移或字面量已不存在），请同步 exemptedOccurrences：\n" +
                stale.joinToString("\n") { "  $it" }
        )
    }

    private fun scanSourceDir(
        repo: File,
        relDir: String,
        violations: MutableList<String>,
        matchedExemptions: MutableSet<String>,
    ) {
        val root = File(repo, relDir)
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filterNot { it.name == "MederiIdentity.kt" }
            .forEach { file ->
                val rel = file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    if (productNamePrefixes.any { line.contains(it) }) {
                        val key = "${relDir.removePrefix("core/src/")}/$rel:${index + 1}"
                        val reason = exemptedOccurrences[key]
                        if (reason == null) {
                            violations += "$key: $line"
                        } else {
                            matchedExemptions += key
                        }
                    }
                }
            }
    }
}