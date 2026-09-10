# 执行沙盒设计（Sandbox）

> 沙盒已全链路落地。本文档记录生效中的设计决策与实测结论（macOS 实测通过；Linux 待真机验证）。

## 0. 已拍板决策（不要重新讨论）

| # | 决策 |
|---|---|
| D1 | **沙盒永远开，无开关**。不感知的安全不需要配置。设置页只放"全局写白名单"编辑器 + 状态展示 |
| D2 | **全局策略，项目级路径**。开关/白名单是应用级全局设置；写白名单路径按当前会话所属项目的 directories 动态生成 |
| D3 | **Windows 不做命令沙箱**：裸跑 + 警告前缀。文件工具的纯 Kotlin 路径校验全平台兜底 |
| D4 | **Linux 只检测不代装**：探测 bwrap；缺失 → 警告前缀 + 安装命令文案（每 turn 一次，不刷屏） |
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
| write_file / edit_file / apply_patch | 只能写白名单路径 | 纯 Kotlin（resolveForWrite containment 校验，WindowsPath 大小写不敏感天然兼容） | 全平台 |
| execute_command | OS 级沙箱：写=白名单，读=全盘，网络/进程放行 | macOS Seatbelt / Linux bwrap / Windows NONE | 按平台降级 |
| 是否写文件/建计划 | Triage Flow 分诊判断（提示词强制，见 `AGENTS.md §5.6`），沙盒是唯一代码级安全网 | SystemPrompts | 全平台 |

## 2. 架构

```
设置页白名单 → AppState(PreferencesStore "sandbox.extraPaths", JSON 数组)
            → mederi.sandboxExtraPaths（可变属性，实时生效）
SessionManagerImpl → TurnExecutor（每 turn 读取）
            → ToolFactory → ShellTools(CommandSandbox 实例)
CommandSandbox.wrap(command, allowedDirs, extraPaths) → ProcessBuilder argv
```

- CommandSandbox 每 turn 新建实例 → bwrap 探测每 turn 重跑（~10ms；用户装完无需重启）
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

## 5. bwrap argv 模板（Linux）

```
bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp
  --bind <dir> <dir> ...   ; 白名单目录（覆盖 ro-bind）
  --bind "$TMPDIR" "$TMPDIR" (若存在)
  bash -c <command>
```

未装 bwrap：输出前缀 `未检测到 bubblewrap，命令未沙箱运行。安装: sudo apt install bubblewrap / sudo dnf install bubblewrap / sudo pacman -S bubblewrap`。

## 6. 风险与边界（诚实清单）

- **Linux 未经真机验证**：macOS 全链路实测通过，Linux bwrap 路径只有代码 review。
  已做代码防御：bwrap 探测为**真实建沙箱烟测**（`bwrap --ro-bind / / ... /bin/true`），
  因为 Ubuntu 23.10+ 默认 AppArmor 限制 unprivileged user namespaces——bwrap 装着但建不了沙箱
  （Codex 社区已知坑）。烟测失败降级为无沙箱 + 精确区分两种原因（未安装 vs AppArmor 限制+解法）。
  **待真机验证**：Ubuntu 24.04（AppArmor 默认开）跑通/降级路径；Fedora（无限制）全功能；
  gradle 编译任务在 bwrap 内的 /dev、/proc、$TMPDIR 覆盖是否完整。
- Seatbelt deprecated：Chrome/Anthropic/OpenAI 全在用，短期内不会消失；如被移除则降级警告
- Windows execute_command 裸跑：文件工具仍是硬边界；git/diff 是回滚网
- 全局白名单存 PreferencesStore（明文路径，无敏感数据）
- 读全盘含敏感文件：提示词约束 + 本地应用风险自担（D8 已拍板）
