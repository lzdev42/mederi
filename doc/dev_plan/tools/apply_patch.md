# apply_patch（多文件补丁编辑工具）开发文档【重点】

> 源码出处：
> - 工具注册/验证/执行编排：`codex-rs/core/src/tools/handlers/apply_patch.rs` 与 `codex-rs/core/src/tools/handlers/apply_patch_spec.rs`
> - 解析器（grammar + 流式解析）：`codex-rs/apply-patch/src/parser.rs`、`streaming_parser.rs`
> - 变更计算与模糊匹配：`codex-rs/apply-patch/src/file_update.rs`、`seek_sequence.rs`
> - 解析→验证→应用主流程：`codex-rs/apply-patch/src/lib.rs`、`invocation.rs`
> - 执行 runtime（sandbox/approval/delta）：`codex-rs/core/src/tools/runtimes/apply_patch.rs`
>
> 定位：**补充** Koog 的 `EditFileTool`，不是替换。二者关系见文末「与 EditFileTool 的对比与分工」。

## 一、功能描述

`apply_patch` 让模型用一份**自由格式（freeform）的补丁文本**一次性对**多个文件**执行新增、删除、更新（含重命名移动）操作。

它相对「单文件单段替换」的编辑方式，强大在以下几点（这就是当初决定补它的原因）：

1. **一次调用、多文件、多 hunk**：一份 patch 内可混合 `Add File` / `Delete File` / `Update File`（Update 内可有多个 `@@` chunk），一次工具调用完成整组修改，减少往返。
2. **上下文定位 + 模糊匹配**：`Update File` 的每个 chunk 用「上下文行 + 删除行 + 新增行」表达，定位基于 `seek_sequence` 的**多级模糊匹配**（精确 → 去尾空白 → 去首尾空白 → Unicode 标点归一化），比「原文精确替换」容忍度大得多——这正是模型生成的 diff 常出现的微小偏差（多余空格、弯引号/连字符）能成功落地的原因。
3. **顺序推进 + 变更区域化**：多个 chunk 按上下文依次定位（前一个 chunk 结束后从其后继续找），保证非相邻区域的多次修改都能精确命中，不会相互干扰。
4. **原子化验证（dry-run verify）**：应用前先对每个 hunk 做**完整校验**——读原文件、用 chunks 计算出新内容、并把「匹配失败」在写盘前就报错返回（`CorrectnessError`），避免半改半错。
5. **支持重命名/移动**：`*** Move to: <path>` 实现文件移动 + 内容修改。
6. **支持 EOF 追加**：`*** End of File` 标记允许明确「在文件末尾追加」，对无换行的末行处理有容错。
7. **结构化 diff 产物**：应用后能给出 `unified_diff`（供 UI 展示），并维护 `AppliedPatchDelta`（含 old/new 内容），支持失败时「已提交的部分变更」回传（delta tracking）。
8. **权限/沙箱/审批集成**：针对 patch 触达的所有文件路径聚合一次性权限审批（而非逐文件），路径自动解析 + sandbox 校验 + 审批流程完整集成。

工具名常量为 `apply_patch`，它是 **FREEFORM 工具**：参数不是 JSON，而是一段遵循 Lark grammar 的补丁文本（`*** Begin Patch` … `*** End Patch`），**不能包在 JSON 里**。

## 二、Codex 的参数定义（自由格式 schema）

工具是 `ToolSpec::Freeform`（[apply_patch_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/apply_patch_spec.rs)），`format.type = "grammar"`、`format.syntax = "lark"`。`definition` 即 [apply_patch.lark](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/apply_patch.lark)：

```
start: begin_patch hunk+ end_patch
begin_patch: "*** Begin Patch" LF
end_patch: "*** End Patch" LF?

hunk: add_hunk | delete_hunk | update_hunk
add_hunk: "*** Add File: " filename LF add_line+
delete_hunk: "*** Delete File: " filename LF
update_hunk: "*** Update File: " filename LF change_move? change?

filename: /(.+)/
add_line: "+" /(.*)/ LF -> line

change_move: "*** Move to: " filename LF
change: (change_context | change_line)+ eof_line?
change_context: ("@@" | "@@ " /(.+)/) LF
change_line: ("+" | "-" | " ") /(.*)/ LF
eof_line: "*** End of File" LF
```

多环境模式下会注入可选头 `environment_id`：

```
start: begin_patch environment_id? hunk+ end_patch
environment_id: "*** Environment ID: " filename LF
```

### 三种 hunk

```text
*** Begin Patch

*** Add File: src/new.txt
+line1
+line2

*** Delete File: src/old.txt

*** Update File: src/a.txt
*** Move to: src/b.txt          ← 可选：移动（移动后仍可带 change）
@@ def foo():                    ← 可选 change_context：单行定位上下文
 def bar():                     ← 上下文行（以空格开头，两侧都有）
-old line                        ← 删除行（-）
+new line                        ← 新增行（+）
@@                              ← 空 context 开始新 chunk
+append line
*** End of File                  ← 可选：本 chunk 必须命中文件末尾（用于末尾追加）

*** End Patch
```

要点：
- **Update 的 chunk**：每个 `@@`（可带上下文文本）开启一个 chunk；chunk 内是 `-`/`+`/` ` 行。`old_lines` 必须在 `change_context` 之后出现、且按文件顺序排列。
- **`*** End of File`**：标记最后一个 chunk 的 `old_lines` 必须命中文件末尾（容忍尾部空行差异），配合纯新增（old_lines 为空）实现「追加到文件尾」。
- **`*** Move to:`**：必须在第一个 chunk 之前；表示把该文件移动到新路径（同时可修改内容）。
- 同一路径在一个 patch 内只允许出现一次（多 hunk 目标同一路径 → 校验报错）。

工具描述：`"The apply_patch tool can be used to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON."`

### 解析模式（Lenient）

`parse_patch` 默认走 **Lenient**（宽松）模式：先按 Strict 校验首尾标记，失败后再尝试剥离 heredoc 包裹（`<<EOF` / `<<'EOF'` / `<<"EOF"` … `EOF`），这是为了兼容 gpt-4.1 用 `local_shell` 的 heredoc 形式调用的情况。Mederi 直接传裸文本，通常走 Strict 即够。

## 三、Codex 的执行逻辑

分两大阶段：**解析/验证（apply-patch crate）** 与 **执行/审批（core runtime）**。

### 阶段 A：解析（parser.rs + streaming_parser.rs）

1. **边界校验**（`check_patch_boundaries_*`）：首行必须是 `*** Begin Patch`，末行必须是 `*** End Patch`；Lenient 模式先剥离可选 heredoc 包裹再校验。
2. **流式解析**（`StreamingPatchParser`）：按行维护状态机（`NotStarted → StartedPatch → AddFile / DeleteFile / UpdateFile → EndedPatch`），逐行归类：
   - `Add File` 后的 `+` 行累积为 `contents`；
   - `Delete File` 后无内容；
   - `Update File` 后：`*** Move to:`（仅第一个 chunk 前）→ 设置 `move_path`；`@@`/`@@ <ctx>` 开启新 chunk（记录 `change_context`）；` ` 上下文行同时进 old/new 并记录 `context_line_indices`；`-` 进 `old_lines`；`+` 进 `new_lines`；`*** End of File` 置 `is_end_of_file`。
3. 产物：`Vec<Hunk>`（`AddFile{path, contents}` / `DeleteFile{path}` / `UpdateFile{path, move_path, chunks}`）。
4. **错误**：`ParseError::InvalidPatchError`（整体结构错）或 `InvalidHunkError { message, line_number }`（某 hunk 内容错，带行号）。

### 阶段 B：验证（invocation.rs `verify_apply_patch_args`，dry-run）

对每个 hunk 做「不改磁盘」的验证，产出一份 `ApplyPatchAction`（所有路径已解析为绝对路径）：

- `AddFile`：直接记录 `Add{content}`。
- `DeleteFile`：读取文件现有内容存入 `Delete{content}`（读不到报错）。
- `UpdateFile`：调用 `derive_new_contents_from_chunks` 计算新内容 + 生成 `unified_diff`（见阶段 C），记录 `Update{unified_diff, move_path, new_content}`；`move_path` 相对 effective_cwd 解析。
- **重复路径检测**：`changes` 是 `HashMap<PathUri, ...>`，同一路径二次出现 → `"multiple operations target {path}"`。
- effective_cwd：heredoc 里 `cd <path> &&` 形式会改变 workdir；直接传文本时用当前 cwd。
- 验证失败 → `CorrectnessError`（解析错误 / IO 错误 / 找不到 old_lines 等），**此时磁盘零改动**。

### 阶段 C：变更计算（file_update.rs `compute_replacements` + seek_sequence.rs）

`derive_new_contents_from_chunks` 流程（Update hunk）：

1. 读原文件，按行拆分（`NormalizeToLf` 模式去掉末尾空串，使行数与 `diff` 一致）。
2. 对每个 chunk，维护全局 `line_index`（定位游标，只增不减）：
   - 若有 `change_context`：用 `seek_sequence` 找该上下文行的位置 → `line_index = 命中位置 + 1`；找不到 → 报 `"Failed to find context '{ctx}' in {path}"`。
   - 若 `old_lines` 为空（纯新增）：插入点取文件末尾（`is_end_of_file` 语义下为末尾前），记录 `(insertion_idx, 0, new_lines)`。
   - 否则用 `seek_sequence` 从 `line_index` 起找 `old_lines` 的连续出现；若失败且 `old_lines` 末位是空串（代表末尾换行哨兵），去掉末位重试（`pattern`/`new_slice` 同步截断）；找到后记录 `(start, old_len, new_slice)`，`line_index = start + pattern.len()`。
   - 找不到 → 报 `"Failed to find expected lines in {path}:\n{old_lines...}"`。
3. 所有 replacement 按 `start` 排序，然后**从后往前**应用（先改靠后的，避免位移影响靠前的）：删除 `old_len` 行、插入 `new_slice`。
4. 最后补回末尾换行，`join("\n")` 得到新内容；用 `similar::TextDiff` 生成 unified_diff 供展示。

`PreserveLineEndings` 模式：用 `SourceFile` 保留原行结束符，只替换非上下文行，上下文行的原始内容和终止符原样保留（混合行结束符文件安全）。

### `seek_sequence` 的多级模糊匹配（seek_sequence.rs）

按**从严到宽**四级找 `pattern` 在 `lines[start..]` 中的连续匹配，返回命中起点：

1. **精确**：`lines[i..i+n] == pattern`。
2. **忽略尾部空白**：`trim_end` 后逐行相等。
3. **忽略首尾空白**：`trim` 后逐行相等。
4. **Unicode 标点归一化**：`normalise()` 把弯引号/各种连字符/不断行空格等映射到 ASCII 等价物后比较：
   - 各种 dash/hyphen（U+2010–2015、U+2212）→ `-`
   - 花式单引号（U+2018–201B）→ `'`
   - 花式双引号（U+201C–201F）→ `"`
   - NBSP 及各种空格（U+00A0、U+2002–200A、U+202F、U+205F、U+3000）→ 空格

防御性处理：空 pattern 返回 `start`（no-op）；`pattern.len() > lines.len()` 直接 `None`（防越界 panic）。

**`eof=true` 时**：先尝试从 `lines.len() - pattern.len()` 处开始匹配（优先命中文件末尾），失败再回退从 `start` 搜。

### 阶段 D：执行与审批（handlers/apply_patch.rs + runtimes/apply_patch.rs）

1. handler 收到 `ToolPayload::Custom { input: patch_input }` → `parse_patch` → 校验失败返回 `"apply_patch verification failed: {err}"`。
2. 多环境时解析 `environment_id`；`resolve_tool_environment` 选定环境；`require_environment_id` 校验权限。
3. `verify_apply_patch_args_with_mode` 做 dry-run 验证（阶段 B/C）→ `Body(ApplyPatchAction)`。
4. **权限聚合**：`effective_patch_permissions` 收集 patch 触达的所有路径（含 move 目标），对比 sandbox 策略，对不可写目录生成 `AdditionalPermissionProfile`，与会话/turn 已授予权限合并；`apply_granted_turn_permissions` 申请。
5. **审批事件**：`ToolEmitter::apply_patch_for_environment` 发出 `PatchApplyUpdated` 类事件（含变更摘要），走 orchestrator 审批。
6. **正式应用**：`ApplyPatchRuntime::run` 调 `apply_patch_with_mode`（lib.rs `apply_hunks_to_files`）逐个 hunk 落到文件系统：
   - Add：写文件（父目录不存在则 `create_directory(recursive)` 重试）。
   - Delete：确认非目录 → `remove`。
   - Update：无 move → 直接写新内容；有 move → 先写目标文件（父目录不存在重试）、再删除源文件；删除源失败时，**返回已提交的目标写 delta**（delta tracking）。
   - 每个 hunk 应用后累积 `AppliedPatchDelta { changes, exact }`；写失败把 `exact` 置 false。
7. **输出摘要**：`print_summary` 输出 git 风格摘要：
   ```
   Success. Updated the following files:
   A <added>
   M <modified>
   D <deleted>
   ```

### 错误处理汇总

- 解析错误（结构/行号）→ `InvalidPatchError` / `InvalidHunkError`。
- 验证失败 → `"apply_patch verification failed: ..."`（磁盘零改动）。
- 写盘失败 → 报错 + 返回已提交 delta（`ApplyPatchFailure.delta()`），`exact=false`。
- 空 patch（无 hunk）→ `"No files were modified."`。
- 非 `Custom` payload → `"apply_patch handler received unsupported payload"`。

## 四、输出格式

成功时给模型的文本（`ApplyPatchToolOutput::from_text(content)`）即 `print_summary` 输出：

```
Success. Updated the following files:
A path/to/added.txt
M path/to/modified.txt
D path/to/deleted.txt
```

- `success = true`。
- 另通过事件/rollout 输出 `changes`（`HashMap<PathBuf, FileChange>`，含 unified_diff、move_path、new_content），供 UI 展示 diff 与审批。
- code 模式返回 `{}`（不承载业务数据，变更通过事件分发）。

## 五、Mederi 实现建议

### 5.1 与 EditFileTool 的对比与分工（为什么是补充不是替换）

| 维度 | Koog `EditFileTool` | Codex `apply_patch`（拟补） |
| --- | --- | --- |
| 参数形态 | 结构化 JSON：`{path, original, replacement}` | 自由格式补丁文本（Lark grammar） |
| 单次修改范围 | 单文件、单段原文替换 | 多文件、每文件多 chunk |
| 匹配宽容度 | 处理 case 与空白不匹配（Koog 自带） | 四级模糊匹配 + Unicode 标点归一化 |
| 操作类型 | 替换（仅 Update） | Add / Delete / Update / Move / EOF 追加 |
| 校验 | 替换前查找，失败报错 | 全 patch dry-run 验证，原子性更强 |
| 上下文定位 | 无（纯原文匹配） | `@@ 上下文行` 精确定位多 chunk |
| 产物 | 单一结果 | unified_diff + AppliedPatchDelta（增量回传） |

**分工建议**：
- `EditFileTool` 保留——它简单、直接、schema 明确，适合「模型明确知道要替换的确切片段」的常见小改。
- `apply_patch` 作为**高级补充**——适合一次多文件重构、需要上下文定位的大改动、以及 move/delete/add 组合操作。模型按其需要选择。

### 5.2 核心数据结构（Kotlin）

```kotlin
sealed class Hunk {
    data class AddFile(val path: String, val contents: String) : Hunk()
    data class DeleteFile(val path: String) : Hunk()
    data class UpdateFile(
        val path: String,
        val movePath: String? = null,
        val chunks: List<UpdateFileChunk>,
    ) : Hunk()
}

data class UpdateFileChunk(
    val changeContext: String? = null,     // @@ <ctx> 的上下文单行
    val oldLines: List<String>,            // - 行
    val newLines: List<String>,            // + 行
    val isEndOfFile: Boolean = false,      // *** End of File
)
```

`args` 可用一个 `patch: String` 字段承载整段补丁文本（freeform 语义），或直接以 raw string 作为工具输入（对齐 `ToolPayload::Custom { input }`）。

### 5.3 分模块实现

1. **解析器** `PatchParser`（对应 parser.rs + streaming_parser.rs）：
   - `parse(patch: String): List<Hunk>`——校验 `*** Begin Patch` / `*** End Patch` 首尾，逐行状态机归类成 `Hunk`。
   - 初版可直接一次性解析；若后续要「流式进度」，可拆出 `StreamingPatchParser.pushDelta(fragment)`（对齐 Codex 的参数流式事件）。
   - 错误带行号：`"invalid hunk at line {n}: {msg}"`。

2. **模糊匹配** `seekSequence(lines, pattern, start, eof): Int?`（对应 seek_sequence.rs）：
   - 四级：精确 → trimEnd → trim → `normalise()`（Unicode 标点归一化，映射表照抄 Codex）。
   - 防御：空 pattern 返回 `start`；`pattern.size > lines.size` 返回 `null`。
   - `eof` 时优先从 `lines.size - pattern.size` 处起搜。

3. **变更计算** `computeReplacements(originalLines, chunks): List<Replacement>`（对应 file_update.rs）：
   - 逐 chunk：`changeContext` 定位推进游标；`oldLines` 空 → 末尾插入；否则 `seekSequence` 找连续出现（末位空串哨兵重试逻辑照搬）；找不到抛「Failed to find expected lines …」。
   - `Replacement = (startIndex, oldLen, newLines)`，按 start 升序收集。
   - 应用：**从后往前**执行（先改靠后的，避免位移）。
   - 末尾补 `\n`、`join("\n")` 得新内容。
   - `PreserveLineEndings` 可延后（初版只做 `NormalizeToLf`，与 Koog 行为一致即可）。

4. **验证 + 应用**（对应 invocation.rs + lib.rs）：
   - `verify(parsed, cwd, fs): ApplyPatchAction`：Add 直接记；Delete 读原内容；Update 计算新内容 + unified_diff（Kotlin 端可用 `com.github.difflib`/`io.github.java-diff-utils` 生成 unified diff）；检测重复路径。
   - `apply(action, cwd, fs)`：逐 hunk 落盘（Add 写 + 父目录创建重试；Delete 确认非目录后删；Update 写 / Move 先写目标再删源，删源失败返回已提交 delta）。
   - 复用 Koog 的 `FileSystemProvider.ReadWrite<Path>`（与 `EditFileTool` 同一抽象），无需新 IO 层。

5. **工具类**：

```kotlin
class ApplyPatchTool(
    private val fs: FileSystemProvider.ReadWrite<Path>,
    private val cwdProvider: () -> Path,           // 当前环境 cwd
) : Tool<ApplyPatchArgs, ApplyPatchResult>("apply_patch") {

    override val description: String =
        "The apply_patch tool can be used to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON."

    override suspend fun execute(args: ApplyPatchArgs): ApplyPatchResult {
        // 1. 解析
        val hunks = try { PatchParser.parse(args.patch) }
            catch (e: ParseException) {
                throw ToolException("apply_patch verification failed: ${e.message}")
            }
        // 2. dry-run 验证（不改磁盘）
        val action = verify(hunks, cwdProvider(), fs)   // 失败抛 ToolException，磁盘零改动
        // 3. 应用
        val summary = apply(action, cwdProvider(), fs)  // 返回 A/M/D 列表
        return ApplyPatchResult(summaryText = buildSummary(summary))
    }
}
```

### 5.4 执行逻辑要点（对齐 Codex 的关键行为）

- **先验证后应用**：所有 chunk 的匹配/内容计算在写盘前完成，任何失败 → 报错且磁盘零改动（这是相对逐文件编辑的核心价值）。
- **逐 chunk 顺序定位**：游标只增不减，保证非相邻区域多次修改互不干扰。
- **多级模糊匹配**：务必包含四级降级 + Unicode 归一化（这是模型生成 patch 成功率的关键）。
- **EOF 追加**：`*** End of File` 语义要支持（纯新增 chunk 命中文件尾）。
- **Move 语义**：先写目标（父目录重试）、后删源；删源失败回传已提交 delta。
- **重复路径检测**：同一文件多 hunk 报错。
- **摘要输出**：git 风格 `Success. Updated the following files:\nA/M/D ...`。
- **FREEFORM 声明**：工具必须标记为 freeform（非 JSON 参数），防止模型把 patch 包成 JSON。
- 可选延后：`environment_id`（多环境）、`PreserveLineEndings`、参数流式进度事件（`PatchApplyUpdated`）、delta/审批与 UI diff 集成。

### 5.5 依赖

- **FileSystemProvider.ReadWrite&lt;Path&gt;**：读写文件、建目录、删文件（复用 Koog 现有抽象）。
- **diff 库**（可选）：生成 `unified_diff` 供 UI/审批展示（`io.github.java-diff-utils` 等）。
- **权限/审批**（对齐 Mederi 现有能力）：对 patch 触达路径做一次性权限申请（若有）。
- **不需要** tokenizer、HistoryStore、ContextManager。
- 若 Koog 的 `Tool` 抽象不支持 freeform（非 JSON args）输入，需先扩展 `Tool` 以支持「原始文本参数」（等价 `ToolPayload::Custom`），这是实现前置项。
