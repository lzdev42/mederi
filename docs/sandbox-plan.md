# 执行沙盒设计（Sandbox）

> 沙盒已全链路落地。本文档记录生效中的设计决策与实测结论（macOS 实测通过；Linux 待真机验证）。

## 0. 已拍板决策（不要重新讨论）

| # | 决策 |
|---|---|
| D1 | **沙盒永远开，无开关**。不感知的安全不需要配置。设置页只放"全局写白名单"编辑器 + 状态展示 |
| D2 | **全局策略，项目级路径**。开关/白名单是应用级全局设置；写白名单路径按当前会话所属项目的 directories 动态生成 |
| D3 | **Windows 不做命令沙箱**：裸跑 + 警告前缀。文件工具的纯 Kotlin 路径校验全平台兜底 |
| D4 | **Linux 只检测不代装**：探测 bwrap；缺失/完整配置不可用 → 明确错误 + 阻止命令，不回退裸跑；下个 turn 重新检测 |
| D5 | **macOS Seatbelt**（sandbox-exec，零依赖，本机实测通过） |
| D6 | **shell 探测链**：macOS/Linux `bash → sh`；Windows `bash.exe(Git Bash) → cmd /c`；探测结果注入系统提示词 |
| D7 | **无逐操作询问**。沙箱兜底，执行路径由主代理按 Triage Flow 分诊判断（见 `AGENTS.md §5.6`）。计划审批工作流（create_plan → 用户批准）**保留不动** |
| D8 | 读全盘放行（含敏感文件；提示词已约束不外泄密钥）；写锁死白名单 |
| D9 | 项目模型：`directories.first()` = 主目录（承载 .mederi/、shell cwd、相对路径解析首选），其余目录平等读写 |
| D10 | 写白名单内置项：项目 directories ∪ `/tmp`+`$TMPDIR` ∪ 构建缓存（`~/.gradle ~/.m2 ~/.cache ~/.konan ~/Library/Caches ~/Library/Java`）∪ 设备文件（/dev/null 等）∪ 全局白名单（用户配置） |

## 1. 生效中的不变量（代码强制）

| 层 | 规则 | 实施层 | 生效 |
|---|---|---|---|
| read_file / list_directory | 全盘可读 | 纯 Kotlin（resolveForRead 无包含校验） | 全平台 |
| write_file / edit_file（apply_patch 已注销，实现保留） | 只能写白名单路径；同文件并发写硬拒绝（FileWriteRegistry） | 纯 Kotlin（resolveForWrite containment 校验，WindowsPath 大小写不敏感天然兼容） | 全平台 |
| bash | OS 级沙箱：写=白名单，读=全盘，网络/进程放行 | macOS Seatbelt / Linux bwrap / Windows NONE | 按平台降级 |
| 进程回收 | 只允许回收 mederi 自己 spawn 的进程组（ProcessRegistry 注册表）；沙箱内命令无法写入注册表，查不到 pid 即拒绝 | ProcessRegistry（宿主侧）+ list_processes / stop_process | 全平台 |
| 是否写文件/建计划 | Triage Flow 分诊判断（提示词强制，见 `AGENTS.md §5.6`），沙盒是唯一代码级安全网 | SystemPrompts | 全平台 |

## 2. 架构

```
设置页白名单 → AppState(PreferencesStore "sandbox.extraPaths", JSON 数组)
            → mederi.sandboxExtraPaths（可变属性，实时生效）
SessionManagerImpl → TurnExecutor（每 turn 读取）
            → ToolFactory → ShellTools(CommandSandbox 实例)
CommandSandbox.wrap(command, allowedDirs, extraPaths) → ProcessBuilder argv
ShellTools.runCommand → 启动即注册 → ProcessRegistry（全局单例，进程组）
            → list_processes / stop_process（宿主侧 kill -- -pgid 整组回收，沙箱外）
```

- CommandSandbox 每 turn 新建实例 → bwrap 探测每 turn 重跑（版本与完整配置各限 10 秒；用户装完无需重启）
- 白名单合并发生在 CommandSandbox 内部（canonicalPath + macOS /tmp→/private/tmp 变体去重）
- SBPL profile 文本按「目录集合 hash」缓存临时文件复用

## 3. 系统环境注入（metaNote）

每条用户消息末尾 `<<<NOT_FOR_UI>>>` 隐藏块注入 AI 需要知道的环境事实：

```
<<<NOT_FOR_UI>>>
NOTE FOR AI (hidden from user in UI):
Time: UTC ... / Local ... (tz, dow)
OS: macOS 15.5 (arm64) / Windows 11 (amd64) / Ubuntu 24.04 (x86_64)
Shell: bash 5.2 (sandboxed writes: <project dirs> + tmp + build caches)
Sandbox: Seatbelt (macOS) / bubblewrap (Linux) / none (Windows)
Process control: list_processes / stop_process manage mederi-spawned processes only
Project dirs: /path/a (primary), /path/b
Extra writable paths: ... (全局白名单，如有)
Mederi workdir: <primary>/.mederi
Java: 21.0.5  (进程运行时，构建相关任务有用)
```

## 4. SBPL profile 模板（macOS，已实测）

```
(version 1)
(deny default)
(allow process-exec*)
(allow process-fork)
(allow mach-lookup)
(allow system-socket)
(allow sysctl-read)
(allow sysctl-write)
(allow ipc-posix*)
(allow ipc-sysv*)
(allow network*)
(allow process-info*)
(allow file-read*)
(allow file-write*
  (subpath "<dir>" ...)      ; 项目目录 + canonical 变体
  (subpath "/tmp" "/private/tmp" "/private/var/folders" "$TMPDIR")
  (subpath "<缓存目录>" ...)  ; D10 内置缓存 + 全局白名单
  (literal "/dev/null" "/dev/stdin" "/dev/stdout" "/dev/stderr")
)
```

实测结论（本机）：项目内写 OK；外部写 `Operation not permitted`；外部读 OK。
`(allow sysctl-write)` 必须保留——`/usr/libexec/java_home`（gradlew 无 JAVA_HOME 时的 JDK 探测链）
内部走 CFPreferences 需要它，缺失时报 "Unable to locate a Java Runtime"。
沙箱内 `env -u JAVA_HOME ./gradlew :core:compileKotlinJvm` 全绿。
macOS 沙箱内 gradle 首跑如遇遗漏路径（daemon socket、Xcode licenses 等）→ 实测后追加 profile 放行项，**追加项记录到本节**。

**信号完全不可配（2026-09 实测，macOS 26.6.2）**：`(allow process-signal)` 一律报
`unbound variable`——本版本 Seatbelt 没有 process-signal 操作（连父→子、同 profile 进程间
互发信号都被 deny default 拦死，实测 `kill` 全部 "Operation not permitted"）。**profile 层面无法
放开信号**，因此跨命令回收只能走宿主侧进程管理（见 §4.5）。该限制是本次新增进程管理机制的直接动因。

## 4.5 进程管理与宿主侧回收（2026-09 落地，修 macOS 互杀不可能）

**问题**：macOS 沙箱内命令之间无法发信号 → bash 起的 dev server / 后台任务，
后续命令 `kill` 必然失败，只能靠会话 teardown 回收。

**机制**：每条命令套**进程组长包装**，使整条命令树共享一个 PGID（= 直接子进程 pid）：
- macOS：`perl -e 'setpgrp(0,0) or die $!; exec @ARGV' -- <sandbox-exec ...>`（/usr/bin/perl 系统自带）
- Linux：`setsid`（util-linux 默认带；缺失降级无进程组）
- Windows：无进程组，退化单 pid + `taskkill /T`

**回收**（宿主侧，沙箱外执行）：
- `ShellTools.runCommand` 启动即写入 `ProcessRegistry`（进程组注册表，全局单例）
- **超时**：整组 SIGKILL（修掉原实现只 destroyForcibly 杀直接子进程、孙进程变孤儿的洞）
- `list_processes` / `stop_process`（新工具，主代理 + Executor 可用，Researcher 无）：按注册表
  定向回收，`pid` 来自 list 输出

**安全边界（硬）**：注册只发生在 runCommand 启动点；注册表查不到 pid 一律拒绝——
AI 无法用 stop_process 杀任何非 mederi 启动的进程。沙箱内命令永远没有写注册表的通道。

## 5. bwrap argv 模板（Linux）

```text
setsid bwrap --unshare-pid --die-with-parent --cap-drop ALL --ro-bind / /
  --bind <canonical-white-path> <canonical-white-path> ...
  --dev /dev --proc /proc -- bash -c <command>
```

- Linux 路径策略独立于 macOS：项目目录、全局白名单、`/tmp`/`$TMPDIR`/JVM 临时目录和 Linux 构建缓存。canonical 去重并合并父子挂载；缺失路径按目录精确创建，已有文件仍可单文件挂载；不扩大可写父目录。
- `/dev` 由 bwrap 创建，不额外 bind `/dev/stdin`、`stdout`、`stderr`（magic links 可能指向 pipe/socket，导致真实执行配置失败）。根目录及 `/proc`、`/dev`、`/sys` 重叠白名单拒绝，不能覆盖隔离挂载。
- 保留宿主临时目录读写，避免 tmpfs 隐藏跨命令产物和构建 daemon sockets。
- 独立 PID namespace，`/proc` 不暴露宿主进程；网络保持可用。外部 `setsid` 与原宿主进程组回收机制配合。
- `LinuxCommandSandbox` 每实例先验证版本，再用**同一完整挂载配置**跑 `/bin/true`；每次探测限 10 秒，持续排空输出且保留最多 8KiB。保留实际 bwrap 错误，不把任意故障一概归因于 AppArmor，也不建议全局关闭安全策略。
- bwrap 缺失或配置失败时，前台返回失败，后台返回启动错误；不执行用户命令，不产生后台任务。下个 turn 可重试。

## 6. 风险与边界（诚实清单）

- **Linux 真机验证仍待完成**：当前开发宿主是 macOS，Docker daemon 未启动。JVM 确定性测试覆盖挂载构造、缺失路径、canonical 合并、错误保留及拒绝执行；Linux 集成测试实际验证写白名单、外部只读、标准流、缓存首次创建、PID namespace、退出码及临时文件跨命令保留。
  Linux 上运行：`MEDERI_REQUIRE_LINUX_SANDBOX=1 ./gradlew :core:jvmTest --tests '*LinuxCommandSandboxTest'`。此标志要求 Linux 主机；Linux 上缺少可用 bwrap 直接失败，不能静默跳过。
  发行版的 AppArmor/user namespace 限制仍需宿主按实际错误配置；程序不安装 bwrap，也不修改宿主安全策略。
- **macOS 沙箱内信号不可用是平台限制，非 bug**：profile 无 process-signal 放行项（实测
  unbound variable）。进程回收已由宿主侧注册表补齐（§4.5）。实测影响：JVM `Process.destroy()`/
  `destroyForcibly()` 在沙箱内**静默失效**（不报错、子进程杀不掉）——Gradle 正常跑测试靠
  socket 协议收工不受影响，但**中断/超时强杀回退会漏 worker JVM**（恰好能被 stop_process 整组回收）；
  测试内自行 spawn 子进程再 destroy 的场景同样会漏。编译/测试主链路已验证可用。
- Seatbelt deprecated：Chrome/Anthropic/OpenAI 全在用，短期内不会消失；如被移除则降级警告
- Windows bash 裸跑：文件工具仍是硬边界；git/diff 是回滚网
- 全局白名单存 PreferencesStore（明文路径，无敏感数据）
- 读全盘含敏感文件：提示词约束 + 本地应用风险自担（D8 已拍板）
