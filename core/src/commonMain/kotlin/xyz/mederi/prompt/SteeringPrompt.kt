package xyz.mederi.prompt

/**
 * 工具边界 steering（用户插话）注入文本的**唯一生成点**。
 *
 * 契约（跨模块）：剥离侧 = app/shared `xyz.mederi.util.PromptComposer`（commonMain，
 * 模块边界所限只能镜像常量），它必须能把本包装还原为用户原文——由 app/shared jvmTest 的
 * `TextProtocolContractTest` 锁定「生成 → 剥离 → 还原」闭环。
 * **改这里的文案（尤其 SYSTEM_NOTE 与标签）必须同步改剥离侧，否则 UI 会漏出系统提示。**
 *
 * 落库原则：`SteeringPrompt.wrap(text)` 只用于**给 AI 看的 prompt 消息**；
 * 持久化与 UI 展示一律用用户原文 [text]，不得被本包装污染。
 */
object SteeringPrompt {
    const val OPEN_TAG = "<user_intervention>"
    const val CLOSE_TAG = "</user_intervention>"
    const val SYSTEM_NOTE = "[System Note: The user submitted the following guidance while you were executing tools. Incorporate this guidance into your ongoing task without restarting from scratch]"

    /**
     * 把用户插话正文 [text] 包成给 AI 看的 steering 消息。
     * 输出格式与历史一致：`<user_intervention>` 一行、`[System Note: ...]:` 一行、正文一行、`</user_intervention>` 结尾。
     */
    fun wrap(text: String): String =
        buildString {
            append(OPEN_TAG)
            append('\n')
            append(SYSTEM_NOTE)
            append(':')
            append('\n')
            append(text)
            append('\n')
            append(CLOSE_TAG)
        }
}
