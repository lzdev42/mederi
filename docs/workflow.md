# 工作流

> 来源：`docs/sandbox-plan.md`（沙箱）、`AGENTS.md §5.5/§5.6`（执行沙盒与分诊流程）、
> core 实现（`CommandSandbox` / `PlanApprovalRequester` / `PlanTools` / `SpawnAgentTool`）。

## 〇、分诊流程（Triage Flow，2026-09 定稿）

主代理是唯一分诊台，对每个用户请求自主判断走哪条路径（信任 AI 判断，无代码门禁）：

```
用户请求 → 主代理判断：
├─【纯读】问答/讨论/分析 → 直接读文件回答；读不够深 → spawn_researcher → 报告 → 据此回答
├─【小改动】已知根因/简单逻辑/几行代码 → 直接 edit_file/write_file（无 plan；apply_patch 已注销）
└─【复杂改动】多文件/逻辑变化/需用户决策 → 下面的 Plan Loop（阶段 1-5）
```

子代理双角色：**Executor**（spawn_agent 派出，照 spec 执行，无 plan/spawn/verify/ask_user）、
**Researcher**（spawn_researcher 派出，只读 read_file/list_directory，深度调研出完整报告，绝不写）。

## 一、项目与目录

```
Project { directories: [主目录, 参考目录...] }
```

- **`directories.first()` = 主目录**：承载 `.mederi/` 工作区（plans、notebook、backups）、
  shell 命令 cwd、`list_directory` 空参默认值、相对路径解析首选
- 其余目录 **平等读写**：写路径白名单 = 所有 directories（含 `.mederi/`）
- 用户不能在设置页关沙箱、不能换主目录、不能删到只剩零个目录

## 二、Agent 模式（AgentMode）

```
APPROVAL   → 计划必须用户批准，批准后才执行
AUTONOMOUS → 计划自动批准（自己批准自己），立即执行
```

**两模式工具集完全一致**（`ToolFactory.kt`）。唯一区别：

| | APPROVAL | AUTONOMOUS |
|---|---|---|
| create_plan 后 | 挂起 → 等用户批准 | 立即批准，继续执行 |

## 三、写入边界（跨平台，代码强制）

```
写白名单 = 项目 directories ∪ .mederi/ ∪ 系统临时目录与 TMPDIR
        ∪ 构建缓存（~/.gradle ~/.m2 ~/.cache ~/.konan ~/Library/Caches ~/Library/Java）
        ∪ 全局白名单（设置页配置，项目外额外可写路径）
读全盘放行
```

| 层 | 约束 | 生效 |
|---|---|---|
| write_file/edit_file | 路径必须在白名单内（`resolveForWrite` containment 校验）；同文件并发写硬拒绝（`FileWriteRegistry`） | 全平台永远 |
| execute_command | macOS Seatbelt 内核级拒绝 / Linux bubblewrap 挂载 / Windows 降级警告 | 按平台尽力 |

> 是否可以写、要不要建计划，由主代理按 Triage Flow 分诊判断（见 `AGENTS.md §5.6`）。

## 四、完整工作流（按顺序）

### 阶段 0：准备

```
1. 用户选择项目（决定了 directories）
2. 用户选择 Agent 模式（APPROVAL / AUTONOMOUS）
3. 用户发送消息
```

### 阶段 1：计划（Plan = WHAT）

```
4. 主代理读代码、讨论需求、问决策（ask_user，最多 3 问）
5. 主代理调 create_plan（写入 .mederi/plans/{planId}.md，Part 1 人读：

   - Business Logic（连贯散文：入口 → 调用链 → 前后行为对比）
   - Scope（In / Out；Out 是过度设计防线）
   - Key Decisions（≤10 条，先验）
   - Changes（[MODIFY]/[NEW]/[DELETE] 文件清单；[NEW] 必须有理由）
   - Data & Parameters（可选）
   - Risks（≤5 条，先验）
   - Success Criteria / Verification

   以及骨架子任务：name + brief intent(1-3 句) + targetFiles + verification + dependsOn）

   注意：不含深度实现 spec（变量名、函数签名、数据结构等——这些在阶段 3）。
   **拆小可验证**：必须拆成多个小的、可独立验证的子任务，每个子任务 = 一个 spec + 一个
   verification（具体命令 + 预期结果 + 通过标准），绝不打包成含混的大任务。
```

### 阶段 2：批准（仅 APPROVAL 模式）

```
6. 工具挂起，发 PLAN_APPROVAL_REQUESTED 事件
7. UI 显示紧凑批准卡片（标题 + 一行描述 + Proceed 按钮）
8. 用户点 Proceed → 工具恢复，计划状态改为 APPROVED
9. (AUTONOMOUS：create_plan 返回时已自动批准，无挂起)
```

**被拒时**：用户继续聊天 → AI 修订计划 → 新的 create_plan 自动取代旧请求（supersede）。

### 阶段 3：Spec 派生（HOW）

```
10. 对于每个子任务（按 dependsOn 顺序；相互独立无依赖的可以并行做）：
    a. 主代理先读真实代码（前序子任务的产出、diff、当前文件状态）
    b. 调 generate_spec(planId, subtaskIndex, spec)：

       - 写入 Subtask.spec（spawn_agent 读取的真理源，同索引再次调用即覆盖；批准时用户看到的 brief 永不被覆盖）
       - 子任务状态 COMPLETED → 拒绝（已完成的不能回写 spec）
       - 记 notebook 审计
       - 发 PLAN_PROGRESS(planId, action=spec-generated, subtaskIndex)

     c. 调 spawn_agent(planId, subtaskIndex)：

        - spec 不存在（Subtask.spec 为空）→ 拒绝，引导先 generate_spec
        - 子代理继承主代理配置（模型/推理等级），AUTONOMOUS，EXECUTOR 角色全套工具
        - 硬绑定：子代理执行的 spec = PlanStore 里存储的 Subtask.spec（零漂移）
        - 并行：同一消息发多个 spawn_agent，相互独立的子任务一起跑（工具并行、无上限）
```

**并行执行规则（2026-09）**：工具调用同一消息并行（`nodeExecuteTools(parallel=true)`），
无并发上限，由 AI 调度。`create_plan` 单独发；**禁止同消息混发 generate_spec 与 spawn_agent**
（并行无序，spawn 可能读到未写入的 spec）——先为所有独立子任务生成 spec，再一起 spawn。
同文件并发写已由 `FileWriteRegistry` 代码级硬拒绝（占用即 Error，AI 下轮重试）；不得重复执行同一命令仍靠 AI 自律。独立子任务全部 spawn 返回后再逐个 verify。

### 阶段 4：验证与修正

```
11. 主代理调 verify_subtask(planId, subtaskIndex, status, evidence)：

    PASS → 下一步（下一子任务 / 全部完成）
    PARTIAL 或 FAIL 且 执行错（子代理产出偏离验证标准）→
        converge_plan(planId, remediationSubtasks) → append 补救子任务 → 重执行
    PARTIAL 或 FAIL 且 spec 错（spec 本身与现实矛盾）→
        重新 generate_spec(planId, subtaskIndex, newSpec) → 覆盖旧 spec → 重执行
```

### 阶段 5：完成

```
12. 所有子任务 COMPLETED
13. Plan 自动归档（.mederi/plans/ → .mederi/plans-done/，连带 spec 文档）
14. 主代理写 notebook 总结
```

## 五、代码级强制清单

| 机制 | 作用 | 位置 |
|---|---|---|
| `resolveForWrite` | 写路径必须在项目 directories 内（全平台） | `FileSystemTools.kt` |
| `CommandSandbox` | execute_command 的 OS 写沙箱（macOS Seatbelt / Linux bubblewrap / Windows 降级） | `CommandSandbox.kt` |
| `SpawnAgentTool` spec 校验 | spawn 前检查存储的 spec 存在（Subtask.spec 非空） | `SpawnAgentTool.kt` |
| `GenerateSpecTool.targetFiles 白名单` | spec 引用的文件 ⊆ 已批准的 targetFiles，越界拒绝 | `PlanTools.kt` |
| `PlanApprovalRequester` | APPROVAL 模式下 create_plan 挂起等用户批准 | `PlanApprovalRequester.kt` |

## 六、关键文件一览

| 文件 | 职责 |
|---|---|
| `core/.../plan/Plan.kt` | Plan 模型（含 businessLogic/dataAndParams/骨架 subtasks） |
| `core/.../plan/PlanStore.kt` | Plan 文档读写 + Spec 文档读写 + 归档 |
| `core/.../plan/PlanApprovalRequester.kt` | APPROVAL 模式挂起/恢复 |
| `core/.../tools/PlanTools.kt` | create_plan(Plan) / generate_spec(Spec 派生) / converge_plan(执行补救) 工具 |
| `core/.../tools/subagent/SpawnAgentTool.kt` | spawn_agent（硬绑定存储 spec） |
| `core/.../tools/VerifyTools.kt` | verify_subtask（三分支：PASS/执行错/spec 错） |
| `core/.../tools/FileSystemTools.kt` | resolveForRead（全盘可读）/ resolveForWrite（写白名单） |
| `core/.../tools/ShellTools.kt` | execute_command（OS 沙箱包裹） |
| `core/.../tools/sandbox/CommandSandbox.kt` | 三平台沙箱实现 + shell 探测链 + 环境信息 |
| `core/.../tools/subagent/SpawnAgentTool.kt` | spawn_agent（EXECUTOR）+ spawn_researcher（RESEARCHER 只读） |
| `core/.../domain/model/SubagentRole.kt` | 子代理双角色枚举（EXECUTOR / RESEARCHER） |
| `core/.../prompt/SystemPrompts.kt` | 提示词骨架（身份/原则/工具清单/分诊工作流） |
| `core/.../prompt/PromptGuides.kt` | 提示词教程素材（SANDBOX_USAGE 等） |
| `app/.../contract/SandboxHooks.kt` | 设置页沙盒状态接口 |
| `app/.../ui/settings/SettingsScreen.kt` | 设置页（含沙盒状态卡 + 全局白名单编辑器） |