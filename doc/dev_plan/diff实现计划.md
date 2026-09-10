# Diff 实现计划

> 状态：待实现  
> 目标：让 Mederi core 能够追踪一次 turn 内产生的文件变更，并对外提供查询接口，供 UI diff 面板使用。

---

## 1. 设计决策

参考 Codex 的 `TurnDiffTracker` 思路，但扩展到 mederi 自己的写工具：

- **不依赖单独的 diff 工具**：diff 由所有写工具共同贡献，而不是一个独立工具生成。
- **以 patch delta tracker 为主**：`apply_patch` / `write_file` / `edit_file` 执行后把变更汇报给 `TurnDiffTracker`。
- **目录快照兜底**：turn 结束时可选扫描目录，捕获 `execute_command` 等未汇报的变更。
- **持久化**：diff 与会话绑定，支持按 `sessionId` / `messageId` 查询。

---

## 2. 核心数据结构

### 2.1 变更类型

```kotlin
package xyz.mederi.tools.diff

enum class FileChangeStatus { ADDED, MODIFIED, DELETED }

data class FileChange(
    val path: String,              // 相对于项目目录的相对路径
    val status: FileChangeStatus,
    val before: String?,           // 变更前内容（ADDED 为 null）
    val after: String?,            // 变更后内容（DELETED 为 null）
)

data class TurnDiff(
    val sessionId: String,
    val messageId: String?,        // 产生该 diff 的 assistant message id
    val changes: List<FileChange>,
    val unifiedDiff: String,       // git 风格 unified diff
    val createdAt: String          // ISO 8601
)
```

### 2.2 DiffStore 接口

```kotlin
package xyz.mederi.store

interface DiffStore {
    suspend fun save(diff: TurnDiff)
    suspend fun list(sessionId: String): List<TurnDiff>
    suspend fun get(sessionId: String, messageId: String?): TurnDiff?
    suspend fun delete(sessionId: String)
}
```

内置实现：
- `InMemoryDiffStore`：内存实现，供测试和无持久化场景。
- `SqliteDiffStore`：基于现有 SQLite 数据库，新增 `diffs` 表。

---

## 3. TurnDiffTracker

### 3.1 职责

- 在单次 turn 内跟踪文件变更；
- 接收来自写工具的变更通知；
- 生成统一的 `unifiedDiff`；
- turn 结束时把结果写入 `DiffStore`。

### 3.2 数据结构

```kotlin
package xyz.mederi.tools.diff

class TurnDiffTracker(
    private val sessionId: String,
    private val projectDirectories: List<String>
) {
    private val baselineByPath = mutableMapOf<String, String>()
    private val currentByPath = mutableMapOf<String, String>()
    private var valid: Boolean = true

    /** 记录 apply_patch 产生的变更 */
    fun trackPatch(changes: List<PatchChange>) { ... }

    /** 记录 write_file / edit_file 产生的变更 */
    fun recordWrite(path: String, oldContent: String?, newContent: String) { ... }

    /** 记录文件删除 */
    fun recordDelete(path: String, oldContent: String) { ... }

    /** 目录快照兜底，捕获 execute_command 等未汇报变更 */
    fun captureSnapshot() { ... }

    /** 生成最终 diff */
    fun buildDiff(messageId: String?): TurnDiff { ... }
}
```

### 3.3 变更合并规则

- 同一文件多次更新：保留最终内容，baseline 为第一次变更前的内容；
- Add 后 Delete： net 效果根据 turn 结束时文件是否存在决定；
- Delete 后 Add（同名文件）：视为 Update；
- move/rename：按 Delete + Add 处理，或保留 move 语义。

---

## 4. 与工具的集成

### 4.1 apply_patch

`ApplyPatchTool` 执行 `applyHunks` 时已经能拿到 `VerifiedChange`，改造后：

```kotlin
private fun applyHunks(
    hunks: List<PatchHunk>,
    verified: Map<String, VerifiedChange>,
    diffTracker: TurnDiffTracker?
): String {
    // ... 现有落盘逻辑
    diffTracker?.trackPatch(changes)
    // ... 返回摘要
}
```

把 `VerifiedChange` 转换为 `PatchChange` 喂给 tracker。

### 4.2 write_file

`WriteFileTool.execute` 改造后：

```kotlin
override suspend fun execute(args: WriteFileArgs): String {
    val file = resolveAndValidate(args.path, mustExist = false, mustBeFile = true)
    file.parentFile?.mkdirs()
    val oldContent = file.takeIf { it.exists() }?.readText()
    file.writeText(args.content)
    diffTracker?.recordWrite(displayPath(file), oldContent, args.content)
    return "Written ${args.content.length} chars to ${displayPath(file)}"
}
```

### 4.3 edit_file

`EditFileTool.execute` 改造后：

```kotlin
override suspend fun execute(args: EditFileArgs): String {
    val file = resolveAndValidate(args.path, mustExist = true, mustBeFile = true)
    val content = file.readText()
    val updated = content.replaceFirst(args.original, args.replacement)
    file.writeText(updated)
    diffTracker?.recordWrite(displayPath(file), content, updated)
    return "Edited ${displayPath(file)}"
}
```

### 4.4 execute_command

`execute_command` 不主动追踪。turn 结束时调用 `TurnDiffTracker.captureSnapshot()` 做兜底扫描，捕获命令行改的文件。

captureSnapshot 逻辑：
- 扫描 projectDirectories 下的所有文件；
- 对于未被工具汇报过的文件，对比 baseline（如未记录则读取当前磁盘）和当前内容；
- 只处理文本文件，二进制文件跳过。

---

## 5. TurnExecutor 集成

### 5.1 创建与销毁

每次 `runTurn` 开始时创建 `TurnDiffTracker`，结束时保存 diff：

```kotlin
private suspend fun runTurn(...) {
    val diffTracker = TurnDiffTracker(sessionId, project.directories)

    // ... 工具注册时传入 diffTracker
    val toolRegistry = buildToolRegistry(
        tools, mode, directories, sessionId, model,
        newContextWindowFlag, diffTracker
    )

    try {
        agent.run(inputText, sessionId = sessionId)
        // turn 结束兜底扫描
        diffTracker.captureSnapshot()

        val messages = historyStore.load(sessionId)
        val assistantMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }

        val diff = diffTracker.buildDiff(assistantMessage?.id)
        diffStore.save(diff)

        sessionStore.update(sessionId, SessionStatus.IDLE)
        emit(sessionId, EventType.MESSAGE_COMPLETED, assistantMessage?.id)
    } catch (e: Throwable) {
        // ... 错误处理
    }
}
```

### 5.2 ToolFactory 签名调整

```kotlin
fun build(
    toolNames: List<String>,
    mode: AgentMode,
    directories: List<String>,
    sessionId: String,
    historyStore: HistoryStore,
    eventBus: MutableSharedFlow<MederiEvent>,
    modelContextWindow: Int?,
    newContextWindowFlag: AtomicBoolean,
    diffTracker: TurnDiffTracker? = null
): ToolRegistry
```

---

## 6. API 扩展

### 6.1 SessionApi

```kotlin
interface SessionApi {
    // ... 现有方法

    suspend fun getFileDiffs(sessionId: String, messageId: String? = null): List<FileDiff>
}
```

### 6.2 FileDiff 领域模型

```kotlin
package xyz.mederi.domain.model

data class FileDiff(
    val filePath: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int
)
```

### 6.3 SessionApiImpl

```kotlin
override suspend fun getFileDiffs(sessionId: String, messageId: String?): List<FileDiff> {
    val diff = diffStore.get(sessionId, messageId)
        ?: diffStore.list(sessionId).lastOrNull()
        ?: return emptyList()
    return diff.changes.map { change ->
        FileDiff(
            filePath = change.path,
            before = change.before ?: "",
            after = change.after ?: "",
            additions = countAdditions(...),
            deletions = countDeletions(...)
        )
    }
}
```

---

## 7. 数据库 Schema

在现有 `mederi.db` 中新增 `diffs` 表：

```sql
CREATE TABLE diffs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    message_id TEXT,
    changes_json TEXT NOT NULL,
    unified_diff TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_diffs_session_id ON diffs(session_id);
CREATE INDEX idx_diffs_message_id ON diffs(message_id);
```

`changes_json` 存储 `List<FileChange>` 的 JSON 序列化结果。

---

## 8. UI Bridge 对接

`MederiAiCore.getFileDiffs(conversationId, messageId?)` 直接转调 `mederi.sessions.getFileDiffs(sessionId, messageId)`，映射为 UI 的 `List<FileDiff>`。

---

## 9. 测试计划

1. **单元测试**：
   - `TurnDiffTrackerTest`：验证多次更新合并、Add+Delete、Delete+Add、move 等场景。
   - `DiffStoreTest`：验证 SQLite 持久化。

2. **集成测试**：
   - `ApplyPatchDiffTest`：apply_patch 后能通过 `getFileDiffs` 查到 diff。
   - `WriteFileDiffTest`：write_file 后能查到 diff。
   - `EditFileDiffTest`：edit_file 后能查到 diff。
   - `ExecuteCommandDiffTest`：execute_command 改文件后，通过 snapshot 兜底能查到 diff。

---

## 10. 文件变更清单

新增：
- `core/src/main/kotlin/xyz/mederi/tools/diff/TurnDiffTracker.kt`
- `core/src/main/kotlin/xyz/mederi/tools/diff/PatchChange.kt`
- `core/src/main/kotlin/xyz/mederi/domain/model/FileDiff.kt`
- `core/src/main/kotlin/xyz/mederi/store/DiffStore.kt`
- `core/src/main/kotlin/xyz/mederi/store/InMemoryDiffStore.kt`
- `core/src/main/kotlin/xyz/mederi/store/sqlite/SqliteDiffStore.kt`
- `core/src/main/sqldelight/xyz/mederi/db/Diff.sq`

修改：
- `core/src/main/kotlin/xyz/mederi/tools/FileSystemTools.kt`
- `core/src/main/kotlin/xyz/mederi/tools/ToolFactory.kt`
- `core/src/main/kotlin/xyz/mederi/infrastructure/koog/TurnExecutor.kt`
- `core/src/main/kotlin/xyz/mederi/api/SessionApi.kt`
- `core/src/main/kotlin/xyz/mederi/api/impl/SessionApiImpl.kt`
- `core/src/main/kotlin/xyz/mederi/Mederi.kt`（装配 DiffStore）
- `core/src/main/kotlin/xyz/mederi/config/MederiConfig.kt`（可选注入 DiffStore）
