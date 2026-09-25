package xyz.mederi.browser.drill

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.browser.BrainResult
import xyz.mederi.browser.BrowserControl
import xyz.mederi.browser.BrowserLocator
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.browser.drill.models.*
import xyz.mederi.debug.DebugLog
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 确定性自动化脚本（Drill）执行器。
 *
 * 由 BrowserPilot 的 DrillExecutor port 而来，但只依赖 [BrowserControl] 抽象——
 * 不接触 BiDiPage / BrowserSession / owner（标签页由 control 内部管理，这里通过
 * openTab / listTabs / closeTab / selectTab 操作）。
 *
 * 两阶段执行模型：
 * - `item_steps == null` → legacy loop：loop_count 轮循环执行 steps，record_result 步骤抓结果；
 * - `item_steps != null` → 页面级 steps 先 extract_list 提取数据项，再逐条对每个 item 执行 item_steps。
 *
 * 支持 action：navigate / click / fill / hover / press / scroll / extract_list /
 * infinite_scroll / fetch_content / ask_ai（经 [onJudgeContent] 回调）/ abort_item /
 * branch / write_file / append_file / open_tab / click_new_tab / close_tab /
 * wait_for / wait / type_slowly。罕见动作 extract_chat_history / upload_file 为 stub。
 *
 * @param control   浏览器控制抽象（唯一与浏览器交互的入口）
 * @param workingDir 输出目录；write_file / append_file 的 file_path 相对它解析，
 *                   null 时跳过文件写入并记 log
 */
class DrillExecutor(
    private val control: BrowserControl,
    private val workingDir: File? = null
) {
    companion object {
        private const val MAX_CONSECUTIVE_ITEM_FAILURES = 5
    }

    private val jsonHelper = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * 执行 Drill 脚本。
     *
     * @param script 脚本入参：JsonPrimitive（字符串）→ 当作 drillScript JSON 字符串解析；
     *               JsonObject → 直接反序列化为 [DrillScript]。mederi 不在这一层查 skill 文件系统
     *               ——调用方（Operator）已解析好传入，或直接传 inline JSON。
     * @param onJudgeContent ask_ai 回调解析器：接收 (content, instruction) 返回 [BrainResult]，
     *                       为 null 时脚本若触发 ask_ai 则抛 IllegalArgumentException。
     */
    suspend fun executeScript(
        script: JsonElement,
        onJudgeContent: (suspend (content: String, instruction: String) -> BrainResult)? = null
    ): DrillResult {
        val finalScript = try {
            when (script) {
                is JsonPrimitive -> {
                    if (script.isString) {
                        // 字符串内容本身即 DrillScript JSON（调用方已解析好，或内联 JSON）
                        jsonHelper.decodeFromString(DrillScript.serializer(), script.content)
                    } else {
                        throw IllegalArgumentException("无效的 Drill 脚本参数类型: Primitive $script")
                    }
                }
                is JsonObject -> {
                    jsonHelper.decodeFromJsonElement(DrillScript.serializer(), script)
                }
                else -> {
                    throw IllegalArgumentException("无效的 Drill 脚本参数格式")
                }
            }
        } catch (e: Exception) {
            DebugLog.error("drill", "Drill 脚本反序列化失败: ${e.message}")
            return DrillResult(
                task_name = "未命名任务",
                success = false,
                error_message = "反序列化失败: ${e.message}"
            )
        }

        val drillResults = mutableListOf<Map<String, String>>()
        val extractedItems = mutableListOf<Map<String, String>>()

        var round = 1
        return try {
            while (true) {
                val startTime = System.currentTimeMillis()
                if (finalScript.item_steps != null) {
                    runPageLevelWorkflow(
                        script = finalScript,
                        onJudgeContent = onJudgeContent,
                        drillResults = drillResults,
                        extractedItems = extractedItems
                    )
                } else {
                    runLegacyLoopWorkflow(
                        script = finalScript,
                        drillResults = drillResults
                    )
                }
                val duration = System.currentTimeMillis() - startTime

                if (finalScript.repeat_interval_ms > 0) {
                    delay(finalScript.repeat_interval_ms.milliseconds)
                    round++
                } else {
                    break
                }
            }

            DebugLog.info("drill", "Drill SUCCESS: task='${finalScript.task_name}', rounds=$round, drillResults=${drillResults.size}")
            DrillResult(task_name = finalScript.task_name, success = true, drill_results = drillResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e

            val errorType = when (e) {
                is ConsecutiveItemFailureException -> "CONSECUTIVE_FAILURE"
                else -> null
            }
            DrillResult(
                task_name = finalScript.task_name,
                success = false,
                drill_results = drillResults,
                error_message = e.message,
                error_type = errorType
            )
        }
    }

    // ── 两阶段：页面级步骤 + 条目级步骤 ──

    private suspend fun runPageLevelWorkflow(
        script: DrillScript,
        onJudgeContent: (suspend (content: String, instruction: String) -> BrainResult)?,
        drillResults: MutableList<Map<String, String>>,
        extractedItems: MutableList<Map<String, String>>
    ) {
        extractedItems.clear()
        val totalSteps = script.steps.size
        script.steps.forEachIndexed { stepIndex, step ->
            yield()
            DebugLog.info("drill", "执行页面级步骤 [${stepIndex + 1}/$totalSteps]: ${step.step_name} (动作: ${step.action})")
            val stepStart = System.currentTimeMillis()
            try {
                when (step.action) {
                    "extract_list" -> executeExtractList(step, extractedItems)
                    "infinite_scroll" -> executeInfiniteScroll(step)
                    "wait_for" -> handleWaitFor(step)
                    "wait" -> handleWait(step)
                    else -> {
                        val locator = if (step.selector_value != null) resolveDrillLocator(step) else null
                        executeAction(locator, step)
                    }
                }
                val stepDuration = System.currentTimeMillis() - stepStart
                DebugLog.info("drill", "Drill step [${stepIndex + 1}/$totalSteps] '${step.step_name}' done in ${stepDuration}ms")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DebugLog.error("drill", "页面级步骤 [${stepIndex + 1}/$totalSteps] '${step.step_name}' 执行失败: ${e::class.simpleName}: ${e.message}")
                throw e
            }
        }

        val itemSteps = script.item_steps ?: return
        if (extractedItems.isEmpty()) {
            throw Exception("提取列表数据为空，没有找到任何职位卡片元素，任务无法继续。")
        } else {
            var consecutiveFailures = 0
            var successCount = 0

            extractedItems.forEachIndexed { itemIndex, item ->
                yield()
                val itemStart = System.currentTimeMillis()

                val itemContext = item.toMutableMap()
                itemContext["_item_index"] = (itemIndex + 1).toString()
                try {
                    itemSteps.forEachIndexed { stepIndex, step ->
                        yield()
                        DebugLog.info("drill", "执行条目子步骤 [${stepIndex + 1}/${itemSteps.size}]: ${step.step_name} (动作: ${step.action})")
                        val stepStart = System.currentTimeMillis()
                        executeItemStep(step, itemContext, onJudgeContent, script.image_assets)
                        val stepDuration = System.currentTimeMillis() - stepStart
                    }
                    drillResults.add(itemContext)
                    consecutiveFailures = 0
                    successCount++
                    val itemDuration = System.currentTimeMillis() - itemStart
                    DebugLog.info("drill", "Item ${itemIndex + 1}/${extractedItems.size} done in ${itemDuration}ms, success=$successCount")
                } catch (e: AbortItemException) {
                    val itemDuration = System.currentTimeMillis() - itemStart
                    DebugLog.info("drill", "Item ${itemIndex + 1}/${extractedItems.size} aborted (${itemDuration}ms): ${e.message}")
                    if (e.closingTab) {
                        closeItemTab(itemContext)
                        delay(300.milliseconds)
                    }
                    consecutiveFailures = 0 // 主动跳过（判定不符合）不属于脚本错误，反而证明流程通畅，重置错误计数
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val itemDuration = System.currentTimeMillis() - itemStart
                    DebugLog.error("drill", "Item ${itemIndex + 1}/${extractedItems.size} failed (${itemDuration}ms): ${e::class.simpleName}: ${e.message}")
                    closeItemTab(itemContext)
                    consecutiveFailures++
                    delay(300.milliseconds)
                }

                if (consecutiveFailures >= MAX_CONSECUTIVE_ITEM_FAILURES) {
                    val detail = "连续 $consecutiveFailures 次条目迭代失败（总条目 ${extractedItems.size}，已成功 $successCount），判定为脚本失效，中止执行。"
                    DebugLog.error("drill", detail)
                    throw ConsecutiveItemFailureException(
                        consecutiveCount = consecutiveFailures,
                        totalItems = extractedItems.size,
                        successCount = successCount,
                        message = detail
                    )
                }
            }
        }
    }

    // ── legacy 循环：loop_count 轮循环执行 steps ──

    private suspend fun runLegacyLoopWorkflow(
        script: DrillScript,
        drillResults: MutableList<Map<String, String>>
    ) {
        repeat(script.loop_count) { loopIndex ->
            val loopStart = System.currentTimeMillis()
            var anyStepFound = false
            val currentLoopData = mutableMapOf<String, String>()
            val totalSteps = script.steps.size
            script.steps.forEachIndexed { stepIndex, step ->
                yield()
                DebugLog.info("drill", "执行步骤 [${stepIndex + 1}/$totalSteps]: ${step.step_name} (playwright_api: ${step.playwright_api ?: "locator"})")
                val stepStart = System.currentTimeMillis()

                try {
                    var finalLocator = resolveDrillLocator(step)
                    val matchCount = finalLocator.count()

                    if (matchCount > 1) {
                        finalLocator = finalLocator.nth(loopIndex % matchCount)
                    } else if (matchCount == 0) {
                        DebugLog.info("drill", "未找到匹配元素，跳过步骤: ${step.step_name} (${step.selector_value})")
                        return@forEachIndexed
                    }

                    executeAction(finalLocator, step)

                    if (step.record_result) {
                        val result = captureResult(finalLocator, step)
                        currentLoopData[step.step_name] = result
                        if (result.isNotBlank()) {
                            anyStepFound = true
                        }
                    } else {
                        anyStepFound = true
                    }
                    val stepDuration = System.currentTimeMillis() - stepStart
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DebugLog.error("drill", "步骤 '${step.step_name}' 执行异常: ${e::class.simpleName}: ${e.message}")
                    throw e
                }
            }

            val loopDuration = System.currentTimeMillis() - loopStart
            if (!anyStepFound) {
                throw Exception("本轮循环所有步骤均失效（选择器不存在或抓取数据为空），任务自动终止。")
            }

            if (currentLoopData.isNotEmpty()) {
                drillResults.add(currentLoopData)
            }
        }
    }

    // ── 条目级步骤分发 ──

    private suspend fun executeItemStep(
        step: DrillStep,
        itemContext: MutableMap<String, String>,
        onJudgeContent: (suspend (content: String, instruction: String) -> BrainResult)? = null,
        imageAssets: Map<String, ImageAsset>? = null
    ) {
        // 对 selector_value 和 action_value 做模板变量替换
        val resolvedStep = resolveStepTemplates(step, itemContext)

        when (resolvedStep.action) {
            "open_tab" -> handleOpenTab(itemContext)
            "click_new_tab" -> handleClickNewTab(resolvedStep, itemContext)
            "close_tab" -> closeItemTab(itemContext)
            else -> {
                // 有 _tabId 时切到该标签页（control 内部管 tab）
                val tabId = itemContext["_tabId"]
                if (tabId != null) {
                    control.selectTab(tabId)
                }

                when (resolvedStep.action) {
                    "navigate" -> handleNavigate(resolvedStep, itemContext)
                    "wait_for" -> handleWaitFor(resolvedStep)
                    "wait" -> handleWait(resolvedStep)
                    "fetch_content" -> handleFetchContent(resolvedStep, itemContext)
                    "ask_ai" -> handleAskAI(resolvedStep, itemContext, onJudgeContent, imageAssets)
                    "extract_chat_history" -> handleExtractChatHistory(resolvedStep, itemContext)
                    "abort_item" -> handleAbortItem(resolvedStep, itemContext)
                    "branch" -> handleBranch(resolvedStep, itemContext, onJudgeContent, imageAssets)
                    "type_slowly" -> handleTypeSlowly(resolvedStep, itemContext)
                    "upload_file" -> handleUploadFile(resolvedStep, itemContext)
                    "write_file" -> handleWriteFile(resolvedStep, itemContext)
                    "append_file" -> handleAppendFile(resolvedStep, itemContext)
                    else -> {
                        val locator = if (resolvedStep.selector_value != null) resolveDrillLocator(resolvedStep) else null
                        executeAction(locator, resolvedStep)
                    }
                }
            }
        }
    }

    /**
     * 对 DrillStep 中的模板变量字段做 resolveTemplate 替换。
     * 目前替换 selector_value 和 action_value，使 JSON 脚本中可使用 {key} 引用 itemContext 数据。
     */
    private fun resolveStepTemplates(step: DrillStep, ctx: Map<String, String>): DrillStep {
        val resolvedSelector = step.selector_value?.let { resolveTemplate(it, ctx) }
        val resolvedActionValue = step.action_value?.let { resolveTemplate(it, ctx) }
        // 只有实际发生了替换才创建副本，避免无谓的对象分配
        return if (resolvedSelector != step.selector_value || resolvedActionValue != step.action_value) {
            step.copy(selector_value = resolvedSelector, action_value = resolvedActionValue)
        } else {
            step
        }
    }

    // ── 标签页处理（control 内部管 tab，无 owner 概念）──

    private suspend fun handleOpenTab(itemContext: MutableMap<String, String>) {
        val result = control.openTab()
        val newTabId = (result as? OperationResult.Success)?.detail?.takeIf { it.isNotBlank() }
            ?: control.listTabs().lastOrNull()
            ?: throw Exception("open_tab: 未找到新 Tab")
        itemContext["_tabId"] = newTabId
        DebugLog.info("drill", "open_tab 成功, 新 Tab ID: $newTabId")
    }

    private suspend fun handleClickNewTab(step: DrillStep, itemContext: MutableMap<String, String>) {
        val selector = step.selector_value ?: throw IllegalArgumentException("click_new_tab 需要 selector_value")

        val previousTabs = control.listTabs().toSet()
        control.locator(selector).click()
        delay(2000)

        val currentTabs = control.listTabs()
        val newTabId = currentTabs.firstOrNull { it !in previousTabs }
            ?: throw Exception("click_new_tab: 未检测到新诞生的 TabID")

        itemContext["_tabId"] = newTabId
        DebugLog.info("drill", "click_new_tab: 新 Tab 捕获成功，ID: $newTabId")
    }

    private suspend fun closeItemTab(itemContext: MutableMap<String, String>) {
        val tabId = itemContext["_tabId"] ?: run {
            return
        }
        try {
            control.closeTab(tabId)
            DebugLog.info("drill", "close_tab 成功: 关闭了 Tab ID=$tabId")
        } catch (e: Exception) {
            DebugLog.info("drill", "close_tab 失败: ${e.message}")
        }
        itemContext.remove("_tabId")
    }

    // ── 基础动作 handler ──

    private suspend fun handleNavigate(step: DrillStep, itemContext: Map<String, String>) {
        val finalUrl = when {
            step.url != null -> {
                step.url
            }
            step.url_from != null -> {
                val value = itemContext[step.url_from] ?: ""
                val resolved = (step.url_prefix ?: "") + value
                resolved
            }
            else -> throw IllegalArgumentException("navigate: 必须提供 url 或 url_from")
        }
        control.navigate(finalUrl)
        DebugLog.info("drill", "导航完成: $finalUrl")
    }

    private suspend fun handleWaitFor(step: DrillStep) {
        if (step.selector_value != null) {
            val start = System.currentTimeMillis()
            control.waitFor(step.selector_value)
            DebugLog.info("drill", "wait_for: 选择器 '${step.selector_value}' 已出现, 耗时: ${System.currentTimeMillis() - start}ms")
        } else {
            val waitMs = step.selector_options?.get("wait_ms")?.toLongOrNull() ?: 1000L
            delay(waitMs.milliseconds)
        }
    }

    private suspend fun handleWait(step: DrillStep) {
        val waitMs = step.selector_options?.get("wait_ms")?.toLongOrNull() ?: 1000L
        delay(waitMs.milliseconds)
    }

    private suspend fun handleFetchContent(step: DrillStep, itemContext: MutableMap<String, String>) {
        val selector = step.selector_value ?: throw IllegalArgumentException("fetch_content 需要 selector_value")
        val fallbacks = step.selector_fallbacks ?: emptyList()
        val selectors = listOf(selector) + fallbacks
        var content: String? = null
        for ((idx, sel) in selectors.withIndex()) {
            try {
                val loc = control.locator(sel)
                val isVis = loc.isVisible()
                if (isVis) {
                    content = loc.getText()
                    if (!content.isNullOrBlank()) {
                        break
                    } else {
                        DebugLog.info("drill", "fetch_content: 选择器 [$sel] 提取到的内容为空白字符")
                    }
                }
            } catch (e: Exception) {
                DebugLog.info("drill", "fetch_content 选择器 [$sel] 等待或读取失败: ${e.message}")
            }
        }
        if (content.isNullOrBlank()) {
            DebugLog.error("drill", "fetch_content: 所有候选选择器内容均为空，判定需要终止当前条目")
            throw AbortItemException("fetch_content 所有候选选择器内容均为空", closingTab = true)
        }
        val storeKey = step.store_as ?: "content"
        itemContext[storeKey] = content
        val preview = if (content.length > 100) content.take(100) + "..." else content
        DebugLog.info("drill", "fetch_content 成功: 内容存入 '$storeKey', 预览: \"${preview.replace("\n", " ")}\"")
    }

    private suspend fun handleAskAI(
        step: DrillStep,
        itemContext: MutableMap<String, String>,
        onJudgeContent: (suspend (content: String, instruction: String) -> BrainResult)?,
        imageAssets: Map<String, ImageAsset>? = null
    ) {
        if (onJudgeContent == null) {
            throw IllegalArgumentException("ask_ai requires onJudgeContent callback")
        }
        val contentFromKeys = step.content_from ?: listOf("content")
        val content = contentFromKeys.joinToString("\n\n") { key ->
            val value = itemContext[key] ?: ""
            if (contentFromKeys.size > 1 && value.isNotEmpty()) {
                "[$key]\n$value"
            } else {
                value
            }
        }
        if (content.isEmpty()) {
            DebugLog.error("drill", "ask_ai: 判定内容为空, 属性键: ${contentFromKeys.joinToString()}")
            throw Exception("ask_ai: 需判定的内容为空 (keys: ${contentFromKeys.joinToString()} 对应的值均为空)")
        }
        val rawInstruction = step.instruction ?: ""
        val resolvedInstruction = resolveTemplate(rawInstruction, itemContext)

        // 注入图片资源描述到指令末尾
        val finalInstruction = if (!imageAssets.isNullOrEmpty()) {
            val assetLines = imageAssets.entries.joinToString("\n") { (name, asset) ->
                "- $name: ${asset.desc}"
            }
            """$resolvedInstruction

可用图片资源（如需发图，返回 image 字段填写对应名称，否则留空字符串）：
$assetLines"""
        } else {
            resolvedInstruction
        }

        DebugLog.info("drill", "ask_ai: 发起 AI 判定, 数据键=${contentFromKeys.joinToString()}, 字数=${content.length}, 指令='${finalInstruction.take(80)}${if (finalInstruction.length > 80) "..." else ""}'")

        val start = System.currentTimeMillis()
        val response = onJudgeContent(content, finalInstruction)
        val duration = System.currentTimeMillis() - start

        DebugLog.info("drill", "ask_ai: AI 返回 (耗时 ${duration}ms)")
        val prefix = step.ai_result_key ?: "ai"

        // BrainResult.data 已经是展开好的 Map<String, String>
        if (response.data.isNotEmpty()) {
            val fieldSummary = response.data.entries.joinToString(", ") { (k, v) -> "$k=$v" }
            DebugLog.info("drill", "ask_ai result: $fieldSummary")

            response.data.forEach { (k, v) ->
                val storeKey = "$prefix.$k"
                itemContext[storeKey] = v
            }
        } else if (response.text.isNotBlank()) {
            // data 为空但 text 有值，存入 raw
            DebugLog.info("drill", "ask_ai result (text): ${response.text.take(120)}")
            itemContext["$prefix.raw"] = response.text
        } else {
            DebugLog.info("drill", "AI 返回为空")
            itemContext["$prefix.raw"] = ""
        }

        // 记录写入的文件（mederi BrainResult 字段为 camelCase filesWritten）
        if (response.filesWritten.isNotEmpty()) {
            DebugLog.info("drill", "ask_ai: 写入了 ${response.filesWritten.size} 个文件: ${response.filesWritten.joinToString()}")
        }
    }

    /**
     * 提取聊天记录（stub）。
     *
     * BrowserPilot 原版用单次 JS 从页面抓取消息并格式化；mederi 骨架阶段暂不实现，
     * 记 log 后跳过——罕见动作，脚本不应依赖它（依赖时 itemContext 中没有对应 key）。
     */
    private suspend fun handleExtractChatHistory(step: DrillStep, itemContext: MutableMap<String, String>) {
        DebugLog.info("drill", "extract_chat_history 未实现（stub），已跳过: ${step.step_name}")
    }

    /**
     * 通用条件评估方法，供 abort_item 和 branch 共用。
     * 支持两种条件语法：
     * - "exists:selector" → 检测页面元素是否存在
     * - "{key} == value"   → 检测 itemContext 中变量值是否匹配（value 支持 EMPTY 和引号包裹的字符串）
     */
    private suspend fun evaluateCondition(condition: String, itemContext: Map<String, String>): Boolean {
        return when {
            condition.startsWith("exists:") -> {
                val sel = condition.removePrefix("exists:")
                val count = control.locator(sel).count()
                count > 0
            }
            else -> {
                val match = Regex("""\{(.+?)\}\s*==\s*(.+)""").find(condition)
                if (match != null) {
                    val (key, expected) = match.destructured
                    val actual = itemContext[key.trim()] ?: ""
                    val expectedTrimmed = expected.trim().removeSurrounding("'")
                    when (expectedTrimmed) {
                        "EMPTY" -> actual.isBlank()
                        else -> actual == expectedTrimmed
                    }
                } else {
                    DebugLog.info("drill", "无法识别的判定表达式='$condition'，默认为 false")
                    false
                }
            }
        }
    }

    private suspend fun handleAbortItem(step: DrillStep, itemContext: Map<String, String>) {
        val conditionExpr = step.condition ?: throw IllegalArgumentException("abort_item 需要 condition")
        val shouldAbort = evaluateCondition(conditionExpr, itemContext)
        if (shouldAbort) {
            throw AbortItemException("条件判断成立: $conditionExpr", closingTab = step.then == "close_tab")
        }
    }

    /**
     * 分支判断：按顺序评估 branches 中每个条件，执行第一个匹配的分支步骤。
     * 支持 timeout_ms 轮询等待（适用于点击后页面异步加载的场景），
     * 所有条件都不满足时执行 default_steps（如有）。
     */
    private suspend fun handleBranch(
        step: DrillStep,
        itemContext: MutableMap<String, String>,
        onJudgeContent: (suspend (content: String, instruction: String) -> BrainResult)?,
        imageAssets: Map<String, ImageAsset>?
    ) {
        val branches = step.branches ?: throw IllegalArgumentException("branch 需要 branches")
        val timeoutMs = step.timeout_ms ?: 0L
        val pollIntervalMs = step.poll_interval_ms ?: 500L
        val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L

        var matchedBranch: DrillBranch? = null

        if (timeoutMs > 0) {
            // 轮询模式：在超时时间内反复评估条件
            DebugLog.info("drill", "branch: 开始轮询条件匹配 (超时=${timeoutMs}ms, 间隔=${pollIntervalMs}ms)")
            while (true) {
                for (branch in branches) {
                    val matched = evaluateCondition(branch.condition, itemContext)
                    if (matched) {
                        matchedBranch = branch
                        break
                    }
                }
                if (matchedBranch != null) break
                if (System.currentTimeMillis() >= deadline) {
                    DebugLog.info("drill", "branch: 轮询超时 (${timeoutMs}ms)，无分支条件匹配")
                    break
                }
                delay(pollIntervalMs.milliseconds)
            }
        } else {
            // 立即评估模式
            for (branch in branches) {
                val matched = evaluateCondition(branch.condition, itemContext)
                if (matched) {
                    matchedBranch = branch
                    break
                }
            }
        }

        val stepsToExecute = if (matchedBranch != null) {
            DebugLog.info("drill", "branch: 匹配分支条件='${matchedBranch.condition}'，执行 ${matchedBranch.steps.size} 个子步骤")
            matchedBranch.steps
        } else {
            val default = step.default_steps
            if (default != null) {
                DebugLog.info("drill", "branch: 无条件匹配，执行 default_steps (${default.size} 个子步骤)")
            } else {
                DebugLog.info("drill", "branch: 无条件匹配且无 default_steps，跳过")
            }
            default
        }

        // 逐个执行选中分支的子步骤（递归支持嵌套 branch）
        stepsToExecute?.forEachIndexed { stepIndex, subStep ->
            yield()
            DebugLog.info("drill", "执行分支子步骤 [${stepIndex + 1}/${stepsToExecute.size}]: ${subStep.step_name} (动作: ${subStep.action})")
            executeItemStep(subStep, itemContext, onJudgeContent, imageAssets)
        }
    }

    private suspend fun handleTypeSlowly(step: DrillStep, itemContext: Map<String, String>) {
        val selector = step.selector_value ?: throw IllegalArgumentException("type_slowly 需要 selector_value")
        val template = step.action_value ?: ""
        val text = resolveTemplate(template, itemContext)
        // mederi BrowserLocator 无 type；用 fill 近似（语义相当：在输入框中设置文本）
        control.locator(selector).fill(text)
        DebugLog.info("drill", "type_slowly: 输入完成")
    }

    /**
     * 上传文件（stub）。
     *
     * BrowserPilot 原版依赖 page.snapshot() 的 AxTree rawTree 定位 input[type=file] 的
     * refid 再走 CDP DOM.setFileInputFiles。mederi 的 PageSnapshot.rawTree 是 Any?（后端
     * 数据结构未在此层暴露），骨架阶段不接具体结构，记 log 后跳过。
     */
    private suspend fun handleUploadFile(step: DrillStep, itemContext: Map<String, String>) {
        DebugLog.info("drill", "upload_file 未实现（stub），已跳过: ${step.step_name}")
    }

    private fun handleWriteFile(step: DrillStep, itemContext: Map<String, String>) {
        val path = step.file_path ?: throw IllegalArgumentException("write_file 需要 file_path")
        val content = step.action_value ?: itemContext[step.content_from?.firstOrNull() ?: "content"] ?: ""
        val resolvedContent = resolveTemplate(content, itemContext)
        val workingDir = workingDir
        if (workingDir == null) {
            DebugLog.info("drill", "write_file: workingDir 为 null，跳过文件写入: $path")
            return
        }
        val fullPath = File(workingDir, path)
        fullPath.parentFile?.mkdirs()
        fullPath.writeText(resolvedContent)
        DebugLog.info("drill", "write_file: 写入成功 -> $fullPath")
    }

    private fun handleAppendFile(step: DrillStep, itemContext: Map<String, String>) {
        val path = step.file_path ?: throw IllegalArgumentException("append_file 需要 file_path")
        val content = step.action_value ?: itemContext[step.content_from?.firstOrNull() ?: "content"] ?: ""
        val resolvedContent = resolveTemplate(content, itemContext)
        val workingDir = workingDir
        if (workingDir == null) {
            DebugLog.info("drill", "append_file: workingDir 为 null，跳过文件追加: $path")
            return
        }
        val fullPath = File(workingDir, path)
        fullPath.parentFile?.mkdirs()
        fullPath.appendText(resolvedContent)
        DebugLog.info("drill", "append_file: 追加成功 -> $fullPath")
    }

    // ── 列表提取 / 无限滚动 ──

    private suspend fun executeExtractList(step: DrillStep, extractedItems: MutableList<Map<String, String>>) {
        val selector = step.selector_value ?: throw IllegalArgumentException("extract_list 需要 selector_value")
        val limit = step.selector_options?.get("limit")?.toIntOrNull()

        // 1. 利用 JSON 序列化作为 JS 字面量注入，避免手动拼字符串的转义错误
        val recordFields = step.record_fields
        val fieldsJsonObj = if (recordFields != null) Json.encodeToString(recordFields) else "{}"
        val selectorStrLiteral = Json.encodeToString(selector)

        // 2. 将提取逻辑封装为一个一次性的闭包函数在浏览器中执行
        val js = """
            (function(selector, fields, limit) {
                try {
                    var rows = document.querySelectorAll(selector);
                    var results = [];
                    var maxCount = (limit !== null) ? Math.min(rows.length, limit) : rows.length;

                    for (var i = 0; i < maxCount; i++) {
                        var row = rows[i];
                        var item = {};
                        for (var key in fields) {
                            var expr = fields[key];
                            var sel = expr;
                            var attr = null;
                            
                            // 解析含有 @ 的属性提取格式
                            var atIndex = expr.indexOf('@');
                            if (atIndex !== -1) {
                                sel = expr.substring(0, atIndex).trim();
                                attr = expr.substring(atIndex + 1).trim();
                            } else {
                                sel = sel.trim();
                            }

                            // 如果选择器为空，则使用卡片本身
                            var child = (sel === "") ? row : row.querySelector(sel);
                            
                            // 改进的可见性判断
                            var isVisible = false;
                            if (child) {
                                var style = window.getComputedStyle(child);
                                if (style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0') {
                                    if (child.offsetWidth > 0 || child.offsetHeight > 0 || child.getClientRects().length > 0) {
                                        isVisible = true;
                                    }
                                }
                            }

                            if (isVisible) {
                                if (attr !== null) {
                                    var attrVal = child.getAttribute(attr);
                                    item[key] = (attrVal !== null) ? attrVal : "N/A";
                                } else {
                                    var textVal = child.innerText || child.textContent || "";
                                    item[key] = textVal.trim();
                                }
                            } else {
                                item[key] = "N/A";
                            }
                        }
                        results.push(item);
                    }
                    return JSON.stringify(results);
                } catch (e) {
                    return JSON.stringify({ "error": e.toString() });
                }
            })($selectorStrLiteral, $fieldsJsonObj, ${limit ?: "null"})
        """.trimIndent()

        // 3. 一次性获取完整结果
        val resultJson = control.evaluateJavascript(js)

        // 4. 解析结果：防范 JCEF 可能包裹的双引号，以及判断错误
        var actualJsonStr = resultJson
        try {
            actualJsonStr = Json.decodeFromString<String>(resultJson)
        } catch (_: Exception) {}

        val element = try {
            Json.parseToJsonElement(actualJsonStr)
        } catch (_: Exception) { null }

        if (element is JsonObject && element.containsKey("error")) {
            throw Exception("JS 批量提取失败: ${element["error"]}")
        }

        val parsedList = try {
            Json.decodeFromString<List<Map<String, String>>>(actualJsonStr)
        } catch (e: Exception) {
            DebugLog.info("drill", "extract_list 解析结果失败: ${e.message}")
            emptyList()
        }

        extractedItems.addAll(parsedList)
        DebugLog.info("drill", "extract_list 成功: 总共发现并提取了 ${parsedList.size} 个数据项 (通过 JS 极速提取)。")
    }

    private suspend fun executeInfiniteScroll(step: DrillStep) {
        val selector = step.selector_value ?: throw IllegalArgumentException("infinite_scroll 需要 selector_value")
        val maxUnchanged = step.selector_options?.get("max_unchanged")?.toIntOrNull() ?: 2
        val waitMs = step.selector_options?.get("wait_ms")?.toLongOrNull() ?: 2000L

        var previousCount = control.locator(selector).count()
        var unchangedCount = 0

        var scrollRound = 0
        while (unchangedCount < maxUnchanged) {
            scrollRound++
            control.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight)")
            delay(waitMs.milliseconds)
            val currentCount = control.locator(selector).count()
            if (currentCount > previousCount) {
                previousCount = currentCount
                unchangedCount = 0
            } else {
                unchangedCount++
            }
        }
        DebugLog.info("drill", "infinite_scroll: 滚动加载完成，最终节点数量: $previousCount")
    }

    // ── locator 解析 / 动作执行 / 结果捕获 ──

    private suspend fun resolveDrillLocator(step: DrillStep): BrowserLocator {
        val playwrightApi = step.playwright_api ?: "locator"

        // mederi BrowserControl 只有 locator(selector)，getBy* 全部退化为 locator
        var locator = when (playwrightApi) {
            "locator" -> control.locator(step.selector_value!!)
            else -> {
                val fallbackSelector = step.selector_value ?: step.selector_type ?: ""
                DebugLog.info("drill", "playwright_api $playwrightApi 退化为 locator (selector='$fallbackSelector')")
                control.locator(fallbackSelector)
            }
        }

        val hasText = step.filter_has_text
        if (hasText != null) {
            locator = locator.filter(hasText)
        }
        if (step.first == true) {
            locator = locator.first()
        }
        if (step.last == true) {
            locator = locator.last()
        }

        return locator
    }

    private suspend fun executeAction(locator: BrowserLocator?, step: DrillStep) {
        val actionLower = step.action.lowercase()
        val selectorStr = step.selector_value ?: "N/A"
        try {
            when (actionLower) {
                "click" -> {
                    locator!!.click()
                }
                "fill" -> {
                    val value = step.action_value ?: ""
                    locator!!.fill(value)
                }
                "hover" -> {
                    locator!!.hover()
                }
                "press" -> {
                    val keyStr = step.action_value ?: "Enter"
                    val key = mapKey(keyStr)
                    // mederi BrowserLocator 无 press，press 走 control
                    control.press(key)
                }
                "check" -> {
                    // mederi 无 check，简化为 click
                    locator!!.click()
                }
                "uncheck" -> {
                    // mederi 无 check，简化为 click
                    locator!!.click()
                }
                "scroll" -> {
                    val value = step.action_value ?: "down"
                    val deltaY = when (value.lowercase()) {
                        "down" -> 400
                        "up" -> -400
                        else -> value.toIntOrNull() ?: 400
                    }

                    // mederi BrowserLocator 无 scroll，统一走 control.scrollByCoordinates
                    control.scrollByCoordinates(500, 500, 0, deltaY)
                }
                "none" -> {
                }
                else -> throw IllegalArgumentException("Unsupported action: ${step.action}")
            }
            DebugLog.info("drill", "executeAction: 动作 [${step.action}] 执行成功 (selector: $selectorStr)")
        } catch (e: Exception) {
            DebugLog.error("drill", "executeAction: 动作 [${step.action}] 执行失败: ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    private suspend fun captureResult(locator: BrowserLocator, step: DrillStep): String {
        return try {
            val res = when {
                step.action.lowercase() == "fill" -> step.action_value ?: ""
                else -> {
                    val text = locator.getText()
                    val href = locator.getAttribute("href")
                    if (!href.isNullOrBlank()) {
                        val cleanText = text.trim()
                        if (cleanText.isNotEmpty()) "$cleanText | $href" else href
                    } else {
                        if (text.isNotBlank()) text.trim() else (locator.getAttribute("value") ?: "N/A")
                    }
                }
            }
            res
        } catch (e: Exception) {
            DebugLog.info("drill", "captureResult: 抓取结果发生异常: ${e.message}")
            "Error capturing result: ${e.message}"
        }
    }

    // ── 模板 / 条件 / 按键映射 ──

    private fun resolveTemplate(template: String, ctx: Map<String, String>): String =
        Regex("""\{(.+?)\}""").replace(template) { ctx[it.groupValues[1]] ?: it.value }

    private class AbortItemException(message: String, val closingTab: Boolean = false) : Exception(message)

    private class ConsecutiveItemFailureException(
        val consecutiveCount: Int,
        val totalItems: Int,
        val successCount: Int,
        message: String
    ) : Exception(message)

    private fun mapKey(key: String): KeyboardKey {
        return when (key.uppercase()) {
            "ENTER" -> KeyboardKey.ENTER
            "TAB" -> KeyboardKey.TAB
            "ESCAPE", "ESC" -> KeyboardKey.ESCAPE
            "BACKSPACE" -> KeyboardKey.BACKSPACE
            "DELETE" -> KeyboardKey.DELETE
            "ARROWUP", "ARROW_UP", "UP" -> KeyboardKey.ARROW_UP
            "ARROWDOWN", "ARROW_DOWN", "DOWN" -> KeyboardKey.ARROW_DOWN
            "ARROWLEFT", "ARROW_LEFT", "LEFT" -> KeyboardKey.ARROW_LEFT
            "ARROWRIGHT", "ARROW_RIGHT", "RIGHT" -> KeyboardKey.ARROW_RIGHT
            "SHIFT" -> KeyboardKey.SHIFT
            "CONTROL", "CTRL" -> KeyboardKey.CONTROL
            "ALT" -> KeyboardKey.ALT
            "META", "COMMAND", "CMD" -> KeyboardKey.META
            "SPACE" -> KeyboardKey.SPACE
            "A" -> KeyboardKey.A
            "C" -> KeyboardKey.C
            "V" -> KeyboardKey.V
            "X" -> KeyboardKey.X
            "S" -> KeyboardKey.S
            "Z" -> KeyboardKey.Z
            else -> KeyboardKey.ENTER
        }
    }
}