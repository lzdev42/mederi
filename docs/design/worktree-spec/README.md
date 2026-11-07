# Worktree 实施交接包

版本：WT-2026-11-02.1；本文保留原始实施规范。

**当前阶段：Gemini 实施及 GLM 两轮整改后，第三轮审查仍不通过。当前唯一执行入口是 [ROUND4-REPAIR.md](ROUND4-REPAIR.md)，实施细节见 [round4-spec.md](round4-spec.md)。** 本轮围绕统一不变量修复，不再按历史 RF/R 编号做孤立补丁。原契约/算法/UI规范仍适用，不从零重建、不重录baseline。历史入口 GLM-REPAIR.md 保留供追溯。

上一轮执行者：Gemini 3.8 Flash；不要求调用另一个AI做安全批准或替它完成设计。

用户目标：由执行模型按本包完成 Worktree 产品功能，减少设计者反复参与；不是只添加 `git worktree add`。实施者无需重新选架构，但必须核对源码位置、完成测试、报告实际证据。

## 阅读顺序与约束

1. 仓库 AGENTS.md；docs/architecture/00-overview.md、01-core.md、02-app-shared.md、04-flows.md 的对应章节。
2. 本文件 → [冻结决策与契约](01-contract.md) → [Git、快照与生命周期算法](02-lifecycle.md) → [运行时与界面](03-runtime-ui.md)。
3. 按 [逐任务计划](04-tasks.md) 实施，每次只加载当前任务引用的章节和目标源码，不反复通读全仓。
4. [验收清单](acceptance.json) 为机器读取的最终用例集合；执行 `python3 scripts/verify-worktree-delivery.py --docs-only` 校验交接包。产品完成后用 `--run` 和 `--evidence-dir` 进行验收。

本目录优先于旧 `../worktree-plan.md` 的建议、拟定与待决项；旧文档作为背景，不作为第二份执行规范。仓库规则仍适用。任何规范冲突或已冻结算法无法实现：停止对应任务，记录 `SPEC_CONFLICT` 的最小复现及候选修正，继续不依赖它的任务；不得自行删掉能力或降低验收要求。

## “完整对标”的具体含义

验收基线固定为 2026-11-02 核对的 Codex **公开 Worktree 工作流**：

- Local/Worktree、分支起点、当前修改、detached 起步与建分支；关联副本复用、双向 Handoff。
- 受管根/保留限额（默认15）/归档清理/快照恢复，运行中与固定对话保护。
- 初始化环境、平台覆盖、常用动作、包含规则，以及手机遥控宿主工作副本。

来源：[Worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)、[Local environments](https://learn.chatgpt.com/docs/environments/local-environment)。

以上官方摘要不是对未公开内部实现的推测。下文算法是 Mederi 的具体实现契约；官方未规定的 dirty 冲突、取消、异常恢复按下文执行，并在人工对照记录实际差异。完整 Git 提交/推送/PR 产品、定时任务调度不在 Worktree 范围；提供建分支、Git 状态、终端/打开工作区与当前已有 Diff，不冒充已经提供全部 Git 工作台。

“完成”必须同时满足：

1. 所有 WT-* 任务完成，无 stub/TODO/no-op/静默 Local fallback。
2. 验收 JSON 中全部用例有**本次代码**的成功报告，不能 disabled、skip 或以编译代替行为。
3. macOS/Linux 原生权限分别有对应主机的 required 运行证据；缺平台是未验收，不是通过。
4. 桌面实机和320dp手机遥控实机/模拟设备验收有证据，覆盖创建、交接、归档、恢复、断线重连。运行 APP/server 只走 idea-mcp；没有该能力时报告待环境验证。
5. 每个官方基线能力都有测试或人工步骤映射；没有未解释差异。只有编译通过或模型口头声称“100%”不算完成。

测试与对照能建立可审计的完成标准，不能从文档本身推导任意模型绝不犯错。执行结果未经这些检查，不得宣称完全对标。

## 给 Gemini 的一次性交接提示词（上一轮历史）

> 在 Mederi 仓库实施 `docs/design/worktree-spec/README.md` WT-2026-11-02.1。遵守 AGENTS.md，先运行交接包 docs-only 检查，按04-tasks逐项执行。01–03是冻结规范；不自行改语义，不缩范围，不把缺平台/失败/skip写成PASS。先记当前 git状态和回归基线，保留已有用户修改；只在temp自建仓库验证Git，不操作真实项目来试交接/清理。每任务提交实现、测试和架构文档，运行该任务指定测试；失败先修实现，规范不可满足则记录SPEC_CONFLICT。不要每个小任务向用户问批准；用户已授权实施整包，具体用户数据破坏性操作仍遵守仓库规则。只对外报告真实证据。全部完成执行最终验收脚本，交付任务状态、测试报告、平台/人工证据和残余差异列表；缺任何一项就写未完成，不自行豁免。不要为了通过检查修改本交接包、验收manifest或验收脚本；确有规范问题需单独报告。使用当前可用执行模型，不依赖特定供应商的高级推理/自动安全审查能力。

## 交付与续作记录

在 `docs/design/worktree-spec/delivery.md` 记录：规范版本、起始HEAD、已修改文件摘要、WT任务状态、实际测试命令/报告位置、每次失败根因、native/manual证据、偏差。任务结束立即更新，续作先读此文件，避免遗忘已完成工作。此文件由实施者建立，不能提前填PASS。

检查脚本只验证报告/清单结构，不是产品测试的替代品；测试必须由真实Git仓库/真实调用链断言。测试代码不得读本清单后直接生成成功输出。


人工记录模板为 [manual-template.json](manual-template.json)。实施者复制到证据目录 `manual.json`，将sourceFingerprint填为同目录regular/receipt.json中的值，逐项完成观察和真实截图/日志，再将对应PENDING改PASS；未进行的项目保持PENDING。模板不是已通过记录，不能仅批量替换状态。最终检查会拒绝缺失项和未解决差异。

交接包校验（本轮已执行）：`python3 scripts/verify-worktree-delivery.py --docs-only`；验收工具自身测试：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p test_verify_worktree_delivery.py -v`。这些只验证本包/工具，不代表113个产品行为用例已经实现或通过。
