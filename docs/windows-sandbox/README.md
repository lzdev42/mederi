# Windows 原生命令沙盒交接

实施编号 WS-2026-11-08。状态为待实现。

1. 读仓库根 [AGENTS.md](../../AGENTS.md)，记录起始 Git 状态，保留已有修改。
2. 读 [现行执行沙盒与项目权限](../sandbox-plan.md) 的 §1–5，以及 [Windows 实施规格](spec.md) 全文。前者定义产品语义，后者定义 Windows 的实现与能力边界。
3. 按 [实施顺序与验收](verification.md) 逐段实现，先在 Windows VM 证明隔离机制可行，再接生产链和发版。提交证据时用该文末尾的模板。

## 从这里开始

Windows 缺的是宿主 Core 的 OS 命令后端。项目设置、命令审批、计划审批、desktop/server/遥控端共享接口已存在。核心入口是 [ShellTools](../../core/src/commonMain/kotlin/xyz/mederi/tools/ShellTools.kt)、[CommandSandbox](../../core/src/commonMain/kotlin/xyz/mederi/tools/sandbox/CommandSandbox.kt)、[SandboxPaths](../../core/src/commonMain/kotlin/xyz/mederi/tools/sandbox/SandboxPaths.kt) 和 [ProcessRegistry](../../core/src/commonMain/kotlin/xyz/mederi/tools/sandbox/ProcessRegistry.kt)。工作区准备与验证命令也从这条路走。当前 Windows 分支在无法强制执行所需边界时阻止命令启动，实施期间保留这条保护。

Codex 是原生隔离结构和能力探测的参考。Mederi 保留自己的项目级两档、一次批准及路径硬拒绝语义。参考方案包括管理员预配置的兼容后端和 MXC，默认优先可完整执行本次策略的 MXC。现行 Codex 另有 unelevated 后端，环境变量级网络限制不足以满足 Mederi 本期要求。 [Codex Windows 沙盒文档](https://learn.chatgpt.com/docs/windows/windows-sandbox)

给执行 AI 的一句话：

> 在当前 Mederi 仓库完整实施 `docs/windows-sandbox/README.md` 的 WS-2026-11-08，先读它链接的规格和验收计划，按 W00–W07 逐段做并保存 Windows 原生证据。任何后端无法强制执行所需策略时必须阻止启动，缺环境或证据就如实标未完成，交付后由 Codex 复审。
