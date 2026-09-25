package xyz.mederi.browser

import kotlinx.serialization.Serializable

/**
 * 浏览器自动化配方（Recipe，对应 BrowserPilot 的 Skill 体系）。
 *
 * 挂载在浏览器任务上的结构化配置，供 BrowserOperator 和 BrowserBrain 读取：
 * - [name]: 配方标识（例如 "v2ex_hot_topics", "job_filter"）
 * - [description]: 配方用途与目标说明
 * - [judgeRules]: 给 BrowserBrain 的判定准则（对标 BrowserPilot Skill 的 judgeContent）
 * - [drillScript]: 可选的确定性自动化执行脚本（Drill）
 */
@Serializable
data class BrowserRecipe(
    val name: String = "",
    val description: String = "",
    /** 执行操作准则（对应 skill 的 "## 操作规则" 节），供 BrowserOperator 注入执行提示词。 */
    val operatorContent: String = "",
    val judgeRules: String = "",
    val drillScript: String? = null
)
