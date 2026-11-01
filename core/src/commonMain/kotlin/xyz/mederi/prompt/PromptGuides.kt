package xyz.mederi.prompt

/**
 * 提示词"教程"常量库（Prompt Guides）。
 *
 * 与 SystemPrompts 的分工：SystemPrompts 是骨架（身份/原则/工具清单/工作流拼接），
 * 本文件是拼装素材——每个主题一份指导常量，往 SystemPrompts 里拼装。
 * 新主题（如何用 git、如何写测试……）在这里加常量即可。
 *
 * 压缩原则（2026-09）：只保留 mederi 特有事实，删重复与铺陈——通用常识不教，
 * 模型不会天然知道的（沙箱规则/渲染特性/计划字段）一个不丢，措辞压到信息密度最高。
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
# Command Sandbox (shell write isolation)

File-tool writes are path-checked in code on every platform. Shell commands run under
an OS-level sandbox:

- macOS: Seatbelt (sandbox-exec), always on. Writes outside the whitelist fail with
  "Operation not permitted".
- Linux: bubblewrap. Not installed → commands run unsandboxed with a "[sandbox]" warning
  (install via `sudo apt/dnf/pacman install bubblewrap`).
- Windows: no native command sandbox; the file-tool path check still applies fully.

Write whitelist = project directory + `.mederi/` + system temp + build caches + global
whitelist. A blocked write means the target is outside the project — ask the user to
add it to the project or the global sandbox whitelist (Settings). Never retry the same
blocked command.

On macOS, use stop_process to stop mederi-started processes: sandboxed kill/pkill cannot
send the required cross-process signals. Never stop processes not started by Mederi.
""".trimIndent()

    /**
     * Markdown & 格式化公共指南（教 AI 输出能被 InkCompose 正确渲染的富文本）。
     *
     * 只保留 mederi 特有渲染事实：file:// 可点击链接 + vlr 竖排文字 + KaTeX 数学 +
     * GitHub Alerts。Mermaid 规范已收敛到 [MERMAID_GUIDELINES]（一份，不重复）。
     */
    val MARKDOWN_FORMAT: String = """
# Markdown & Formatting

## Local Links & Media
- Clickable `file://` links for ALL referenced files/symbols:
  `[name](file:///abs/path)` / `[name#L10-L20](file:///abs/path#L10-L20)`.
- Embed images/videos: `![caption](/abs/path.jpg)`.

## Vertical Text (`vlr`)
- A fenced block with info string `vlr` renders vertical text (top-to-bottom, left-to-right)
  for Mongolian, Manchu, Xibe scripts.
- Attributes: height (alias `h`, dp/px) · fontSize (aliases `font-size`, `size`, sp/px) ·
  wrap (aliases `autowrap`, `auto-wrap`, true/false, effective only with height).
- No height → columns break at newlines only; height+wrap=true → auto-wrap; height+wrap=false → scroll.
- Inside an artifact, vlr blocks are preserved on HTML/PDF export (font embedded).

## Horizontal Rules
- `---` separates DISTINCT narrative blocks only — NOT decoration. Three blank lines above AND
  below. Never directly under a heading (mis-renders as setext underline).

## Alerts
GitHub-style: `[!NOTE]` `[!TIP]` `[!IMPORTANT]` `[!WARNING]` `[!CAUTION]`. Don't stack
consecutively or nest.
""".trimIndent()

    /**
     * Mermaid 绘图规范（唯一一份；OUTPUT_FORMAT 不再重复讲 mermaid 能力边界）。
     */
    val MERMAID_GUIDELINES: String = """
# Mermaid Tips

Use a fenced block whose info string is exactly `mermaid`; suffixes disable rendering.
Other diagram DSLs (PlantUML/DOT/d2) display as source; use them only when requested as text.
Quote labels containing special characters: id["Label (v2)"]. Avoid raw HTML in labels.
Keep one concern per diagram; split complex views and avoid deeply nested subgraphs.
""".trimIndent()

    /** 计划工具的依赖顺序与可执行验收契约，参数形状以 schema 为准。 */
    val PLAN_TOOL_GUIDE: String = """
# Plan Tool Contract

Only use these tools for the software-development workflow described in Plan Loop.
write_plan must succeed before dependent generate_spec calls; specs must be saved before execution.
Never invent planId. Stop and fix failed calls before running their dependents. Independent spec
writes may be batched, but write_plan must be alone and specs must not share a message with SPAWN.

Each subtask requires targetFiles, verification (one executable ASCII command), verificationExpected
(user-readable expected behavior), and verificationExpectStdoutContains (at least one literal the
command actually prints). Optional cwd/timeout and forbidden literals use the tool schema's fields.
For behavior changes, verify the changed behavior, not just successful compilation or a generic
BUILD SUCCESSFUL marker. A bare assert or grep -q prints nothing: make a successful check emit the
literal you require, e.g. python3 -c 'assert 2 + 2 == 4; print("CHECK_PASS")' with ["CHECK_PASS"].
This example illustrates output matching only; actual checks must exercise the requested behavior.
Confirm paths and valid cases before writing checks; negative searches must not reject legitimate fallback code.
The tool executes the contract; your evidence must describe the actual result, not a predicted one.
""".trimIndent()

    /** 计划概览：只记录已确认方案，按信息需要组织内容。 */
    val PLAN_OVERVIEW_GUIDE: String = """
# Plan Overview

A plan records an agreed software change. Resolve decisions via ask_user before writing it;
leave openQuestions empty and include no unresolved defaults or User Review Required section.
Use a flowing goal statement, a concrete approach (behavior, data flow, call chain, or algorithm),
and independently verifiable subtasks. Explain genuine trade-offs and the agreed choice.
Use Mermaid to make architecture, flows, states, or alternatives clearer when useful.
Fill only informative optional fields; avoid repeating the goal in background, summary, and outcome.
The summary is the approval card's short takeaway; detailed file changes belong in changes.
Verification describes how correctness will be established, with manual checks only where needed.
""".trimIndent()

    /** 计划详情：具体改动与必要证据，不重复概览。 */
    val PLAN_APPENDIX_GUIDE: String = """
# Plan Detail

For each changed file, explain the concrete before/after behavior and its purpose. Justify new
files and deletions. Subtask planDetail conveys the approach the user approves; line-level
instructions belong in the later spec. Optional appendices hold evidence, root-cause reasoning,
and cross-subtask context that would otherwise obscure the overview. Prefer key excerpts or
pseudocode over source dumps; omit empty, repetitive, or obvious material.
""".trimIndent()

    /**
     * 子代理汇报核验纪律（静态指令）。
     *
     * 归位说明（2026-10）：这段语义原先由 `TurnExecutor` 在组装 `<event_message>` 时**动态追加**，
     * 违反「动态注入只挂状态、不挂指令」（AGENTS §5.6 审计备忘第 5 条）。现改为静态素材，
     * 由 [SystemPrompts] 拼进主代理系统提示词；`<event_message>` 只保留 role/reportPath/summary 等状态事实。
     */
    val SUBAGENT_REPORT_VERIFICATION: String = """
# Reviewing Delegated Results

Completion events report claims, not verified success. Check the result against the assigned goal
and supporting evidence. Read ReportPath or affected artifacts when the summary is insufficient;
missing evidence, unresolved blockers, or hidden SPEC_FEEDBACK mean work remains.

For plan executors, inspect actual sources before handling SPEC_FEEDBACK:
- missing / ambiguous / vs-reality: investigate, resolve the gap, amend via generate_spec(reason=...),
  then execute directly or delegate again using the normal cost judgment.
- unsatisfiable: ask_user which requirement wins; never silently change intent.
- compile-blocked: assess scope/semantics, confirm changed decisions with the user if needed,
  then amend the spec before resuming.
For ad-hoc work, resolve blockers against the confirmed assignment without inventing a plan.
""".trimIndent()

    /** 软件计划的实现规范；适用构建检查由父代理指定，不套用到无计划任务。 */
    val SPEC_WRITING_GUIDE: String = """
# Spec Writing Guide — software Plan Loop only

The executor receives the stored spec, not the parent's conversation or AGENTS.md. Ground it in
actual files and the completed output of dependencies. Include:
- Exact modification scope and reference files, with location anchors.
- Ordered per-file changes, relevant types/signatures, call-site wiring, behavior and edge cases.
- Applicable build/compile command and cwd/target. If compilation is inapplicable, state why and
  specify an appropriate non-test check instead. Plan tests are run by the parent via verify_subtask.
- Project constraints translated into concrete actions; allowed mechanical fixes and stop boundaries.

Resolve material choices before delegation; do not leave business semantics as "handle reasonably".
Keep enough detail to implement without guessing, without transcribing whole files. Replacing an
existing spec requires reason and preserves the prior version. This guide does not apply to ad-hoc
non-software assignments.
""".trimIndent()

    /** 可选研究委派的 briefing 与证据核验。 */
    val RESEARCH_DELEGATION: String = """
# Research Briefing

When delegation is worthwhile, give the research question, purpose, necessary background,
scope, known constraints or evaluation criteria, and useful starting sources. Do not prescribe
unnecessary search steps. Ask for findings with sources and reasoning, not a dump of raw material.
Evaluate evidence and deductions independently; neither a child's confidence nor agreement with
your initial premise establishes correctness. Preserve useful findings for later work.
""".trimIndent()
}
