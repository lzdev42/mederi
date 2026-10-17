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

Long-running processes: background them and redirect output to a file under the project:

    python3 -m http.server 8000 > server.log 2>&1 &

Track with list_processes (mederi-spawned pids), stop with stop_process(pid). macOS
Seatbelt forbids ALL cross-process signals — `kill`/`pkill`/`kill -9` inside
bash fail. stop_process runs outside the sandbox and only touches
mederi-started processes, so it is the only reliable way to stop your servers.
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

- Mermaid is the ONLY diagram format we render: every diagram must be a ```mermaid block.
  PlantUML / Graphviz DOT / d2 have no renderer and show as plain source — never emit them
  unless the user explicitly asks for that format as text.
- **Fence info string MUST be exactly `mermaid` and nothing else.** Never append extra words
  after "mermaid" — ```` ```mermaid mermaid ````, ```` ```mermaid diagram ````,
  ```` ```mermaid flowchart ```` etc. are INVALID: InkCompose matches the exact info string,
  any suffix falls back to a plain code block and the diagram does not render.
- Quote labels with special characters: `id["Label (v2)"]`. No raw HTML inside labels.
- One concern per diagram. Split a complex system into an overview plus one diagram per
  module/layer; no subgraphs nested deeper than one level.
""".trimIndent()

    /**
     * write_plan 参数纪律：字段清单 + 精简范例 + 时序硬规则。
     *
     * 压缩原则：与 SystemPrompts.PLANNING_DISCIPLINE 的重复内容（"别编造 planId"、
     * "顺序执行"）收敛到这里，PLANNING_DISCIPLINE 只讲流程判断；两个文件各讲各的。
     */
    val PLAN_TOOL_GUIDE: String = """
# Plan Tool Discipline (write_plan / generate_spec / subagent(SPAWN) / verify_subtask)

Hard rules — violating any wastes the whole turn:
1. Plan tools are STRICTLY SEQUENTIAL, one per message step: write_plan must SUCCEED before
   generate_spec, generate_spec before subagent(SPAWN). Never fire later steps in the same message
   as an earlier one.
2. A planId exists ONLY after write_plan succeeds. If you have no real planId, the only correct
   call is write_plan. NEVER invent an id.
3. Any plan-tool Error → STOP and fix that call (validation errors list every missing item).
   Never continue to dependent calls after a failure; never report plan progress that never ran.



Example (shape to follow; content from your real task):

```json
{

  "languageStack": "Python 3.12",
  "inScope": ["Add sub(a,b) to pkg/calc.py", "Export sub in pkg/api.py"],
  "outScope": ["No CLI changes"],
  "keyDecisions": [{"question": "How to test?", "choice": "plain assert script",
     "rationale": "no test framework in the project", "alternatives": ["pytest"]}],
  "changes": [{"module": "core", "action": "MODIFY", "filePath": "pkg/calc.py",
     "description": "Add sub(a,b) function", "rationale": "extends core math"},
     {"module": "test", "action": "NEW", "filePath": "pkg/test_calc.py",
     "description": "Add sub test", "rationale": "no existing test file"}],
  "successCriteria": ["Verification command for the last subtask passes"],
  "subtasks": [{"name": "Implement sub", "planDetail": "Add sub(a,b) to pkg/calc.py.",
     "targetFiles": ["pkg/calc.py"],
     "verification": "python3 -c 'import pkg.calc; assert pkg.calc.sub(5,3)==2'",  // command YOU run via verify_subtask after executor completes — NOT something the executor runs
     "verificationCwd": null,        // optional: verification working dir (relative to project root); null = project root
     "verificationTimeoutSeconds": 30, // optional: verification timeout seconds; verification must be a single ASCII command (prose rejected)
     "dependsOn": [], "parallelizable": false,  // "appendix": "Optional. Subtask technical reasoning: current logic, code evidence, root cause, fix preview."
     }],
     // "appendix": "Optional. Plan-level technical context shared across subtasks."
}
```

After approval, per subtask: generate_spec(planId, subtaskIndex, spec) as an ordered checklist,
then subagent(SPAWN, planId, subtaskIndex) (returns agentId, runs in background, END YOUR TURN —
you will be auto-woken with the result), then verify_subtask(planId, subtaskIndex, status, evidence).
""".trimIndent()

    /**
     * 计划概览撰写指引：菜单式取舍（非固定填表）；Goal 单段叙事；Approach 必填硬核；
     * 验证计划拆自动/手动；破坏性决策走 pre-plan ask_user；Open Questions 非阻塞默认。
     */
    val PLAN_OVERVIEW_GUIDE: String = """
# Plan Overview Writing Guide

The plan document's Part 1 (human-readable, above the first ---) is NOT a fixed schema to fill —
it is a MENU of sections. Include only sections that carry information for THIS task; leave
irrelevant fields empty (the renderer skips empty fields — do NOT pad). Every plan should look
different because every task is different.

## Section menu (renderer behavior)
- ## 目标（Goal）: rendered from background + problemAnalysis + expectedOutcome merged into ONE
  narrative section (any one non-blank → section renders). Write it as a flowing story:
  the problem + background + what this change accomplishes. Do NOT split into sub-headers.
- ## 实现方案（Approach）: REQUIRED, always rendered (even if you write little — empty is visible,
  which is intentional). This is the hard core Antigravity-style skeletons lack. Spell out the
  business logic / how it works: entry point, call chain (before vs after), rules, data flow,
  key algorithms. Match the internal structure to the task type:
    * algorithm task → algorithm steps
    * refactor → call-chain before/after
    * cleanup → delete list
    * new feature → data flow + rules
  Never collapse this to a one-liner.
- ## 验证计划（Verification Plan）: optional (rendered only if testMethod non-blank). Split into
  ### 自动测试（Automated Tests） with exact commands, and ### 人工验证（Manual Verification）
  with what the user should manually check.
- ## 范围 / ## 风险 / ## 成功标准: optional, render only when there is real content.
- ## 默认决策（Open Questions）: optional, for non-blocking defaults you took without asking
  ("Chose X because Y — disagree? say so in chat").

## Hard rules
- NEVER include a "需要你确认 / User Review Required" section — that field is removed. Anything
  requiring user sign-off (breaking changes, major trade-offs) MUST be resolved via ask_user
  BEFORE write_plan; the plan only reflects already-decided outcomes.
- Omit sections that add no information for this task; do not pad to fill structure.
- Use diffs / mermaid / code snippets inline where they help (mermaid goes in the architecture
  field). Omit rich media for trivial changes.
- The detailed file-level changes (MODIFY/NEW/DELETE grouped by component) belong in the Changes
  appendix below the first ---, not in the overview.
""".trimIndent()

    /**
     * 技术附录（appendix）撰写指引：subtask 级与 plan 级，论证版（WHY），与 spec（HOW）区分。
     */
    val PLAN_APPENDIX_GUIDE: String = """
# Appendix Writing Guide

The appendix is the detailed implementation section — for thorough reviewers who want to
understand exactly what will change. Unlike the concise overview, be substantive here.

## Changes
- For each file, describe WHAT specifically changes — not just "Add function X" but
  "Add function X that does Y, called from Z". For [MODIFY]: what exists now and what it
  becomes. For [NEW]: what it does and why a new file is needed (vs modifying existing).
  For [DELETE]: what it contained and why it's removed.

## Subtask Briefs (planDetail)
- Describe the approach for this subtask — not just the task title. The user reviews
  this to understand HOW the subtask will be done before approving. Include: which files
  are touched, what the key change is, any tricky parts.

## Key Decisions
- Surface real trade-offs, not obvious choices. Each decision should have a genuine
  alternative that was considered and rejected for a stated reason.

## Architecture (optional)
- Include a Mermaid diagram when the approach benefits from visualization (data flow,
  architecture, sequence). Omit for trivial changes.

## Subtask Appendix (free-text, optional)
- For complex subtasks: current logic, code evidence, root cause, fix preview.
- HIGH information density — no full source dumps, only key code lines + core contradiction.
- Pseudo-code is preferred over real code for fix previews.
- Omit for simple subtasks (new files, mechanical changes).
""".trimIndent()

    /**
     * 子代理汇报核验纪律（静态指令）。
     *
     * 归位说明（2026-10）：这段语义原先由 `TurnExecutor` 在组装 `<event_message>` 时**动态追加**，
     * 违反「动态注入只挂状态、不挂指令」（AGENTS §5.6 审计备忘第 5 条）。现改为静态素材，
     * 由 [SystemPrompts] 拼进主代理系统提示词；`<event_message>` 只保留 role/reportPath/summary 等状态事实。
     */
    val SUBAGENT_REPORT_VERIFICATION: String = """
# Subagent Report Verification

A subagent <event_message> reports what the subagent claimed, not what is verified. When you are
woken by a SUBAGENT_COMPLETED / SUBAGENT_ERROR event: Inspect the summary critically before
assuming success.

- Check the report against the task you dispatched, and that quoted verification evidence exists.
- Treat missing evidence, hidden SPEC_FEEDBACK, or a summary that does not match the task as
  unfinished work.
- If in doubt, read the report file (ReportPath, e.g. `.mederi/plans/{planId}/reports/NN-executor.md`)
  or the touched files before continuing the plan.
""".trimIndent()

    /**
     * Spec 撰写引导：执行器零项目上下文，spec 是其唯一知识来源。
     * 引导式（非硬约束）——AI 自行决定包含多少上下文。
     */
    val SPEC_WRITING_GUIDE: String = """
# Spec Writing Guide

The executor subagent has NO project context — it does not see AGENTS.md, architecture docs,
or any coding principles. The spec is its only source of project knowledge. When writing a
spec, include any rules the executor needs to make correct edits without guessing: coding
conventions, architectural constraints (e.g. "tests must not hit the production database",
"all public APIs must have integration tests"), dependency choices, and style expectations
relevant to this task. Embed key
signatures or data structures the executor will work with. You decide how much to include —
the goal is executor efficiency: the executor should follow the spec without re-investigating
the codebase. A one-line fix needs almost none; a multi-file refactor needs more.
Specs are implementation steps only. The executor ensures its code compiles — do NOT include
test-running steps. You run tests via verify_subtask after the executor completes.
""".trimIndent()
}
