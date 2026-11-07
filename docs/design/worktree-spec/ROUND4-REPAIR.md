# Worktree 第四轮：按系统不变量整改

版本 **WT-REPAIR-2026-11-03.2**。这是当前唯一实施入口。执行者可以是 GLM 5.2，也可以是用户当前选择的开发模型；不需要聊天上下文或另一模型替你做安全批准。

这是一个新的、边界明确的整改任务：完成现有 Worktree 的正确性和已承诺 UI，**不增加新产品范围，不重写整个 Mederi，不开发通用自动审查/通用测试平台**。不要继续将历史 R01–R15 逐条做最短补丁后宣称完成。

## 背景和实际起点

Mederi 是 Kotlin/Koog 应用框架。core 是 JVM 宿主引擎；app/shared 是 Compose KMP UI、AiCore 契约和两侧桥。desktop 进程内调用 core；手机/wasm 经 ServerAiCore 的 REST/SSE 遥控同一宿主。业务不得挂 desktop main。

用户要求 Worktree 对标原 spec 固定的 Codex 公开工作流：创建/隔离、Local↔Worktree、分支、环境动作、固定/归档/恢复、宿主限额/根目录、窄屏遥控。权限是项目级 NEEDS_APPROVAL/FULL_ACCESS；无 AI 安全判定，计划自动批准与执行权限独立。原项目身份和 `.mederi` 产物根不能因 checkout 改变。

Gemini 第一轮实施；GLM 随后两轮整改。第三轮审查源码指纹为 `2ea0cc2de3215d3547a5ef6d49628f83119876549aca244f8ab8617089123ccb`。原 19 项报告通过，上一轮独立五项现在也确实通过；但七个新独立回归全部失败于行为断言，0 errors/skip。详见 [第三轮审查](review-2026-11-03-round3.md)。这些是已确认的当前实现缺陷，不是可豁免 baseline。

仓库有大量未提交和未跟踪的沙盒/Worktree 改动，保留全部已有工作；不要 reset/clean/整包覆盖。禁止删真实数据库、操作真实项目测试 Handoff/归档或强删。所有 fixture/scratch 位于仓库 temp。APP/server 必须 idea-mcp；Gradle 测试/编译可以命令行。本任务不需要碰真实用户数据。

## 阅读顺序与交接组成

1. 根 AGENTS.md；按其要求先读 docs/architecture 对应工作区/执行/桥层/UI 章节。架构文字可能过度声称完成，必须核验本文标出的生产接线。
2. 本文件 → [本轮实施 spec](round4-spec.md) → [第三轮审查](review-2026-11-03-round3.md)。
3. 当前任务涉及的原 `01-contract.md`、`02-lifecycle.md`、`03-runtime-ui.md`。原 README/GLM-REPAIR/04-tasks 是历史入口，原冻结行为继续有效；不重复原 baseline，不重新从零开发。
4. [round4-acceptance.json](round4-acceptance.json)：32 项 = 七个最新复现 + 五个上轮回归 + 二十个必须新增的生产不变量测试。原113项和19项同时保留，不能替换。

优先级：用户当前指令/AGENTS → 本轮不变量及原冻结行为 → 当前源码事实 → 历史 delivery 声明。这里不是批准弱化原 spec；如真正自相矛盾，记录 SPEC_CONFLICT/最小复现/候选修订，继续独立部分，不静默缩范围。

## 本轮的工作原则

- 先画清楚真实调用链与规则归属，再改代码。不要在每个调用点复制另一套权限、类型恢复或恢复完成判断。
- 使用现有 Manager/Store/Driver/ExecutionPermissions/ProcessRegistry 架构，只抽出收拢重复危险逻辑所需的窄内部服务。新接口/字段/流程同步 architecture 和 AiCore 四处+Mock，不引入万能 coordinator/framework。
- 测试模型只可替代外部 LLM、网络、浏览器等不可控依赖。被测 Manager、Store事务、Resolver、ToolFactory、桥层水合、UI实际入口不能由 permissive Mock 替代后声称生产已通。
- 不把命令 exit0、方法名存在、编译成功当行为证据；不把返回 Result 成功当磁盘/数据库一定正确。
- 当前源码语义与测试断言冲突时先验规范。已知例子：旧 subagentAndVerify 测试未调用 verify，产物断言把根放 managed/.mederi，与原项目稳定产物根相反；应改为真实行为断言，而不是保留错断言。
- 不能修改原验收 manifest/门禁/baseline 或本文来逃避失败。可以为真实 API 重构更新 fixture；不得改名字、删断言、跳过、改成假工具/假 Store 结果。

## 第一件事：保留证据并提升七个复现

记录起始 HEAD、git status、已有 diff 摘要；备份当前 temp/worktree-evidence/delivery 的 regular/native 到另一个 temp 路径。原 baseline 只读，不重录、不把本轮七项登记为既有失败。

七项已保存为正式资产，不再依赖审查者 scratch：

```bash
python3 scripts/verify-worktree-round4.py --docs-only
./gradlew -I scripts/worktree-review-round4/init.gradle :core:jvmTest \
  --tests xyz.mederi.workspace.WorkspaceProductionThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceLifecycleThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceJournalThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceRollbackThirdReviewTest --console=plain
```

审查时预期七项失败。把 `scripts/worktree-review-round4/src/xyz/mederi/workspace/` 四个类一起提升到 core 常规 jvmTest，保留名字与断言；不要只靠 -I 临时注入才能运行。init 在四个都提升后不再注入；只提升部分会明确报错。旧两类五项已在常规源集，不重复复制。

## 七个任务与依赖

按下面顺序逐项推进。每项都是实现+行为测试+对应架构文档，不是最后统一补测试。N02依赖N01；N03可独立调研但避免共改Manager；N04依赖N02/N03；N05依赖稳定N01/N04；N06依赖N05；N07最后。若委托子代理，只分配不会同时写同文件的部分，并由主执行者验证跨模块装配，不能以“子代理说通过”代替结果。

| 任务 | 修改目标及责任 | 独立验收 |
|---|---|---|
| N01 执行身份与权限投影 | T01/T08/T10/T11；原配置只读；主/子代理/verify/setup/action/handoff 共用身份和审批；失败准备不放行 | 旧两权限复现、新黑名单持久化复现；新 Core 用例1–6、15 |
| N02 进程与管理租约 | T03/T04；先授权/取租约后启动；后台至进程结束；管理所有权显式且不可伪造 | 新后台拒绝与动作busy两复现；Core用例7–8 |
| N03 安全文件状态原语 | T05/T07；完整NOFOLLOW状态与类型转换；原位回滚不写穿/不删未知 | 新ignored及rollback两复现；Core用例9–10 |
| N04 日志、事务、恢复 | T02/T06/T14；写前意图、持久后像、事务提交、成功历史不重放、完整恢复判定 | 新历史和日志两复现；Core用例11–14，真实SQLite重开 |
| N05 真实状态投影 | T09；初始快照/observe/REST/SSE/重连同源，RECOVERY_REQUIRED可见可操作 | Shared用例1、3 |
| N06 UI完整入口和异步归属 | T12/T13；迁移/环境动作/根与限额/建分支/恢复；owner+request代次；手机可用 | Shared用例2、4、5；真实Compose点击和尺寸 |
| N07 端到端验收与交付 | 修旧测试的伪生产覆盖、有限逆向验证、文档与证据 | 原113+19、本轮32、四平台；native/manual另有真实凭据 |

上表 Core/Shared 用例编号对应 round4-spec 的两张行为测试表。不要只实现表中方法名，让内部永远 assertTrue；必须完成对应动作、故障和后果断言。

每完成一项，在原 `delivery.md` 追加“第四轮 Nxx”实际记录：生产调用链/改动文件/断言行为/报告/失败根因/剩余项。保留历史，纠正过时结论，不批量把历史PASS当本轮完成。该文件已被原指纹脚本排除，可在最终测试后补记录。

## 本轮特别禁止的五种捷径

1. 在运行 action/setup 时 updateSettings 写回重映射黑名单。
2. 看到 WORKSPACE_BUSY 就继续无租约运行，或先启动进程再检查是否能执行。
3. 恢复时遍历 SUCCEEDED 历史把会话改成每条记录的目标。
4. 文件/状态信息不够时标 READY；用 git status 或 FILE hash 子集代替完整 PREIMAGE/index/ref 验证。
5. Mock 给 snapshot 注入生产桥没有的 workspaceInfo，再以 UI 测试绿灯声称真实入口接通。

## 验收与完成语义

任务内只跑相关测试；最终运行：

```bash
# 常规自动化阶段：全量core/shared、四平台编译、原113+19和本轮32报告
python3 scripts/verify-worktree-round4.py --run --evidence-dir temp/worktree-evidence/delivery

# 在各自平台、同一最终源码上运行。没有Linux不能伪造Linux收据。
python3 scripts/verify-worktree-delivery.py --native macos --evidence-dir temp/worktree-evidence/delivery
python3 scripts/verify-worktree-delivery.py --native linux --evidence-dir temp/worktree-evidence/delivery

# 完成原manual-template的真实桌面/手机/对照后，保留全部旧门禁
python3 scripts/verify-worktree-round4.py --evidence-dir temp/worktree-evidence/delivery
```

新门禁只加要求，复用原门禁的源码指纹、XML校验、baseline差异、113项、平台/人工证据。它检查报告是否存在/通过，**不能证明测试质量或业务语义完整**；仍需交付 round4-spec 要求的调用链映射、故障矩阵与三项逆向验证，由审查者复核。

普通阶段通过只能写“常规自动化通过”；缺Linux/idea-mcp/人工证据时明确写最终未验收。不要为缺外部平台提前停下可做的源码、常规测试、UI路由修复。APP/server无idea-mcp就不直接bash启动。

最终交付必须提供：N01–N07状态、第三轮T01–T14逐项修复位置与证据、七项从FAIL到PASS、二十项新增实际行为、旧113/19与baseline差异、源码指纹、native/manual缺口、仍需审查内容。任何未实施UI/未接生产入口/未证明的恢复场景都如实列出，不称“全部可实施完成”。

用户已授权本轮实施，无需每个常规任务再问批准；真实用户数据破坏性操作仍按AGENTS，任务本身不需要它。
