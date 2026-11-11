# Windows 沙盒实施顺序与验收

## 环境与执行纪律

- 开始时记 HEAD、工作区状态、JDK/Gradle、Rust/Windows SDK、Windows 版本/架构、管理员与普通用户角色。用有快照的 Windows 11 x64 VM，至少有管理员可安装、标准用户可运行的环境。对 MXC 记录运行时版本、ProcessContainer 探测结果和实际隔离级别。其余 Windows 版本须分开报告。
- 所有文件、Git 仓库、凭据替身、监听端点、安装资源均为合成 fixture。仓库内只用 `temp/windows-sandbox/` 保存实验及报告。要验证实际系统账户、WFP、注册表、安装时，在专用可回滚 VM 里只改本测试安装 ID 的资源。不要对真实项目、用户目录、实际凭据试 ACL 或做卸载实验。
- 用户命令原生测试可以由测试代码和 Windows shell 启动。APP/server 实机验证按 AGENTS.md 使用 idea-mcp。构建、解包与检查 Windows 发布物可走 CLI。缺原生/手工环境时如实记录对应门未通过。
- 先写能复现边界的独立探针。Native 端准备的正向对照必须证实文件存在、监听可达、脚本确实启动、读写 API 和参数本身有效。拒绝用例同时断言 Win32/SDK 错误类别和副作用，例如读取 token 未出现在输出、目标内容和 ACL 未变、网络端点未收到 nonce。故障注入不能直接伪造最终结果。
- 用户命令 stdout 不能充当 native 握手或最终报告。整树测试用句柄或进程创建时间辨认实例，检查保留无关的 sentinel 进程。测试探针在同一宿主监听 IPv4/IPv6 回环与另一个可控 peer/接口，不依赖公网。

## 阶段顺序

| 阶段 | 要交付什么 | 独立通过条件 |
|---|---|---|
| W00 能力与基线 | 复跑现有 JVM 基线，整理入口和进程生命周期。在 VM 对兼容和 MXC 分别做最薄的路径、网络和 Job 探针。特别验证同父目录内的 deny 覆盖、真实读拒绝、父目录删除通路和 deny-only，列出支持矩阵 | 保存命令、输出与机制依据。通过真实对象确认 WN-02/03/05/07/08/09 的可行性或定位具体缺口。明确哪个后端能覆盖 deny-only。两套都无法表达关键约束时，保留拦截并提交可复现的机制缺口，不能降低产品权限语义 |
| W01 协议与装配 | 创建 Rust helper、Kotlin 内部执行描述、协议版本/超时/错误、可信二进制路径和共享启动入口。后端暂不可用时保持原有拦截 | JVM 测试使用注入的 helper，验证 WI-01–04。Native 传输测试跑 WN-01，验证控制消息不能被 payload 冒充 |
| W02 兼容安装 | 一次性账户和网络规则 provisioning、身份限制、诊断、修复、卸载与故障恢复。记录资源所有权 | WN-07/10/12。管理员只做部署动作，标准用户能调用 probe/run；重复安装和失败修复可验证 |
| W03 兼容执行 | 受限 token、ACL/只读与拒绝覆盖、控制信道、Job 和生命周期；为可证实的模式声明能力 | WN-01–11 中兼容适用的所有项。使用真实普通项目及 linked worktree fixture，默认和 network 路径都能运行。deny-only 的支持状态单列 |
| W04 MXC | 固定 SDK 版本，接原生 ProcessContainer，翻译策略、能力探测、优先选择和取消 | WN-01–11 中 MXC 适用的所有项。两个后端同时可用时优先 MXC。确定不支持才允许在启动前选兼容，模拟运行失败后无换后端重跑 |
| W05 生产链 | 前后台、验证、工作区 Setup/Action、父子代理、执行租约与项目设置代次接入真实 helper，桌面和 server 共用 Core | WI-01–07 与 WN-13。必须有原生测试经过公开工具入口，直接跑 helper 只算机制证据 |
| W06 发布与运维 | Windows 桌面、server bundled、server API 发布物携带并从可信布局发现 helper；文档和诊断更新 | WN-14，真实发布物布局检查，安装/修复说明可在标准用户场景复现。其他平台构建与已有沙盒原生测试通过 |
| W07 独立收口 | 按下述验收矩阵补齐证据、提交支持和已知缺口，做负向变异 | 汇总必须有受支持的 Win11 x64 原生证据、两个后端的适用能力、至少一个真实 deny-only 后端及跨平台回归。缺项单列，不能宣称完成 |

W00 在写大段生产代码前做完。兼容与 MXC 共用策略和证据结构。W00 若发现某个后端无法落实 deny-only，可让该后端仅交付通过的能力。对应配置在运行时须失败即停，最终发布说明写清哪些 Windows 环境支持完整项目权限。

## JVM 与生产集成矩阵

JVM 仿真覆盖授权、分流、协议和状态机。给项目、会话、工具调用、路径和设置代次不同的输入，断言真实调用参数和是否启动；避免仅断言类名、日志字面量或编译成功。

| ID | 断言 |
|---|---|
| WI-01 | 四行权限矩阵逐条走生产准备入口。拒绝命令、用户拒绝、缺授权上下文的提升、项目代次或工作区凭证变化均无启动。允许前缀走已有静态规则，只免相应提升审批 |
| WI-02 | 前台、后台、verify_subtask、Setup/Action 同一准备链，默认/联网使用同一文件策略。linked worktree 使用准确 checkout/产物根/重映射黑名单，源目录与兄弟 worktree 不自动可写。后台与 Action 的租约等到进程实际终止才释放 |
| WI-03 | MXC 与兼容能力组合做参数化矩阵。明确不支持、探测异常、后端启动失败、payload 非零分别有正确状态。黑名单非空时禁止产生 host/plain 启动计划。FULL/off 只能选能执行 deny-only 的后端，或直接拦截 |
| WI-04 | 协议错误、超时、helper 缺失或路径不可信、payload stdout 伪造 ready、错 executionId/重复消息均不能误报启动。准备后取消或 grant 失效不能放行 suspended payload |
| WI-05 | 停止、取消、超时、前台结束、后台完成、helper 意外退出使用同一持有的进程/Job 所有权；后台失败不会残留活任务，状态与退出码正确 |
| WI-06 | 桌面桥与 REST 状态一致，保持现有权限和审批卡含义。新增宿主状态如需 UI，使用 commonMain + 中英资源并验证窄屏可读。模型工具、遥控请求和环境脚本不能触发特权安装 |
| WI-07 | 已有 macOS/Linux 和 Worktree 授权、验证、租约测试仍走生产路径，行为不退化 |

## Windows 原生矩阵

兼容和 MXC 分别运行相同的适用用例。原生测试以宿主 JVM/标准用户启动真实 helper，用可断言的 native payload/Win32 探针直接测 OS 权限。正常无黑名单完全访问路径也验证进程回收。

| ID | 用例与可观测结果 |
|---|---|
| WN-01 | `cmd`、PowerShell、Git for Windows Bash 各走真实入口。带空格、Unicode、引号、尾反斜杠参数原样到达 payload。验证输出、错误、退出码、输出上限和控制信道。helper/依赖目录的恶意同名替身没有执行 |
| WN-02 | default 与 network 都可读普通工作区及宿主用户代表性外部可读文件，只能改工作区、批准的临时根、额外白名单。单文件白名单不能写同级兄弟，外部哨兵内容和身份不变。项目计划产物可写 |
| WN-03 | Git 普通仓库、gitfile 与 linked worktree 的实际 gitdir/common dir 可读，但写、删、重命名、替换均被拒；保护目录和项目写根正确交叠。测试可写父目录的 `FILE_DELETE_CHILD`。普通兄弟文件的同类操作应成功，证明不是把整个工作区锁住 |
| WN-04 | 受保护文件和拒绝对象对工作目录相对路径、8.3 短名（启用该特性的 fixture）、大小写、长路径、junction、symlink、hardlink、ADS 和允许目录内部的卷挂载别名保持隔离。对无法支持的对象明确 fail closed。用受控同步点在准备期替换目录或 ACL 验证启动防护 |
| WN-05 | 保护目录可读，项目黑名单文件/目录不能读、写、列、删、重命名或替换，包括黑名单处于额外写根内。内容用随机 nonce，父目录和普通兄弟均有阳性对照。验证缺失拒绝对象、非 NTFS、UNC/设备路径失败不启动。至少一个后端在 FULL 和批准 off 下真能执行 deny-only：普通非黑名单外部文件可写，黑名单仍不可访问 |
| WN-06 | 两个项目、两个并发命令及同路径交叠的反例。A 的写能力不能让 B 写入同一对象，A 的拒绝不会残留并伤及 B、真实用户或非沙盒进程。以同一文件内容、ACL、SID 和对象身份为证据 |
| WN-07 | 同一 fixture 对 TCP/UDP IPv4/IPv6、DNS 和宿主回环分别做 off-line 与 online 对照，监听端核验 nonce。命令用原生 socket、非 curl 实现，验证子进程及尝试通过本机代理/具代表性的命名管道出网仍被拒。连接失败须能排除服务未启动或 DNS 解析失败的干扰 |
| WN-08 | 后端没装、策略被删改、路径无法表示、Job 创建/绑定失败、损坏的 helper 和启动失败。payload 放首行 marker，断言它从未执行。模拟 MXC 选中后失败，兼容未启动且无裸跑 |
| WN-09 | 前后台子/孙进程在停止、超时、会话取消、helper 崩溃、父 JVM 崩溃后被回收，另一个独立进程存活。测试 payload 尝试 breakaway 和 WMI/任务计划/服务等 broker。后台未成功启动时没有虚假成功任务。正常提前退出也要符合现有清理和租约规则 |
| WN-10 | 兼容 setup/repair/uninstall 普通用户与管理员分工。取消、重复安装、部分创建、规则丢失、升级、卸载及重启修复。ACL、账户和防火墙/过滤器对比快照，只清理本测试安装拥有的资源。普通用户和沙盒账户不能直接读取凭据、注册自己或调用特权 broker |
| WN-11 | 挂起后还未 resume、ACL 意图刚写、ACL 部分落地、payload 运行中各点强制终止或重启。恢复拒绝带残留的危险启动，能按身份恢复自有资源且保留无关用户变更。MXC 验证本后端实际会创建的资源和断点 |
| WN-12 | Offline/Online SID 不混用，Online 使用的身份、可写临时目录和启动环境无法被 Offline 借用；无关用户的出网行为不变。拒绝高权限句柄继承、远程连接到控制管道及未授权的 UI/IPC 请求 |
| WN-13 | 通过实际 Core 工具运行前台、后台、verify_subtask、Worktree Setup/Action 至少各一次。用 stdout、外部文件、监听和租约可观测结果验证边界；重复执行覆盖网络批准、设置变化、拒绝和取消 |
| WN-14 | 检查桌面、server bundled、server API 发布物。离开源码树、用空净工作目录及不含开发 helper 的 PATH 验证可信路径，正确识别可用/不可用状态并执行原生 smoke。APP/server 运行和桌面/遥控人工对照按 AGENTS.md 的 IDE 入口进行 |

## 运行入口与通过标准

实现者负责提供稳定入口 `scripts/windows-sandbox/verify.ps1`，支持 `-Backend Compatibility|Mxc` 和 `-EvidenceDir`，运行本后端原生证据与报告检查。命令为交付目标，当前仓库尚未提供该脚本。

```powershell
cargo test --locked --manifest-path <helper-crate>/Cargo.toml
./gradlew.bat :core:jvmTest :app:shared:jvmTest
$env:MEDERI_REQUIRE_WINDOWS_SANDBOX = '1'
pwsh -File scripts/windows-sandbox/verify.ps1 -Backend Compatibility -EvidenceDir temp/windows-sandbox/compatibility
pwsh -File scripts/windows-sandbox/verify.ps1 -Backend Mxc -EvidenceDir temp/windows-sandbox/mxc
Remove-Item Env:MEDERI_REQUIRE_WINDOWS_SANDBOX
```

`MEDERI_REQUIRE_WINDOWS_SANDBOX=1` 在平台、helper、配置或所要求能力不可用时必须失败。日常跨平台 JVM 测试可跳过非本平台原生用例，required 原生模式不接受跳过。能力矩阵中明确不适用的兼容专用项与 deny-only 例外单独标注，并分别用失败路径证明系统依然拦截。Core、shared JVM 及涉及契约时的 Android/wasmJs/iOS Simulator 编译通过后，还要运行既有 macOS/Linux 原生验证。命令和要求参考 [现行沙盒规格 §7](../sandbox-plan.md)。

至少进行三次局部负向变异并在恢复后复跑同一用例。可在测试 fixture 上关闭 Offline 的某条规则，去掉一个拒绝或只读保护，模拟不分配 Job 或允许启动 fallback。每次记录具体被击中的断言；单纯编译失败不算证明。变异只能在可回滚 VM 和隔离 fixture 上进行。

## 交付记录模板

把以下内容写入本目录的新 `delivery.md`。日志和报告原件放入约定的证据目录或 CI artifact，避免把凭据、真实用户路径和整个 VM 日志提交进 Git。

- 起始 HEAD、最终源码指纹/提交、helper 二进制 SHA-256、Windows/SDK 版本及权限角色。
- W00–W07 状态，每条注明代码入口、实际命令、报告位置、失败后的复跑及未完成项。
- WI/WN 逐项报告。每个用例包含后端、实际运行状态、预期/观察、未跳过证明。支持矩阵分别列 Win11 发行版、标准用户、离线、联网、黑名单封套、整树回收。
- 安装、卸载、重启恢复与发布物路径的证据。桌面/遥控实际对照单列，缺 idea-mcp 时写未验。
- 负向变异的故障位置、失败断言、恢复后结果。列明仍不能表示的路径/策略、相关错误及当前发布能力。缺真实原生数据时用 `BLOCKED`，不要用编译或 mock 结果补齐。
