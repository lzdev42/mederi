# 自定义工具开发指南（CustomToolRegistry）

> 面向 Mederi 宿主应用开发者（desktop / server 进程）：如何编写原生 Kotlin 工具并注入到 AI Agent 的工具集。
>
> MCP server 是另一条独立的扩展路径（设置页 → MCP 管理直接添加，跨进程、可持久化），不在本文档范围内。

## 1. 什么时候用自定义工具

你的工具需要**与宿主进程深度交互**——访问 JVM 侧资源、调用进程内对象、与 UI 层共享状态——这类工具写成原生 Kotlin class 注册进 `CustomToolRegistry`，比启动一个 MCP server 进程更直接。

典型场景：
- 注入项目内部 SDK 的查询接口（如代码索引、依赖图分析）
- 桥接宿主持有的服务对象（如自定义数据库连接、内部 API 客户端）
- 在工具执行时与 UI 层通信（通过 EventBus 发事件）

## 2. 核心机制

### CustomToolRegistry

**文件**：`core/src/commonMain/kotlin/xyz/mederi/tools/CustomToolRegistry.kt`

全局单例注册表（`object`），仿 `BrowserRegistry` 模式。宿主进程在启动装配阶段注册工厂闭包，`TurnExecutor` 每轮 turn 自动收集：

```
宿主启动 → CustomToolRegistry.register("name") { MyTool() }
                                    ↓ 注册表内存（ConcurrentHashMap）
每轮 turn → TurnExecutor.runTurn() → CustomToolRegistry.buildAll()
                                    ↓ 产出新实例列表
ToolFactory.build(customTools = ...) → 与内置工具旁路合并进 ToolRegistry
                                    ↓
Koog AIAgent → 工具描述自动发给 LLM → 模型决定是否调用
```

关键语义：
- **旁路合并**：自定义工具不参与 `toolNames` 角色裁剪——主代理、EXECUTOR、RESEARCHER 都能用
- **每轮新实例**：`buildAll()` 每次调工厂闭包产出新对象，避免跨 turn 状态残留
- **幂等覆盖**：同名 `register` 后注册的覆盖先注册的
- **纯内存**：进程重启后注册表清空，需在启动时重新注册

## 3. 编写一个工具

### 基础：继承 SimpleTool

Mederi 的工具体系建立在 Koog 的 `SimpleTool<Args>` 上。一个工具 = 一个 `@Serializable` 参数类 + 一个 `execute()` 方法。

```kotlin
package my.app.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

class DependencyTreeTool(
    private val projectRoot: String
) : SimpleTool<DependencyTreeTool.Args>(
    argsType = typeToken<Args>(),
    name = "dependency_tree",
    description = "Get the dependency tree of a Gradle module. Returns module path and its dependencies as text."
) {
    @Serializable
    data class Args(
        @LLMDescription("Gradle module path, e.g. ':core' or ':app:shared'. Empty = project root.")
        val module: String = ""
    )

    override suspend fun execute(args: Args): String {
        // 你的实际逻辑
        val target = if (args.module.isBlank()) projectRoot else "$projectRoot/${args.module.replace(':', '/')}"
        // ... 调用你的分析逻辑 ...
        return "Module $target dependencies:\n- ...\n- ..."
    }
}
```

### 要素说明

| 要素 | 说明 |
|---|---|
| `SimpleTool<Args>` | Koog 基类，`Args` 必须是 `@Serializable` data class |
| `argsType = typeToken<Args>()` | Koog 需要运行时类型令牌来序列化参数 schema |
| `name` | 工具唯一标识，LLM 用这个名字调用。**避免与内置工具名冲突**（见下方内置工具名列表） |
| `description` | 直接发给 LLM 的工具说明，写清楚"这个工具做什么、什么时候该用它"。描述质量直接影响模型调用准确率 |
| `@LLMDescription("...")` | 标注在 Args 字段上，生成参数 schema 的描述，告诉模型每个参数的含义 |
| `execute(args)` | suspend 函数，返回 `String`。返回值直接作为工具结果交给 LLM |

### 内置工具名（注册时避免冲突）

```
read_file, write_file, edit_file, list_directory, bash
update_todo, ask_user
create_plan, generate_spec, write_log, converge_plan, update_verification
verify_subtask
subagent
browser
office_read, office_write
list_processes, stop_process
web_search
```

建议用项目前缀命名自定义工具（如 `myapp_dependency_tree`）。

### 返回值约定

- **成功**：返回工具结果文本，LLM 会看到它并据此继续推理
- **参数错误**：返回 `"Error: ..."` 开头的描述性文本，不要抛异常
- **执行失败**：同样返回错误文本，说明发生了什么、用户该怎么处理

```kotlin
override suspend fun execute(args: Args): String {
    if (args.pid <= 0) return "Error: pid must be a positive number."
    return try {
        // ... 正常逻辑 ...
        "Result: ..."
    } catch (e: IOException) {
        "Error: failed to read $target: ${e.message}"
    }
}
```

> Mederi 原则（见 AGENTS.md §5）：不崩溃，安全报错给用户。工具执行抛未捕获异常会中断当前 turn。

## 4. 注册工具

### 注册时机

在宿主进程启动装配阶段调用 `register`。对于 Mederi 自己的宿主，推荐位置是 `MederiAiCore.initialize()` 或 desktop / server 的 `main.kt` 启动序列中。

```kotlin
import xyz.mederi.tools.CustomToolRegistry

fun registerCustomTools(projectRoot: String) {
    CustomToolRegistry.register("myapp_dependency_tree") {
        DependencyTreeTool(projectRoot)
    }
    // 注册多个工具：多次调用 register
    CustomToolRegistry.register("myapp_code_index") {
        CodeIndexTool()
    }
}
```

- **工厂闭包**：`register` 接收 `() -> ToolBase<*, *>`，不是工具实例本身。每轮 turn 调用闭包产出新实例
- **闭包捕获**：如果工具需要外部依赖（配置、服务对象），在闭包外构造好、闭包内引用。注意闭包捕获的对象生命周期要覆盖整个进程
- **卸载**：`CustomToolRegistry.unregister("name")` 移除单个工具；`clear()` 清空全部（测试用）

### 注册后生效

注册后**下一轮 turn 自动生效**——不需要重启会话。`TurnExecutor.runTurn()` 第 1016 行 `CustomToolRegistry.buildAll()` 每轮收集，工具描述由 Koog 自动发给 LLM。

## 5. 完整示例

以下是一个完整的、可运行的自定义工具：查询 Git 仓库的最近提交。

```kotlin
package my.app.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.File

class GitLogTool(
    private val repoRoot: String
) : SimpleTool<GitLogTool.Args>(
    argsType = typeToken<Args>(),
    name = "myapp_git_log",
    description = "Show recent git commits in the repository. Returns commit hash, author, date, and message."
) {
    @Serializable
    data class Args(
        @LLMDescription("Number of commits to show (default 10).")
        val count: Int = 10
    )

    override suspend fun execute(args: Args): String {
        val n = args.count.coerceIn(1, 100)
        return try {
            val process = ProcessBuilder("git", "log", "--oneline", "-$n")
                .directory(File(repoRoot))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) output
            else "Error: git log failed (exit ${process.exitValue()}):\n$output"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
```

注册：

```kotlin
// 在宿主启动序列中
CustomToolRegistry.register("myapp_git_log") {
    GitLogTool(projectRoot = "/path/to/repo")
}
```

## 6. 进阶

### 带状态的工具

如果工具需要在单次 turn 内跨调用共享状态（如缓存、计数器），把状态放在工具实例的字段里。`buildAll()` 每轮 turn 产出新实例，状态不会跨 turn 泄漏：

```kotlin
class CachingQueryTool : SimpleTool<...>(...) {
    private val cache = mutableMapOf<String, String>()  // 单 turn 内有效

    override suspend fun execute(args: Args): String {
        return cache.getOrPut(args.key) {
            // 昂贵的查询，同 turn 内只执行一次
            doExpensiveQuery(args.key)
        }
    }
}
```

### 通过 EventBus 与 UI 通信

工具执行时可以向 `eventBus` 发事件，UI 层监听后做展示（如进度通知）：

```kotlin
class LongRunningTool(
    private val eventBus: MutableSharedFlow<MederiEvent>
) : SimpleTool<...>(...) {
    override suspend fun execute(args: Args): String {
        repeat(args.steps) { i ->
            eventBus.emit(MederiEvent.Progress(step = i, total = args.steps))
            doWork(i)
        }
        return "Done"
    }
}
```

### 参考：内置工具实现

想看更复杂的真实工具实现，直接读 Mederi 源码：

| 工具 | 文件 | 看点 |
|---|---|---|
| `list_processes` / `stop_process` | `core/.../tools/ProcessTools.kt` | 最简完整示例：参数定义、错误返回、进程交互 |
| `bash` | `core/.../tools/ShellTools.kt` | 复杂参数、沙箱集成、长进程管理 |
| `verify_subtask` | `core/.../tools/VerifyTools.kt` | 带外部依赖注入（PlanStore）、多步骤执行 |

## 7. 注意事项

1. **纯内存注册表**：`CustomToolRegistry` 是进程级 `object`，重启后清空。所有注册必须在启动序列中完成，不能依赖持久化
2. **仅 JVM 宿主**：自定义工具是 JVM 字节码，只在 desktop / server 进程里跑。遥控端（iOS / Android / WASM）不跑 core，不注册自定义工具——它们经 `ServerAiCore` REST 访问 server 进程，间接受益于 server 上注册的工具
3. **不参与角色裁剪**：自定义工具对主代理和所有子代理（EXECUTOR / RESEARCHER）都可见。如果某个工具只该给主代理用，在 `execute()` 内自行判断调用上下文
4. **工具名唯一**：同名注册后者覆盖前者；与内置工具名冲突会导致不可预期的行为
5. **description 是给 LLM 看的**：写得越清楚，模型调用越准确。说明工具做什么、参数含义、什么时候该用、什么时候不该用
6. **不要在工具里做权限决策**：工具的执行不受 AGENTS.md §5.5 沙盒约束（沙盒只管 `bash` 和文件工具）。自定义工具的安全边界由开发者自行保障

## 8. 测试

注册表行为可参考 `core/src/jvmTest/kotlin/xyz/mederi/tools/CustomToolRegistryTest.kt`：

```kotlin
@BeforeTest
fun clean() { CustomToolRegistry.clear() }

@AfterTest
fun cleanup() { CustomToolRegistry.clear() }

@Test
fun `register 后 buildAll 包含注册的工具`() {
    CustomToolRegistry.register("my_tool") { MyTool() }
    assertEquals(1, CustomToolRegistry.buildAll().size)
    CustomToolRegistry.clear()
}
```

> `CustomToolRegistry` 是进程级全局单例，每个测试前后必须 `clear()` 避免污染其他测试。
