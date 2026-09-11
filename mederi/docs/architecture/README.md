# Mederi 架构速查文档（AI 专用）

> **用途**：AI 排查问题时**先查本目录，不要直接通读/全量搜索源码**。本目录由全量代码结构化提取生成，包含类图、结构图、流程图、时序图与穷尽式字段/签名清单。
>
> **排查工作流**：① 按问题域从下方索引选定篇章 → ② 用文档内的类图/流程图/签名清单定位相关类及其 `文件路径` → ③ **只打开这几个文件读相关段落**。文档能直接回答的问题（结构、字段、谁调谁、事件流向、端点、表结构）引用文档即可，不再翻源码确认；只有文档未覆盖或疑似与代码不一致的实现细节，才去读文档标注的那几个源码文件。
>
> **维护义务**：结构发生改动时按需更新本目录（规则见 AGENTS.md §0）。本文档描述"结构是什么"，AGENTS.md 描述"规则是什么"，两者互补。

## 文档索引（按问题类型查）

| 你在查什么 | 看哪篇 |
|---|---|
| 项目分几个模块、依赖方向、宿主入口、平台注入矩阵、硬性规则索引 | [00-overview.md](./00-overview.md) |
| core 模块：DI 装配、领域模型字段、API/Manager/Store 方法签名、Koog 执行引擎、工具系统、Plan、Provider/Koog 适配、MCP、事件、提示词、存储表结构 | [01-core.md](./01-core.md) |
| app/shared 与 server：AiCore 契约签名、契约模型字段、三实现（MederiAiCore/ServerAiCore/MockAiCore）、RemoteServer 路由表、AppState/ViewModel、UI 组合树、平台入口、preferences | [02-app-shared.md](./02-app-shared.md) |
| inkcompose：公共 API、Markdown 解析/渲染管线、LaTeX、语法高亮、Mermaid 图表、竖排文字、平台 actual 矩阵 | [03-inkcompose.md](./03-inkcompose.md) |
| 核心流程图 + 时序图：发消息全链路、Turn 执行、事件→快照聚合、Plan Loop、上下文压缩、ask_user、审批、子代理、回滚、自动改名、遥控启动、密码门 | [04-flows.md](./04-flows.md) |
| 业务规则（分诊/沙盒/推理档位/模型元数据所有权/契约同步） | 仓库根 `mederi/AGENTS.md` |
| 专项设计文档 | `docs/sandbox-plan.md`、`docs/todo-system-plan.md`、`docs/e2e-test.md`、`docs/workflow.md`、`docs/backlog.md` |

## 30 秒心智模型

```
Mederi = Koog(执行引擎) 的应用框架包装
├── core（仅 JVM）         领域模型 + Manager(唯一真理源) + Api(DTO/异常) + Store(SQLite) + Koog 适配 + 工具 + Plan
├── app/shared（KMP）      跨平台 UI + AiCore 契约桥 + AppState/VM；jvmMain 里 MederiAiCore 直调 core，commonMain 里 ServerAiCore 走 REST/SSE
├── server（JVM 薄壳）     headless 启动器 = MederiAiCore + RemoteServer 路由，零业务逻辑
├── inkcompose（KMP）      富文本渲染库：Markdown/LaTeX/语法高亮/Mermaid 图表/竖排文字
└── app/{desktop,android,web,ios}App  宿主入口，只做平台注入，禁止挂业务
```

- 一切业务行为挂在 **AiCore 层**（contract `AiCore.kt` → `MederiAiCore` → `ServerAiCore` → server 路由，四处同步）。
- 契约变更四处同步硬规则：**AiCore.kt → MederiAiCore → ServerAiCore → RemoteServer 路由**（+Mock 同步）。
- 对外只抛 `MederiException` 子类；Manager 是唯一真理源；Store 只做持久化。
