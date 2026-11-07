package xyz.mederi.tools.sandbox

import xyz.mederi.api.exception.MederiStateException

/**
 * 确定性的 argv 前缀规则，不运行 shell、不用模型分类。
 * 只为单条静态命令自动放行；管道、重定向、替换、环境赋值等不能被前缀规则意外扩大授权。
 * 命令拒绝规则约束命令文本，不声称能识别所有实现相同效果的程序；路径黑名单是独立硬边界。
 */
internal object CommandRules {
    fun validate(patterns: List<String>) {
        patterns.forEach { require(arguments(it) != null) {
            "Command rules must be a non-empty static command prefix without shell operators or expansions: $it"
        } }
    }

    /** 返回是否命中允许规则；命中拒绝或无法安全检查已有拒绝规则时直接拒绝。 */
    fun allowsEscalation(command: String, settings: SandboxSettings): Boolean {
        val argv = arguments(command)
        if (argv == null) {
            if (settings.deniedCommands.isNotEmpty()) throw MederiStateException(
                "This project has command deny rules; complex shell syntax cannot be checked safely. Split it into individual static commands."
            )
            return false
        }
        val denied = settings.deniedCommands.any { pattern ->
            val prefix = arguments(pattern) ?: return@any false
            // 拒绝规则也覆盖同名命令的绝对路径形式；允许规则保持完整 executable 精确匹配。
            val executableMatches = argv.first() == prefix.first() ||
                java.io.File(argv.first()).name == prefix.first()
            executableMatches && argv.size >= prefix.size && argv.drop(1).take(prefix.size - 1) == prefix.drop(1)
        }
        if (denied) throw MederiStateException("Command rejected by this project's deny rules; no process was started.")
        return settings.allowedCommands.any { pattern ->
            val prefix = arguments(pattern) ?: return@any false
            argv.size >= prefix.size && argv.take(prefix.size) == prefix
        }
    }

    /** null 表示不能用一条静态 argv 安全解释；引号本身不执行任何代码。 */
    internal fun arguments(command: String): List<String>? {
        val args = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        for (character in command.trim()) {
            if (escaped) {
                if (character == '\n' || character == '\r') return null
                // POSIX 双引号内，反斜杠仅转义 $ ` " 和反斜杠；其它字符保留反斜杠。
                if (quote == '"' && character !in "$`\"\\") token.append('\\')
                token.append(character); escaped = false; started = true
                continue
            }
            if (quote == '\'') {
                if (character == '\'') quote = null else token.append(character)
                continue
            }
            if (character == '\\') { escaped = true; started = true; continue }
            if (quote == '"') {
                if (character == '"') quote = null
                else if (character == '$' || character == '`') return null
                else token.append(character)
                continue
            }
            when {
                character == '\'' || character == '"' -> { quote = character; started = true }
                character == '\n' || character == '\r' -> return null
                character.isWhitespace() -> if (started) {
                    args.add(token.toString()); token.clear(); started = false
                }
                character in ";|&<>(){}$`*?[]~#" -> return null
                else -> { token.append(character); started = true }
            }
        }
        if (escaped || quote != null) return null
        if (started) args.add(token.toString())
        if (args.isEmpty() || args.first().isEmpty() || '=' in args.first() || args.first() in shellKeywords) return null
        return args
    }

    private val shellKeywords = setOf("!", "time", "if", "then", "elif", "else", "fi", "do", "done",
        "for", "in", "while", "until", "case", "esac", "select", "function", "coproc")
}
