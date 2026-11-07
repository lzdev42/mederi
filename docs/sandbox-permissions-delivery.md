# macOS/Linux 沙盒与项目权限交付

2026-11-01。当前行为以代码及 [沙盒设计](sandbox-plan.md) 为准；此前三档分析不再作为实施依据。Windows 原生命令后端留到下一批。

## 使用方式

侧栏项目菜单 → 项目设置（设置 → 沙盒亦可查看当前项目），输入框另有“权限与计划”图标菜单，提供项目级的「需要批准／完全访问」，同一项目的会话及执行子代理、desktop/server 和遥控端同源。默认需要批准；框内操作直接执行，越界操作由用户批准一次，不调用任何模型做安全审查。“自动批准计划”独立控制计划批准，不代表执行权限；窄屏输入选项面板直接展开这两组操作，桌面只显示图标及悬停状态提示。项目设置另含路径白/黑名单及命令允许/拒绝前缀。

需要批准时，bash/verify_subtask 的 `sandbox=network` 为该命令申请联网且保留文件系统限制；`sandbox=off` 为该命令申请解除沙盒。文件工具自动申请这次完整写操作的批准，批准前不创建父目录。完全访问不弹执行审批，仍保留进程登记、取消、超时与数据格式校验。

## 架构与接口

`SandboxApi → SandboxManager → SettingsStore`，每项目配置 key 为 `sandbox.projects.{projectId}.settings`。不在 AppState/宿主 main 保存另一份权限配置。旧全局权限及设备偏好不迁移到各项目，旧数据保留；未删除用户数据或改动数据库 schema。

| AiCore 方法 | REST |
|---|---|
| getSandboxSettings(projectId) | GET /v1/projects/{projectId}/sandbox/settings |
| updateSandboxSettings(projectId, settings) | POST /v1/projects/{projectId}/sandbox/settings |
| getSandboxStatus(projectId) | GET /v1/projects/{projectId}/sandbox/status |
| resolveExecutionApproval(conversationId, requestId, approved) | POST /v1/sessions/{id}/execution-approvals/{requestId}，body 为 `{ "approved": true }` 或 false |

设置包含 mode、extraWritablePaths、version。修改时保留最近一次读取的 version；core 原子校验版本，阻止旧遥控表单覆盖新权限。后端能力状态与用户选定模式分别展示。

活体申请通过会话快照的 pendingExecutionApprovals 和现有 SSE 事件提供。请求按 ID 独立保存、取消与失效，子代理申请归父会话；批准只唤醒原调用。变更设置撤销旧申请，进程重启不从历史恢复授权。事件背压不会阻塞批准/取消状态机。

## 边界与审查要点

- 文件与命令共用 canonical 根及保护路径；默认不放开家目录构建缓存、整棵 /private/var/folders 或宿主配置目录。项目 .mederi 计划产物仍可写。
- macOS 通过内联 SBPL 执行，消除可写策略缓存；Linux 通过 bwrap + seccomp 隔离网络及宿主 Unix socket 通路，不落策略缓存。后端失败阻止受限命令。
- 前台、后台和存储的验证命令共用授权准备入口。联网批准不会解除文件限制；没有授权上下文也不能通过空 backend 裸跑。
- 批准后复核配置代次与路径。整份补丁包括移动源/目的，实际每个写目标必须匹配授权前捕获的目标，不能因等待期间相对路径查找改变而改写另一个文件。
- 文件写入保留同文件写锁；write/edit/Office 增加硬链接拒绝，apply_patch 保留预检与失败回滚。宿主文件工具的路径校验不声称消除了外部进程竞争目录修改的一切 TOCTOU 风险。
- 调研 HTTP 已脱离 shell，限制协议、重定向、超时及响应体积；它和浏览器/MCP 是独立能力，不应宣称被命令沙盒完整覆盖。
- 审批卡不折进过程，提交状态按原会话和 requestId 隔离，错误与可重试状态原子更新。主 turn 阶段结束不撤销仍在等待的子代理申请。

## 项目级与图标菜单修订验证（2026-11-01）

| 检查 | 结果 |
|---|---|
| core 全量 JVM | 603 项：597 通过、3 跳过、3 项既有 GitHub 联网测试失败（API HTTP 403）；沙盒/项目权限相关零失败 |
| macOS Seatbelt 原生 | `MEDERI_REQUIRE_MACOS_SANDBOX=1`，普通/FULL 黑名单读写拒绝实际执行通过；CommandSandboxTest 共 7 项零失败、零跳过 |
| Linux 策略 | LinuxCommandSandboxTest 14 项，12 通过、2 项原生用例因 macOS 宿主跳过；包括黑名单遮罩、缺失目标失败及准备阶段不创建黑名单/保护目录内容 |
| shared 权限 UI/API/遥控回归 | 39 项全部通过，含 320dp 图标菜单、手机直接展开两组设置、280dp 大字体四规则编辑、项目归属未知时不借用其它项目、共享投影及 HTTP 桥 |
| shared JVM / Android / wasmJs / iOS Simulator Arm64 | 编译通过 |
| 组件图像检查 | 检查 320dp 菜单/手机内容截图：两组职责清楚、文本换行、无横向溢出；仅测试组件，并非 APP 实机验收 |

最终测试关闭 Kotlin 增量编译（`-Pkotlin.incremental=false`）避开本地缓存的 DirtyData/FqNames 错误。此前 core 602 项曾零失败、3 跳过；新增准备阶段黑名单回归后的 603 项中，三项 Camoufox 实网测试遇到 GitHub 403，未修改相关测试或安装器行为。

界面现在只保留“权限与计划”盾牌图标，悬停/可访问名称显示两项状态；展开后分别选择项目执行权限、自动/手动批准计划，并可进入项目设置。手机输入选项面板直接显示相同内容，避免嵌套下拉。旧 AgentModeSelector/AutoApproveSwitch 及文字权限 pill 已移除。

## 前一版全局方案验证记录（历史记录）

| 检查 | 结果 |
|---|---|
| core 全量 JVM 测试 | 585 项，0 失败，2 跳过 |
| macOS 原生 Seatbelt | required 模式实际执行；写入、保护路径、全盘读、默认网络拒绝、单次联网仍保留文件边界均通过；对应 CommandSandboxTest 6 项无跳过 |
| 权限 UI/API 专项 | 21 项全部通过，覆盖多卡、失败重试、防双击、原会话归属、状态归约、设置保存与 HTTP 桥 |
| shared 全量 JVM 测试 | 544 项，2 项现有失败；与未修改 HEAD 的隔离基线相同，见下 |
| shared JVM / Android / wasmJs / iOS Simulator Arm64 编译 | 通过 |
| git diff --check | 通过 |

core 两项跳过分别是当前 macOS 无法执行的 Linux 原生验收，以及原有断流自愈集成测试的 Ignore。Linux 的 argv、路径、BPF/ABI、管道与错误分支单测已执行；这不等于 Linux 真机验收。

shared 基线取自 `f35a574f8124814dd0286204eae06b15ed7401a8`，隔离副本全量 530 项，同样有以下两项失败，本批没有改写这些测试或相应产品行为：

- ChatScrollLayoutTest.actualReasoningExpansionAndStreamingDoNotDisplaceBottomControl：全套运行时找不到展开控件，单独执行可通过。
- UnifiedInputDeliveryTest.failure after switching restores original conversation instead of contaminating current draft：等待 5 秒超时，单独与全量基线均复现。

## 待环境验收及后续

Linux 真机执行（required 模式缺少后端或平台时必须失败，不能跳过冒充通过）：

```bash
MEDERI_REQUIRE_LINUX_SANDBOX=1 ./gradlew :core:jvmTest \
  --tests 'xyz.mederi.tools.sandbox.LinuxCommandSandboxTest' --rerun-tasks
```

当前会话无 idea-mcp，未启动 APP/server 做实机交互验收；需要先启动/配置 idea-mcp 才能运行 APP/server。自动 UI 组件测试和实际 HTTP 桥测试已通过。

Windows 当前没有受限命令后端，默认失败即停；仅无显式路径黑名单且用户明确批准单次 off 或选择完全访问时才无沙盒执行；有路径黑名单时命令仍阻止。下一批再实现 Windows 隔离后端，不将此状态宣传为已具备 Windows 沙盒。
