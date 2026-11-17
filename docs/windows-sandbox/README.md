# Windows 原生命令沙盒交接

实施编号 WS-2026-11-08。状态为待实现。

1. 读仓库根 [AGENTS.md](../../AGENTS.md)，记录起始 Git 状态，保留已有修改。
2. 读 [现行执行沙盒与项目权限](../sandbox-plan.md) 的 §1–5，以及 [Windows 实施规格](spec.md) 全文。前者定义产品语义，后者定义 Windows 的实现与能力边界。
3. 按 [实施顺序与验收](verification.md) 逐段实现，先在 Windows VM 证明隔离机制可行，再接生产链和发版。提交证据时用该文末尾的模板。

## 从这里开始

Windows 缺的是宿主 Core 的 OS 命令后端。项目设置、命令审批、计划审批、desktop/server/遥控端共享接口已存在。核心入口是 [ShellTools](../../core/src/commonMain/kotlin/xyz/mederi/tools/ShellTools.kt)、[CommandSandbox](../../core/src/commonMain/kotlin/xyz/mederi/tools/sandbox/CommandSandbox.kt)、[SandboxPaths](../../core/src/commonMain/kotlin/xyz/mederi/tools/sandbox/SandboxPaths.kt) 和 [ProcessRegistry](../../core/src/commonMain/kotlin/xyz/mederi/tools/sandbox/ProcessRegistry.kt)。工作区准备与验证命令也从这条路走。当前 Windows 分支在无法强制执行所需边界时阻止命令启动，实施期间保留这条保护。

Codex 是原生隔离结构和能力探测的参考。Mederi 保留自己的项目级两档、一次批准及路径硬拒绝语义。参考方案包括管理员预配置的兼容后端和 MXC，默认优先可完整执行本次策略的 MXC。现行 Codex 另有 unelevated 后端，环境变量级网络限制不足以满足 Mederi 本期要求。 [Codex Windows 沙盒文档](https://learn.chatgpt.com/docs/windows/windows-sandbox)

## 上游依据与实施优先级

**直接复用 Codex 的 Rust 沙盒源码，并自动跟随上游。** W00 从上游最新稳定发布取候选提交，试编 `mxc-sandbox` 和依赖。Mederi 自有 Rust helper 负责把 Kotlin 的策略和进程生命周期接到上游库。默认上游源码零修改。若 Cargo 工作区耦合妨碍直接依赖，自动同步所需源码及上游测试，适配仍放在 Mederi 自有代码。兼容阶段对 `windows-sandbox-rs` 用同样的方法。如果原生验收证明上游接口缺少必要能力，优先向上游提交改动。必须临时维护补丁时，单独记录原因、上游跟踪项和移除条件。最终随 Mederi 发布 helper，用户无需安装 Codex CLI。 [MXC crate 依赖](https://github.com/openai/codex/blob/main/codex-rs/mxc-sandbox/Cargo.toml) · [兼容 crate 依赖](https://github.com/openai/codex/blob/main/codex-rs/windows-sandbox-rs/Cargo.toml) · [Codex 许可证](https://github.com/openai/codex/blob/main/LICENSE)

GitHub Actions 每天检查稳定发布，有新版本就生成依赖更新 PR，将该提交及其 MXC SDK 版本交给 Windows 原生矩阵。已知安全修复可手动指定上游提交立即走同一条流程。原生验收通过后自动合并依赖更新，发布构建使用该次验证过的提交和锁文件，并记录在产物元数据中。失败时保留失败报告和告警。已知受影响版本在修复验证完成前停止发布。

- **MXC 优先。** OpenAI 将原生 MXC 用于支持它的 Windows 设备，用户可以省掉管理员初始化、额外 Windows 账户和本地防火墙配置。微软文档区分 ProcessContainer 的原生与回退层，并明确设备可用与单项策略能力需要分别验证。按 SDK 实际探测选择原生层，并检查本次策略所需的原生路径拒绝能力。 [OpenAI Windows 沙盒文档](https://learn.chatgpt.com/docs/windows/windows-sandbox) · [Microsoft MXC 平台支持](https://learn.microsoft.com/en-us/windows/apps/develop/mxc/platform-support) · [Codex 能力探测](https://github.com/openai/codex/blob/main/codex-rs/mxc-sandbox/src/availability.rs)
- **Rust helper 执行 Windows 隔离，Core 保有授权。** Codex 的 MXC 代码把可读、可写、拒绝路径和网络分别编译为原生策略，并由 Microsoft SDK 启动 payload。Mederi 复用该结构，由现有 `SandboxManager` 和 `SandboxPaths` 生成不可变执行策略。默认网络策略同时拒绝外网与宿主回环。先验证现有后台任务语义能否用 SDK 保持，尤其是 launcher 退出后的 Job 生命周期。 [Codex 策略映射](https://github.com/openai/codex/blob/main/codex-rs/mxc-sandbox/src/policy.rs) · [Codex 原生启动](https://github.com/openai/codex/blob/main/codex-rs/mxc-sandbox/src/native.rs) · [Codex MXC 生命周期及限制](https://github.com/openai/codex/blob/main/codex-rs/mxc-sandbox/README.md)
- **旧版兼容后端单独验收。** Codex 把管理员配置与普通权限执行拆开，用专用低权限身份、ACL、按身份强制的防火墙与受限令牌实现边界。Mederi 仅在用户本机主动配置或 IT 预置后使用此路径。按执行策略分别声明可用能力，尤其是项目间 ACL 隔离和完全访问附带黑名单。 [Codex 管理员配置](https://github.com/openai/codex/blob/main/codex-rs/windows-sandbox-rs/src/setup_provisioning.rs) · [Codex runner 启动](https://github.com/openai/codex/blob/main/codex-rs/windows-sandbox-rs/src/elevated/runner_client.rs) · [Codex Job 与句柄](https://github.com/openai/codex/blob/main/codex-rs/windows-sandbox-rs/src/process.rs)

实施顺序先做 W00 能力验证、W01 协议、W04 MXC，再做 W02/W03 兼容后端，最后完成 W05–W07。W00 同时用小型探针检查两条路线。发行包保留相关许可证与适用的归属声明。上述源码链接指向调研时可读的公开主线。

给执行 AI 的一句话：

> 在当前 Mederi 仓库完整实施 `docs/windows-sandbox/README.md` 的 WS-2026-11-08，先读它链接的规格和验收计划，按 W00–W07 逐段做并保存 Windows 原生证据。任何后端无法强制执行所需策略时必须阻止启动，缺环境或证据就如实标未完成，交付后由 Codex 复审。
