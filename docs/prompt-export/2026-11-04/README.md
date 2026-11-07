# Mederi 代码层提示词提取与问题记录

提取日期：2026-11-04。`current-*`、`source-*`、templates/tool 源码及 diff 为首次提取时的历史快照，包含当时尚未提交的改动，保留供对照；它们不代表随后修改后的最新提示词。最新正文见 [updated-turn-prompts.md](updated-turn-prompts.md)。AGENTS.md 未改。

## 阅读入口

| 文件 | 内容 |
| --- | --- |
| [current-assembled.md](current-assembled.md) | 编译当前原始 Kotlin 提示词构建器并调用所得的正文：主代理 APPROVAL/AUTONOMOUS × 浏览器启用/禁用、EXECUTOR、RESEARCHER、浏览器 Operator、Brain 判定与报告 |
| [current-dynamic-examples.md](current-dynamic-examples.md) | 调用原始构建器生成 skills、项目规则、plan/todo、用户插话、浏览器分析输入的包装示例；方括号内为明确占位值 |
| [auxiliary-prompts.md](auxiliary-prompts.md) | 压缩总结、AGENTS 生成和自动命名的独立正文，便于直接阅读 |
| [templates-source.md](templates-source.md) | 中央 prompt 目录全部六个文件，以及压缩总结、自动命名、AGENTS 生成、环境、子代理输入/报告、浏览器状态等辅助源码完整原文 |
| [tool-sources.md](tool-sources.md) | 14 个生产工具源码文件：工具描述、参数注解、动态描述和返回结果内的提示全部保留，未把工具执行逻辑误称为提示词正文 |
| [before-assembled.md](before-assembled.md) | 简化前 `f35a574^` 的构建器实际生成正文，不是根据当前版倒推的旧版本 |
| [before-dynamic-examples.md](before-dynamic-examples.md) | 简化前构建器生成的动态包装示例 |
| [simplification.diff](simplification.diff) | 简化提交 `f35a574^ → f35a574` 的 prompt 目录逐行差异 |
| [since-simplification.diff](since-simplification.diff) | `f35a574 → 当前工作区` 的后续差异，避免把之后的更改归因于那次简化 |
| [source-snapshot.zip](source-snapshot.zip) | 当前相关生产源码完整快照，以及简化前中央 prompt 目录源码 |
| [source-manifest.json](source-manifest.json) | 38 个当前源码文件的路径、字节数与 SHA-256，便于核对原文是否被提取过程改写 |

`assembled` 保留原始语言和顺序，不翻译、不改写、不删减。外围一级标题用于区分不同角色/配置，不属于发送给模型的正文。当前主代理四种组合有重复，这是实际共用骨架。旧版 build 没有 hasBrowser 参数，故旧版只有两种主代理模式；没有伪造旧版参数。

浏览器 `maxSteps` 示例使用 100，不代表运行时固定为 100。动态示例不填入真实会话、项目规则或 skill 内容。中央模板的所有分支及插值语义仍可在完整源文件中核对。压缩、自动命名和 AGENTS 生成原文在 templates-source 的对应 Kotlin 文件中，未遗漏这些散落在 prompt 目录之外的指令。

## 实际挂载链路

主代理：`SystemPrompts.build(agentMode, hasBrowser)` → `withSkillsAndActive` → `withProjectRules` → `dynamicSuffix`（活跃 plan 与 todo 互斥）→ `EnvironmentInfoProvider.build`。调用点为 `core/.../koog/TurnExecutor.kt`。

执行器/研究员：`SystemPrompts.forSubagent(role)`，不是主代理提示词的删减视图。执行器可继承 skills；研究员不继承。两者均不自动继承父会话/项目 AGENTS.md，需要父代理在 briefing/spec 中转交约束。`SubagentRunnerImpl` 还负责组装任务输入与报告摘要。

浏览器独立使用 `BrowserPrompt` / `BrowserBrainPrompt`；每步的页面快照、任务、记忆与历史由 `BrowserOperator` 组装。工具 schema、工具结果也会影响行为，已单列源码。外部 MCP 服务的 schema、用户配置的真实 AGENTS/skill/recipe、对话历史及附件不是代码里固定的提示词，不从用户配置目录读取，也不伪造。

## 首次提取时的问题与推断边界

1. **简化确实发生过，但不能仅凭删行数证明退化因果。** 提交 `f35a574` 在四个主要提示词文件中删除 696 行、增加 280 行。`CodingPrompts.PRINCIPLES` 从 21 行左右的逐项约束缩成 7 行左右的一段；完整前后文本见差异文件。旧版同样没有完整的故障诊断步骤，不能声称“简化时删掉了原本完整的根因流程”。
2. **“修根因”仍在，但主要是原则声明。** 当前 `CodingPrompts.kt` 明确写着 `Fix root causes, never suppress symptoms`，执行器也有相似要求。当前没有在这一处建立清楚的诊断步骤：复现用户现象、追踪错误值/状态的产生与消费、验证因果假设、修复所有相关调用点、用能捕获原问题的行为检查验收。它可能解释“知道口号却只改表面”，但仍需真实失败会话或行为评估验证。
3. **提示词与工具契约存在确定性冲突。** 当前 `SystemPrompts.TOOL_GUIDELINES` 和研究员指南写 `read_file` 的 offset 从 0 开始；`FileSystemTools.ReadFileArgs`、工具 description 及实现实际按 1 开始。这是可直接核对的陈旧指令，不只是措辞偏好。本次先保留原文，未混入另一轮提示词调整。
4. **现有提示词测试不能证明根因分析能力。** `PromptCompositionTest` 主要断言角色段和若干关键字存在；例如根因纪律只查 `Fix root causes, never suppress symptoms`。关键字通过不意味着模型能追到真实故障链。要判断提示词能力，需要覆盖实际失败场景、检查调查证据和修复结果。
5. **模型编辑问题有独立且已定位的 UI 根因。** `ProviderModelDialogs` 曾根据 FETCHED 禁止编辑，`ProviderDetailWorkspace` 保存时又只提交图片开关；Core 的 `updateUserModel` 实际接受全字段。文档仍称 Core 会拒绝修改，与代码不符。本次移除两处 UI 门禁并同步事实，验证缺失元数据的拉取模型可输入并完整提交。

首次提取保全了提示词原文及历史差异，并修复模型编辑问题；随后用户确认了下述提示词修改。原快照保留，不覆盖历史原文。

## 提取核验

原始 Kotlin 提示词构建器以本地缓存的 Kotlin 2.4.21 编译器独立编译并实际调用；不启动 APP/server，不请求 LLM。当前正文包含 9 个角色/配置区段，简化前包含 7 个。38 个当前源码文件的 SHA-256 与 ZIP 条目逐项一致，阅读索引内所有链接目标均存在。源码原文与正文生成不是按人工理解重新拼写。

模型编辑修复验证：`ProviderFormsDeliveryTest` 7 项通过；`ProviderCatalogViewModelTest.fetchedModelEditsSubmitAllMetadataToCoreWithExactIds` 1 项通过。覆盖缺元数据的 FETCHED 模型实际表单输入、完整保存回调与到 AiCore 的精确 ID/全字段转发。主代码 JVM 编译通过；未启动 APP/server。

## 用户确认后的提示词修改

- [updated-turn-prompts.md](updated-turn-prompts.md)：从测试通过的编译类实际调用构建器，导出主代理两种模式及 EXECUTOR/RESEARCHER，共四种配置。
- 新增共享的证据与因果推理引导：可靠证据支持原因，必要时实验检验假设，方案解释如何消除问题机制，验证原始失败及相关行为。不规定调查量、读取次数或替代解释配额。
- 加强实现方案与复杂计划详情；spec 派发前核对真实来源、前序结果、调用点、边界语义和验收方法。执行器核对源码并报告事实冲突，主代理核查关键报告证据，研究员明确未找到/未证实和搜索局限。
- 通用 turn 提示词移除浏览器文字与 hasBrowser 分支，两处 TurnExecutor 调用同步；能力由实际注册工具声明。专用浏览器任务模块的提示词不在本次通用提示词修改范围内。
- 修正 read_file 的行号说明为与实现一致的 1-based。
- 验证：core JVM 编译及 30 项针对性测试通过，覆盖角色挂载、通用提示词无浏览器内容、缓存拼接顺序、补丁注册与读文件分页。这验证装配和工具契约，不是模型行为质量的实测结论；尚未用真实失败任务做模型行为对比。
