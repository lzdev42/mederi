package xyz.mederi.prompt

/**
 * 提示词"教程"常量库（Prompt Guides）。
 *
 * 与 SystemPrompts 的分工：SystemPrompts 是骨架（身份/原则/工具清单/工作流拼接），
 * 本文件是拼装素材——每个主题一份完整指导常量，往 SystemPrompts 里拼装。
 * 新主题（如何用 git、如何写测试……）在这里加常量即可。
 *
 * 全部用英文：减少 token，且模型对英文指令遵循更稳。
 */
object PromptGuides {

    /**
     * 执行沙箱使用教程。
     *
     * 核心事实（CommandSandbox 实现对齐）：
     * - 永远开：文件工具的写路径校验（纯 Kotlin）全平台硬约束；命令走 OS 级沙箱
     * - 读全盘放行；写白名单 = 项目目录 + .mederi/ + 系统临时目录 + 构建缓存 + 全局白名单
     * - macOS：Seatbelt，必然可用；Linux：bubblewrap（缺失则警告+安装命令，不代装）；
     *   Windows：无命令沙箱，文件工具约束仍然生效
     */
    val SANDBOX_USAGE: String = """
# Command Sandbox (how shell writes are isolated)

File-tool writes are path-checked in code on every platform (see Working
Directory above). Shell commands run under an OS-level sandbox that varies by
platform:

- macOS: every command runs under Seatbelt (sandbox-exec). Writes outside the
  whitelist fail with "Operation not permitted". This always works.
- Linux: commands run under bubblewrap (bwrap). If not installed, they run
  unsandboxed with a "[sandbox]" warning; install via
  `sudo apt/dnf/pacman install bubblewrap`.
- Windows: no native command sandbox; shell writes are unrestricted, but the
  file-tool path check above still applies fully.

If a legitimate write is blocked, the target is outside the project — ask the
user to add the directory to the project or the global sandbox whitelist
(Settings). Never retry the same blocked command expecting a different result.
""".trimIndent()

    /**
     * Mermaid 绘图与架构图拆分规范。
     *
     * 核心规则：
     * - 语法安全：特殊字符用引号、禁用 HTML 标签、避免未转义字符
     * - 结构拆分：严禁单张巨图；先总览后分层/分模块详解；每图单一关注点；嵌套超一层或连线混乱即拆分
     */
    val MERMAID_GUIDELINES: String = """
# Mermaid Tips

- Quote labels that contain special characters: `id["Label (v2)"]`.
- No raw HTML inside labels.
- One concern per diagram. Split a complex system into an overview plus one
  diagram per module/layer; if subgraphs would nest deeper than one level, split
  into separate diagrams instead.
""".trimIndent()

    /**
     * 计划工具纪律 + create_plan 完整参数范例。
     *
     * 实测背景（端到端审计，2026-09）：弱模型会
     * 1) 把 overview 等结构化字段塞进 architecture 大杂烩、漏填必填字段 → 解析/校验连环失败；
     * 2) 编造 planId（"placeholder"）继续调 generate_spec/spawn_agent → 全部被拒还继续跑；
     * 3) 同一条消息里并行发射 create_plan+generate_spec+spawn_agent 霰弹枪乱调。
     * 本指南三味药：硬规则（顺序/报错即停/禁止编造）+ 完整 JSON 范例 + 聚合校验配合说明。
     */
    val PLAN_TOOL_GUIDE: String = """
# Plan Tool Discipline (create_plan / generate_spec / spawn_agent / verify_subtask)

Hard rules — violating any of these wastes the entire turn:

1. Plan tools are STRICTLY SEQUENTIAL and each depends on the previous one's
   result. One call per message step: create_plan must SUCCEED before
   generate_spec; generate_spec before spawn_agent. Never fire later steps in
   the same message as an earlier step.
2. A planId exists ONLY after create_plan succeeds and returns it in its
   result. If you don't have a real planId, the only correct call is
   create_plan. NEVER invent an id (e.g. "placeholder").
3. When any tool returns an Error, STOP and fix that call. Read the error —
   validation errors list EVERY missing item; fix all of them in the retry.
   Never continue to dependent calls after a failure, and never report
   plan-based progress (subtasks, verification) that never actually ran.

create_plan arguments are ONE JSON object. Required fields in CODE mode:
title, overview, businessLogic, languageStack, inScope, keyDecisions, changes,
successCriteria, subtasks. Optional: summary, projectContext (default
BROWNFIELD), outScope, dataAndParams, risks, architecture. List fields take
JSON arrays of strings. Every subtask needs name, planDetail, targetFiles,
verification; dependsOn is an array of 0-based indices.

Complete example (shape to follow, content from your real task):

```json
{
  "title": "Add subtraction feature",
  "summary": "One sentence: what changes and to what end.",
  "projectContext": "BROWNFIELD",
  "overview": "Background and goals, 1-2 sentences.",
  "businessLogic": "Entry point, before/after behavior, where the new logic hooks into the call chain.",
  "languageStack": "Python 3.12",
  "inScope": ["Add sub(a,b) to pkg/calc.py", "Export sub in pkg/api.py"],
  "outScope": ["No CLI changes"],
  "keyDecisions": [
    {"question": "How to test?", "choice": "plain assert script",
     "rationale": "no test framework in the project",
     "alternatives": ["pytest", "unittest"]}
  ],
  "changes": [
    {"module": "core", "action": "MODIFY", "filePath": "pkg/calc.py",
     "description": "Add sub(a,b) function", "rationale": "extends core math"},
    {"module": "test", "action": "NEW", "filePath": "pkg/test_calc.py",
     "description": "Add sub test", "rationale": "no existing test file to modify"}
  ],
  "successCriteria": ["Verification command for the last subtask passes"],
  "subtasks": [
    {"name": "Implement sub",
     "planDetail": "Add sub(a,b) to pkg/calc.py, matching existing style.",
     "targetFiles": ["pkg/calc.py"],
     "verification": "python3 -c 'import pkg.calc; assert pkg.calc.sub(5,3)==2'",
     "dependsOn": [], "parallelizable": false}
  ]
}
```

After approval, per subtask: generate_spec(planId, subtaskIndex, spec) with the
spec as an ordered checklist, then spawn_agent(planId, subtaskIndex), then
verify_subtask(planId, subtaskIndex, status, evidence).
""".trimIndent()
}
