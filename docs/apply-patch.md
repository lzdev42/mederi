# apply_patch 工具

## 真理源与范围

本地补丁执行器是纯 Kotlin/JVM 实现，不要求用户安装 Codex、Rust、git、patch 或额外服务。
主代理与 EXECUTOR 在 APPROVAL/AUTONOMOUS 模式均可调用，RESEARCHER 无写入权限。
工具由 `ToolFactory` 注册；桌面与无头 server 共用 core，不新增 AiCore 契约或 server endpoint。

参考版本固定为 [openai/codex 23934d31b15b1127c83535f84854e5009f7cd51d](https://github.com/openai/codex/tree/23934d31b15b1127c83535f84854e5009f7cd51d/codex-rs/apply-patch)。
Kotlin 解析器、匹配算法和 grammar 对齐这个提交，不能把“兼容 Codex”理解为自动跟踪未来版本。
上游 LICENSE、NOTICE、25 个场景的原始字节及版本记录保存在
`core/src/jvmTest/resources/apply-patch-upstream/`。

## 每次发送给模型的配置

`tools/patch/ApplyPatchProtocol.kt` 集中管理工具名、格式教程、grammar 和参数序列化。

| 请求路径 | 工具声明 | 模型调用输入 | 结果回传 |
|---|---|---|---|
| 官方 `https://api.openai.com` Responses | `type=custom`、`name=apply_patch`、Codex freeform description、`format={type:grammar,syntax:lark,definition:…}` | 原始补丁文本，不能包 JSON | `custom_tool_call_output`，原 `call_id` |
| 其他 OpenAI 兼容 Responses | `type=function`、完整格式教程、必填 `patch:string` schema | JSON 对象的 `patch` 字段 | `function_call_output`，原 `call_id` |
| Chat Completions / 其他供应商 | Koog 标准 function 声明和同一格式教程 | JSON 对象的 `patch` 字段 | 原供应商标准工具结果 |

不凭模型名猜测自定义端点的 grammar 支持，不自动重试切换协议。官方端点的判定检查 HTTPS、准确 host、无 userinfo 和标准端口。
这是 Codex 的 custom/freeform 工具，不是 Responses 中另外一种内建 `type=apply_patch` operation API。
相关官方说明见 [Codex prompting guide](https://developers.openai.com/cookbook/examples/gpt-5/codex_prompting_guide)。

Koog 内部统一使用可序列化参数 DTO。Responses 非流式 custom 输入经 DTO 编码；流式
`response.custom_tool_call_input.delta` 到达时立即以 kotlinx.serialization 编码字符串片段并发出参数增量。
参数外壳从同一 DTO serializer 的输出分区生成，不手写字段或拼接未转义的模型数据。
这只是编码器的分片输出，不对外部 JSON 做字符串解析、修复或猜测。
custom/function 工具按 output index 分别聚合，工具间交错或正文不会提前结束另一工具；emoji 跨片时最多暂存一个高代理字符，确保每个发出的片段经 UTF-8/SSE 不损坏。
done/终态会检查输入一致性，缺结束、不完整响应或前后终态不一致均安全报错，不继续执行工具；完成后的历史再次发送时还原 custom 原始输入。
工具结果按 call ID 作为独立结果项发送，不能当用户普通正文忽略。

完整 grammar 随每次工具声明发送；通用 function 路径的 description 包含全部文件操作、上下文、EOF、示例、覆盖语义和失败处理。
主代理与执行器的系统静态提示词还说明优先使用 apply_patch、按实际声明选择 raw/JSON 包装、匹配失败先重读、写失败先核对回滚状态、同路径不能并发写。

## 补丁格式与反馈

```text
*** Begin Patch
*** Add File: src/new.txt
+first line
*** Update File: src/old.txt
*** Move to: src/renamed.txt
@@ optional exact source context line
 unchanged context
-old text
+new text
*** End of File
*** Delete File: src/obsolete.txt
*** End Patch
```

- Add 每行前缀 `+`；Update 用空格表示上下文、`-` 删除、`+` 新增，支持多个 `@@` 块。
- `@@` 后可带一行定位上下文；不使用 unified diff 的数字范围。
- Move 紧跟 Update，修改后移动；可覆盖已有普通文件。Add 也可覆盖已有普通文件，diff 保留覆盖前内容。
- End of File 可选，要求对应块命中文件末尾；查找游标不向前回退。
- 匹配依次尝试精确、忽略尾空白、忽略首尾空白、Unicode 标点归一化；上下文仍保留磁盘原文，不将模糊匹配后的文本写回。
- 保留 LF、CRLF、独立 CR 和混合行尾；新增/替换行采用源文件首种行尾，新增文件使用 LF。更新无终止换行的文件会补换行，删光内容得到空文件，与固定上游一致。
- 成功返回 `Success. Updated the following files:` 和实际 A/M/D 路径；相同字节的操作返回 `No files were modified.`。
- 语法和预检失败返回 `Error: apply_patch verification failed: … No files were modified.`，包含行号、路径或缺失上下文。
- 写入失败说明回滚是否恢复所有文件；未恢复时列出残余 A/M/D、无法检查路径与回滚错误。变更追踪失败会明确提示磁盘已修改，不能误报成完全未执行。

## 文件写入边界

执行顺序：解析 → 解析全部源/移动目标 → 路径/类型预检 → 全部路径一次 try-lock（稳定键租约） →
原始字节与权限快照 → 整体 dry-run → 再校验 → 逐文件替换/删除 → diff/touched → finally 解锁。

路径受项目与显式全局白名单约束；相对路径只按项目目录解析。拒绝空路径、目录、父子目标冲突、canonical 重复目标、移动源目标重叠和符号链接（包括悬空链接及根下的祖先链接）。
项目根自身可经 canonical 定位，但不能作为文件操作。严格 UTF-8 解码/编码，拒绝 NUL 与非 UTF-8 文件，避免二进制被替换字符破坏。
移动源和目标同时锁定；覆盖目标的原始字节和 diff 独立保留；成功及未回滚的实际变化进入 TurnDiffTracker 和执行器 touched files。

与快照字节相同的操作直接跳过，不替换 inode 或修改 mtime。锁租约保存获取时的 canonical 键，释放不重新解析可能已经改变的路径。
每个写入采用同目录临时文件替换，支持时原子 move，保留已有 POSIX 权限（不承诺保留全部扩展属性/时间戳）；写失败按原始字节快照尽力回滚并清理本次新建空目录。
这不是跨文件的文件系统事务：进程崩溃不能运行回滚；外部进程不参与 FileWriteRegistry。
路径重检查会拦截常规链接变化，但 Java pathname API 无法完全排除恶意外部进程在检查与操作间替换祖先目录。
因此不把整个补丁描述成崩溃原子或对抗任意外部文件系统竞态的安全沙箱。

相对上游的有意差异：Mederi 整体预检（官方场景 015 的后续失败不会留下前面的新增），拒绝多操作命中同一路径和符号链接；上游的 Environment ID 是远程执行路由，本地工具不接受。

## 验证与后续升级

```bash
./gradlew :core:jvmTest
```

重点测试：`PatchCompatibilityTest`（25 官方磁盘快照场景 + 算法边界）、
`FileSystemToolsApplyPatchTest`（路径、锁、回滚、权限、diff/touched）、
`ApplyPatchTransportTest`（真实请求捕获与 SSE/非流式回放）、
`ApplyPatchRegistrationTest`（角色/模式和提示词）。测试不访问真实 LLM，不消耗 API token。

升级时固定新的官方提交，比较 parser/streaming_parser、seek_sequence、file_update、text_file、
`core/assets/tools/apply_patch.lark` 和 freeform tool spec；更新 Kotlin、grammar、上游夹具和版本归属记录后运行全部 core 测试。
任何安全或事务差异必须继续明确记录，不能为了通过上游测试而放松本地写入边界。
仅服务商实际调用验收需要可用模型服务；本次开发不要求启动 APP/server。
