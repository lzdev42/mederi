package xyz.mederi.tools.sandbox

/**
 * 沙箱全局配置（进程级，JVM-only — core 当前仅 jvm 目标，无 KMP 替代品）。
 *
 * 沙箱永远开、无开关，此处只有"项目外额外可写路径"（设置页全局白名单）。
 * UI 改后同步写入此对象；CommandSandbox 每 turn 构造时读取，下个 turn 即生效。
 */
object SandboxConfig {
    @Volatile
    var extraWritablePaths: List<String> = emptyList()
}