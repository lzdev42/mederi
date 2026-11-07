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
     * 项目两档独立于计划审批；受限模式越界前申请本次用户批准。
     * macOS Seatbelt / Linux bwrap+seccomp 默认禁止命令网络，后端失败即停。
     * 完全访问或已批准 off 才解除沙盒；不默认放开家目录构建缓存。
     */
    val SANDBOX_USAGE: String = """
# Execution permissions

Each project has two execution permission modes, independent of plan approval and AgentMode.
Only the user can change the project mode; never edit configuration files to bypass it.

- NEEDS_APPROVAL (default): file writes within project/temp/explicit writable roots run
  directly. Protected metadata/configuration paths and outside writes require approval
  for this exact tool call. File tools request it automatically before changing anything.
- Commands use macOS Seatbelt or Linux bubblewrap plus seccomp. Networking is disabled;
  protected paths remain read-only. Missing or broken backends block execution, never
  silently fall back to unsandboxed execution. Windows has no restricted backend yet.
- FULL_ACCESS: commands and file writes do not ask for execution approval. Explicit
  project path and command denials still apply. Plan approvals and ask_user remain independent.

Project denied paths forbid both reading and writing, including in FULL_ACCESS or an
approved sandbox="off" call; approval cannot override them. Command rules are static argv
prefixes, with deny taking precedence. An allowed prefix skips elevation approval but
does not relax sandbox="default". Pipes, redirection and expansions cannot match an allow
rule; when deny rules exist, split complex shell syntax into individual static commands.

For a command that needs networking, use bash(sandbox="network"). For a command that
must write protected/outside paths or cannot work inside the sandbox, use
bash(sandbox="off"). In NEEDS_APPROVAL these requests wait for one-time user approval;
they do not change the project mode or whitelist. Rejection is not permission to try an
equivalent bypass. verify_subtask accepts the same sandbox parameter for its own stored
verification command. Never infer sandbox failure from a nonzero exit alone.
Build caches outside the writable roots are not implicitly writable. Web search/fetch
are independent read-only HTTP tools, not grants of network permission to commands.

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
Never invent planId. Stop and fix failed calls before running their dependents. Prefer sending
independent generate_spec calls together in one response. Group ready verify_subtask calls only
when their stored commands can run concurrently without sharing build outputs or other mutable
resources. Use the plan tools directly for these groups. Send write_plan alone and wait for specs
to be saved before sending SPAWN. Let verify_subtask run its stored command instead of repeating
that command as a separate preflight when the same evidence would suffice.

Each subtask specifies targetFiles and a collector command. Give each acceptance case a unique ID,
a business or calculation objective, initial state, concrete input, steps through a real entry point,
the observation source, exact expected scalar and the defect it would detect. The collector must
print one JSON object with actual scalar values keyed by case ID. Core compares the values to the
approved expectations. Exit status, success markers and prose are not observations. List every
collector script, assertion helper and fixture in acceptanceFiles so its content is pinned.
For a fix, exercise the check against the old behavior and verify the target assertion can fail;
setup errors are separate. Use a dedicated manual task only when automated observation is
impractical. Set manual=true for its cases, omit its command and ask the operator to record
observed values and evidence via the acceptance UI or API. For a new entry point, check the observation logic against known-good
and known-bad samples first. The final overallAcceptance task depends on all implementation tasks
and exercises their integration through the actual entry point.
""".trimIndent()

    /** 计划概览：只记录已确认方案，按信息需要组织内容。 */
    val PLAN_OVERVIEW_GUIDE: String = """
# Plan Overview

A plan records an agreed software change. Resolve decisions via ask_user before writing it;
leave openQuestions empty and include no unresolved defaults or User Review Required section.
Use a flowing goal statement, a concrete approach (behavior, data flow, call chain, or algorithm),
and independently verifiable subtasks. Explain genuine trade-offs and the agreed choice.
The implementation approach is required: explain the verified current behavior and cause or
requirement gap, the proposed behavior, how data/control flows through the affected components,
and how the checks establish the requested outcome. A goal, file list, or a generic one-line
"modify X to support Y" is insufficient. Scale detail to the actual change; do not pad simple facts.
Use Mermaid to make architecture, flows, states, or alternatives clearer when useful.
Fill only informative optional fields; avoid repeating the goal in background, summary, and outcome.
The summary is the approval card's short takeaway; detailed file changes belong in changes.
Verification describes how correctness will be established, with manual checks only where needed.

Write for a narrow Markdown reader. Organize the overview around the decision the reader needs
to make, using short paragraphs and headings only where they help scanning. Keep task IDs and case
IDs visible. For each case, group the initial state and exact inputs, then the real action and
where to observe the result. Keep the expected value and the defect this check detects close
together. Use a short list for steps, inline code for short IDs and values, and fenced code for
multiline data. Use tables only for compact comparisons with a few short columns. Reserve bold for
key decisions, values, and outcomes. Put raw logs and long paths with their evidence. In delivery
notes, put the expected value, observed value, judgment and evidence reference together for each
case. Use plain status labels for failed, blocked or unverified checks.
""".trimIndent()

    /** 计划详情：具体改动与必要证据，不重复概览。 */
    val PLAN_APPENDIX_GUIDE: String = """
# Plan Detail

For each changed file, explain the concrete before/after behavior and its purpose. Justify new
files and deletions. Subtask planDetail conveys the approach the user approves; line-level
instructions belong in the later spec. Optional appendices hold evidence, root-cause reasoning,
and cross-subtask context that would otherwise obscure the overview. Prefer key excerpts or
pseudocode over source dumps; omit empty, repetitive, or obvious material.
For complex changes, preserve the current logic, decisive evidence, causal explanation, and
cross-subtask assumptions in the relevant detail or appendix. Keep conclusions tied to sources;
an appendix supplements the approach and must not hide unresolved decisions needed to execute it.
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
For conclusions that determine a fix or the next task, inspect the cited source and relevant
implementation or check result before accepting them. A confidence statement or completion status
is not evidence. Use the full report when its abbreviated event omits the necessary reasoning.

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

## Mandatory pre-dispatch self-check

You own the spec's correctness. Before saving it as ready for execution or dispatching a child,
read it from the executor's perspective, without relying on this conversation. Check every item:
1. Sources: referenced paths, location anchors, types, signatures, and current behavior were checked
   against actual files. Mark assumptions explicitly; never turn an unverified diagnosis into a step.
2. Preconditions: dependency outputs exist and were reviewed. Describe the actual resulting state,
   not what an earlier subtask was expected to produce. Recheck affected assumptions after changes.
3. Wiring and scope: include affected producers, consumers, call sites, and applicable interface or
   platform counterparts; confirm the proposed steps address the cause and relevant other occurrences.
4. Semantics: specify necessary branches, missing/error data handling, compatibility, and meaningful
   edge cases. No material choice may depend on the child guessing the parent's intent.
5. Checks and boundaries: supply the applicable build command and working directory (or justified
   alternative), the parent's behavior verification, mechanical-fix allowance, and when to stop/report.
   The checks must cover the original failure or requested outcome, not just compilation.
If an item is unresolved, investigate or obtain the missing decision before dispatch. Do not send
a knowingly incomplete or contradictory spec and rely on SPEC_FEEDBACK to repair it downstream.

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
