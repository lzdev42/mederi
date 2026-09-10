# tool_search（延迟工具搜索工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/tool_search.rs`（执行 + BM25 索引 + 缓存）与 `codex-rs/core/src/tools/handlers/tool_search_spec.rs`（工具描述生成），搜索文本构建在 `codex-rs/tools/src/tool_search.rs`。
> 依赖：`bm25` crate（BM25 全文检索），`codex_tools` 的 `LoadableToolSpec`/`ToolSearchEntry`。

## 一、功能描述

`tool_search` 让模型**在「延迟加载（deferred）」的工具集合中按语义检索**，并把命中的工具暴露给下一轮模型调用。

它的设计目的：

- 工具数量巨大（尤其大量 MCP / connector 工具）时，不可能把所有工具的完整 schema 都塞进上下文。Codex 把这类工具标记为 `Exposure::Deferred`，默认**不**注入模型上下文；模型需要时先调用 `tool_search` 检索，命中后这些工具才会在「下一次模型调用」中临时暴露。
- 检索基于 **BM25 全文检索**（对 `search_text` 建索引），查询词以英文分词检索。
- 结果是「LoadableToolSpec」：命中的工具会被转换为可加载的 spec（默认 `defer_loading = true`，由客户端在下一轮加载）。
- 工具名常量为 `TOOL_SEARCH_TOOL_NAME = "tool_search"`。

本质：这是一个「工具发现（discovery）」工具，专门针对 **deferred** 工具，而不是普通已注入工具的再查询。

## 二、Codex 的参数定义（args schema）

工具定义在 [tool_search_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/tool_search_spec.rs) 的 `create_tool_search_tool`。

| 参数名 | 类型 | 是否必填 | 默认值 | 描述 |
| --- | --- | --- | --- | --- |
| `query` | string | **是** | —— | 对延迟工具的搜索查询。 |
| `limit` | number | 否 | `8`（`TOOL_SEARCH_DEFAULT_LIMIT`） | 最多返回的工具数量。 |

`required: ["query"]`，`additionalProperties: false`。

工具描述（`create_tool_search_tool` 动态生成，含两部分）：
1. 固定说明：`# Tool discovery` 引导段，说明该工具基于 BM25 检索 deferred 工具元数据、把命中的工具暴露给下一次模型调用；并提醒「MCP 工具发现一律用 `tool_search`，不要用 `list_mcp_resources`/`list_mcp_resource_templates`」。
2. **可选来源清单**：当 `source_listing == Include` 时，追加 `You have access to tools from the following sources:` 及去重后的来源列表（来源名 + 描述，总预算 512KB，超出被截断）；`Omit` 时省略。

### 特殊的 ToolSpec 形态

`tool_search` 的 spec 是 `ToolSpec::ToolSearch { execution: "client", ... }`——它是一个**需要在客户端执行**的特殊工具（客户端负责把返回的工具暴露给模型），而不是服务端普通 function。

## 三、Codex 的执行逻辑

handler 在 [tool_search.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/tool_search.rs) 中实现。

### 3.1 索引构建（ToolSearchHandler::new）

- 收集所有 `Exposure::Deferred` 的工具的 `search_info()`：
  - 不可变（immutable）handler（如 MCP）→ 缓存 `Weak<dyn CoreToolRuntime>`，按需 `upgrade().search_info()`；
  - 动态（dynamic）handler → 缓存 `ToolSearchInfo` 快照。
- 每个 `ToolSearchInfo` 含：
  - `entry.search_text`：用于检索的文本（见下）。
  - `entry.output`：`LoadableToolSpec`（命中的工具以可加载 spec 形式返回，默认 `defer_loading = true`，function 的 `output_schema` 被清空）。
  - `source_info`：来源名 + 描述（用于描述里的来源清单）。
- 把 `search_text` 作为文档，用 `SearchEngineBuilder::<usize>::with_documents(Language::English, docs)` 构建 **BM25 索引**，文档 id 即 `search_infos` 下标。

### 3.2 缓存（ToolSearchHandlerCache）

- 缓存 key = `source_listing` + 工具来源集合（不可变 handler 用 `Weak::ptr_eq` 比较、动态用值比较）。
- 当注册表内容不变时，`get_or_build` 直接返回缓存的 `Arc<ToolSearchHandler>`，避免重复建索引；注册表变化（新增/移除/修改 deferred 工具）时自动重建。
- 对外暴露 `Arc<ToolSearchHandler>`，handler 内部无状态（`search_engine` 只读），可安全并发查询。

### 3.3 查询（handle_call + search）

1. **校验 payload**：必须是 `ToolPayload::ToolSearch { .. }`，否则 `Fatal` 错误。
2. **参数校验**：
   - `query.trim()` 为空 → `"query must not be empty"`；
   - `limit == 0` → `"limit must be greater than zero"`；
   - `limit` 缺省取 `TOOL_SEARCH_DEFAULT_LIMIT`。
3. **空索引短路**：`search_infos.is_empty()` 时直接返回空 `ToolSearchOutput { tools: [] }`（避免在空引擎上检索）。
4. **BM25 检索**：`search_engine.search(query, limit)` 返回按相关度排序的文档，取 `document.id` 反查 `search_infos`。
5. **合并输出**：`coalesce_loadable_tool_specs(entries.output)`——把命中的工具按命名空间合并成一组 `LoadableToolSpec`（同一 MCP server / 动态 namespace 的工具聚合到一个 Namespace，避免碎片化）。
6. **返回**：`ToolSearchOutput { tools: Vec<LoadableToolSpec> }`。

### search_text 如何构建

默认构建见 [tool_search.rs:87-149](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/tools/src/tool_search.rs#L87-L149)：
- 对 function：工具名、`name.replace('_', " ")`（下划线展开便于分词）、描述、参数 schema 递归（字段名 + 描述 + items/anyOf）。
- 对 namespace：namespace 名、描述、内部各工具的上述文本。
- 对 freeform：工具名、描述、`format.syntax`。
- MCP 额外拼接：`flat_tool_name`、callable name、server name、title、描述、connector name 等（`build_mcp_search_text`）。

### 数据存储在哪里

- 索引只驻留内存（每次启动重建，跨调用缓存）。
- 检索结果**不持久化**，也不写入模型上下文——命中的工具是「下一次模型调用」的临时暴露（客户端加载后注入）。

### 边界条件与错误处理

- 空 query / 0 limit → 返回错误文本给模型。
- 无 deferred 工具 → 返回空列表（不报错）。
- 索引命中 0 个 → 空列表。
- 同名 namespace 工具合并，避免重复暴露。

## 四、输出格式

`ToolSearchOutput { tools: Vec<LoadableToolSpec> }` 渲染为：
- 给模型的文本：每个命中的工具以可读形式列出（namespace / 工具名 + 描述）。
- code 模式：`{"tools": [ ... LoadableToolSpec ... ]}`。
- `success_for_logging`：true。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
@Serializable
data class ToolSearchArgs(
    val query: String,                    // 必填
    val limit: Int = DEFAULT_LIMIT,       // 默认 8
)

@Serializable
data class ToolSearchResult(
    val tools: List<LoadableToolSpec>,    // 命中的可加载工具 spec
)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class ToolSearchTool(
    private val registry: ToolRegistry,   // 提供 deferred 工具列表
) : Tool<ToolSearchArgs, ToolSearchResult>("tool_search") {

    override val description: String = buildDescription()   // 见 5.3

    override suspend fun execute(args: ToolSearchArgs): ToolSearchResult {
        val query = args.query.trim()
        require(query.isNotEmpty()) { "query must not be empty" }
        require(args.limit > 0) { "limit must be greater than zero" }

        val searchInfos = registry.deferredToolSearchInfos()   // 收集 deferred 工具的 (searchText, spec)
        if (searchInfos.isEmpty()) return ToolSearchResult(emptyList())

        val hits = bm25Search(searchInfos, query, args.limit)  // BM25 检索，返回下标
        val specs = hits.map { searchInfos[it].spec }
        return ToolSearchResult(coalesceNamespaces(specs))      // 按 namespace 合并
    }
}
```

### 5.3 执行逻辑要点

- **只检索 deferred 工具**：只有标记为延迟加载的工具才进入索引；已直接暴露的工具不参与（它们本来就在上下文里）。
- **BM25 索引**：Kotlin 端可用现成 BM25 实现（如 `com.github...bm25` 库）或自实现经典 BM25；`Language::English` 分词足够。数据量小（几百个工具）时也可用简单分词 + 词频打分，但为对齐 Codex 行为建议用标准 BM25。
- **结果「暴露给下一次调用」**：工具返回的 `LoadableToolSpec` 不能直接作为普通 function 输出文本，需要 Mederi 的上下文/加载机制把它们临时注入下一次模型调用。这是实现本工具的关键前置能力——若 Mederi 目前所有工具都是全量注入（无 deferred 机制），`tool_search` 的意义有限，应排在「deferred 工具 + 动态加载」机制之后。
- **namespace 合并**：同一来源（MCP server / 动态 namespace）命中的多个工具合并为一个 Namespace spec，减少 token。
- **描述里带来源清单**：若 Mederi 有连接器来源，可在工具描述中列出可用来源，帮助模型决定是否检索。

### 5.4 依赖

- **ToolRegistry / 工具元数据源**：能枚举 deferred 工具并给出 `search_text` + 可加载 spec（这是前提）。
- **BM25 检索库**：全文检索。
- **客户端协作**：`ToolSpec::ToolSearch { execution: "client" }` 语义需要在客户端（IDE 侧）实现「加载并暴露命中工具」，Mederi 需要确认其客户端是否支持这种动态加载协议。
- **不需要** HistoryStore、tokenizer（检索本身不涉及 token 计算；但 `defer_loading` 的结果 spec 需要 token 预算控制时可加）。
