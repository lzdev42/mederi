package xyz.mederi.infrastructure.koog

import xyz.mederi.domain.model.MessageRole
import xyz.mederi.store.HistoryStore
import xyz.mederi.tools.contextUsedTokens
import xyz.mederi.tools.estimateTokens

/**
 * 当前上下文占用（token）——**压缩判定与 UI 上下文显示共用的唯一真理源**。
 *
 * 任何一方都不得另写一份计算逻辑：一旦两处口径分叉，就会出现
 * 「界面显示还有 30% 余量、引擎却已经压了」这类互相矛盾的判定。
 *
 * ## 为什么分两条分支
 *
 * 取的是 AI 视图窗口（[HistoryStoreChatHistoryProvider.aiViewWindow]，即最后一条 SUMMARY
 * 压缩标记及其之后的消息），窗口首条是 SUMMARY 时说明**上下文已经被压缩过**：
 * 此时窗口内 Assistant 消息携带的 `inputTokens` 反映的是**压缩前**的大上下文，
 * 拿它当基线会严重高估占用（压缩已经做完了，判定却仍以为爆窗），
 * 因此改用加权估算（[estimateTokens]）按窗口实际内容重新算。
 *
 * 没有 SUMMARY 标记时，窗口内最近一条 Assistant 的 `inputTokens` 就是 API 报告的
 * 本次请求真实 prompt 大小，直接用它（[contextUsedTokens]），比估算准。
 *
 * @param includeImages 是否保留用户消息中的图片——与实际发送口径保持一致
 *        （AI 视图按当前模型能力剔除图片），否则图片的固定 2000 token 估算会与实发不符。
 * @return 窗口为空时返回 0；否则返回上面的口径值。
 */
suspend fun aiViewContextUsedTokens(
    historyStore: HistoryStore,
    sessionId: String,
    includeImages: Boolean
): Int {
    val window = HistoryStoreChatHistoryProvider.aiViewWindow(historyStore, sessionId)
    if (window.isEmpty()) return 0
    val koogWindow = KoogMessageMapper.toKoogMessages(window, includeImages = includeImages)
    return if (window.first().role == MessageRole.SUMMARY) estimateTokens(koogWindow)
    else contextUsedTokens(koogWindow)
}