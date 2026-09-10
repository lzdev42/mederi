package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.component.LatexParserContext
import xyz.emuci.latex.parser.component.LatexTokenStream
import xyz.emuci.latex.parser.model.LatexNode

internal fun interface CommandHandler {
    /**
     * 命令处理器函数式接口
     *
     * 每个 handler 负责解析一类 LaTeX 命令。
     * [cmdName] 是触发命令名（不含反斜杠），[ctx] 提供解析上下文，[stream] 提供 token 流。
     */
    fun parse(cmdName: String, ctx: LatexParserContext, stream: LatexTokenStream): LatexNode?
}

/**
 * 命令注册表：将 LaTeX 命令名映射到对应的 [CommandHandler]。
 *
 * 使用方式：
 * ```kotlin
 * val registry = CommandRegistry()
 * registry.installFractionHandlers()
 * registry.installRootHandlers()
 * // ...
 *
 * // 在 parseCommand() 中：
 * val node = registry.dispatch(cmdName, ctx, stream)
 * ```
 *
 * 通过 [installModule] 支持按领域批量注册：
 * ```kotlin
 * registry.installModule { installFractionHandlers() }
 * ```
 */
internal class CommandRegistry {
    private val handlers = LinkedHashMap<String, CommandHandler>()

    /**
     * 注册一个 handler 到一个或多个命令名。
     * 后注册的同名命令会覆盖先前的注册。
     */
    fun register(vararg names: String, handler: CommandHandler) {
        for (name in names) {
            handlers[name] = handler
        }
    }

    /**
     * 尝试分发命令。如果命令已注册，调用对应 handler 并返回结果；
     * 如果未注册，返回 null（调用方可回退到默认处理）。
     */
    fun dispatch(cmdName: String, ctx: LatexParserContext, stream: LatexTokenStream): LatexNode? {
        val handler = handlers[cmdName] ?: return null
        return handler.parse(cmdName, ctx, stream)
    }

    /**
     * 安装一个模块（批量注册一组 handler）
     */
    fun installModule(registrar: CommandRegistry.() -> Unit) {
        registrar()
    }
}
