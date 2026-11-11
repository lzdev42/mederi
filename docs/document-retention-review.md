# 仓库文档留存盘点

2026-11-08。初次盘点覆盖当时 Git 跟踪的 `doc/`、`docs/` 117 个文件，另检查了根目录、模块文档和源码、脚本、CI 的引用。

## 已清理

- 首次盘点删去一份被次日报告覆盖的工具调用冒烟记录和一份无复现入口的一次性诊断时间线。
- 按用户确认，删除旧沙盒与授权分析、早期 UI 对接地图与实施计划、`doc/dev_plan/` 历史开发计划、供应商数据清洗调研与计划。
- 删除工作台主计划、设置主计划、UI 原型与设计系统 HTML、历史工作记录、InkCompose 审计和旧 backlog。
- 删除 Codex Worktree 计划及旧规格目录的 Markdown，包括多轮复审和历史交付记录。三份机器清单与人工对照模板迁到 [Worktree 验收资产](../scripts/worktree-acceptance/README.md)，现行行为继续以架构文档为入口。

## 保留范围

| 文件或范围 | 留存用途 |
|---|---|
| [Core API 快速参考](../doc/api.md)、[Core 使用手册](../doc/mederi文档.md)、[旧审批规格](../doc/审批功能对接规格.md) | 使用示例与旧契约背景，是否继续维护为公开用法需要另行核对 |
| [旧测试记录](../doc/dev_test/) | 保留现有历史结果，包括最终文本为空的流式测试失败 |
| [工作台组件盘点](design/conversation-workspace-ui-inventory.md)、[UI 开发标准](design/standard/README.md)、各批 `workspace-*-delivery.md` 与 `settings-*-delivery.md` | 现行开发入口及各批交付证据 |
| [落地页](../doc/mederi_landing_page.html)、[候选视觉稿](../doc/design_concepts/candidate_1_abyssal_gold_sea.jpg) | 品牌素材 |
| [提示词导出包](prompt-export/2026-11-04/README.md) | 保留提示词前后差异和当时尚未提交的源码 |
| [macOS/Linux 沙盒交付](sandbox-permissions-delivery.md)、[Windows 沙盒交接](windows-sandbox/README.md) | 已有平台的实现证据和 Windows 后端实施依据 |
| [架构索引](architecture/README.md)、AGENTS.md、根 README 与翻译、模块文档、测试 fixture | 开发规则、现行行为、公开入口和测试输入 |
| [Todo 系统规格](todo-system-plan.md)、[工作流说明](workflow.md)、[重试调研](retry-delay-research.md)、[ChatGPT 授权计划](design/openai-chatgpt-authorization-plan.md) 与 [供应商布局计划](design/provider-settings-layout-plan.md) | 当前实现的规则、取舍及验收依据 |
