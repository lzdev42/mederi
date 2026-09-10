# view_image（本地图片查看工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/view_image.rs`（执行）与 `codex-rs/core/src/tools/handlers/view_image_spec.rs`（schema），图片准备在 `codex_utils_image::data_url_from_bytes`，详情级别在 `codex_protocol::models::{ImageDetail, DEFAULT_IMAGE_DETAIL}`。

## 一、功能描述

`view_image` 让模型**读取本地磁盘上的图片文件，并把图片以 data URL 形式注入模型可见上下文**，供视觉模型（multimodal）直接查看。

它的设计目的：

- 当需要「视觉检查」时（查看截图、UI 渲染结果、示意图等），模型通过该工具把文件系统里的图片转换成图像输入。
- 支持两种 `detail`：`high`（默认，可缩放）与 `original`（保留原始分辨率）。
- 对模型能力有前置约束：**只有 `input_modalities` 包含 `Image` 的模型才允许调用**；纯文本模型调用会直接报错。
- 支持并行调用（可同时查看多张图）。

工具名常量为 `VIEW_IMAGE_TOOL_NAME = "view_image"`。

## 二、Codex 的参数定义（args schema）

工具定义在 [view_image_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/view_image_spec.rs) 的 `create_view_image_tool(options)`，schema 随 `ViewImageToolOptions` 变化。

| 参数名 | 类型 | 是否必填 | 描述 |
| --- | --- | --- | --- |
| `path` | string | **是** | 本地文件系统上的图片文件路径。 |
| `detail` | string enum | 否 | `high` / `original`。默认 `high`；`original` 保留精确分辨率。仅当 `can_request_original_image_detail && !unified_image_budget` 时才出现在 schema 中。 |
| `environment_id` | string | 否 | 来自 `<environment_context>` 的环境 id；省略则用主环境。仅当 `include_environment_id` 时出现。 |

`strict: false`，`defer_loading: None`，`required: ["path"]`，`additionalProperties: false`。

工具描述：`"View a local image file from the filesystem when visual inspection is needed. Use this for images already available on disk."`

`ViewImageToolOptions` 三开关（[view_image_spec.rs:9-14](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/view_image_spec.rs#L9-L14)）：
- `can_request_original_image_detail`：模型/服务是否允许请求 `original` 分辨率。
- `unified_image_budget`：是否启用统一的图片预算（此时不暴露 `detail` 参数，强制走统一预算逻辑）。
- `include_environment_id`：是否支持多环境选择。

### 输出 schema（output_schema）

```jsonc
// unified_image_budget == false 时
{
  "type": "object",
  "properties": {
    "image_url": { "type": "string", "description": "Data URL for the loaded image." },
    "detail":    { "type": "string", "enum": ["high", "original"] }
  },
  "required": ["image_url", "detail"],
  "additionalProperties": false
}
// unified_image_budget == true 时去掉 detail 字段
```

## 三、Codex 的执行逻辑

handler 在 [view_image.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/view_image.rs) 中实现。流程：

1. **能力前置校验**：若 `turn.model_info.input_modalities` 不包含 `InputModality::Image` → 返回 `"view_image is not allowed because you do not support image inputs"`。
2. **校验 payload**：必须是 `ToolPayload::Function { .. }`，否则报错。
3. **解析参数**：`parse_arguments::<ViewImageArgs>`（`path` 必填，`environment_id`/`detail` 可选）。
4. **detail 兼容处理**：虽然新 schema 只暴露 `high`/`original`，但 handler 仍接受历史传值：
   - `"high"` → `High`，`"original"` → `Original`；
   - 其他值 → `"view_image.detail only supports 'high' or 'original'; omit 'detail' for default high resized behavior, got '{...}'"`。
5. **解析环境与路径**：`resolve_tool_environment(...)` 选择目标环境；无可用环境 → `"view_image is unavailable in this session"`。`path` 相对当前环境 cwd 解析，解析失败报错。
6. **读取文件（带沙箱）**：
   - `fs.get_metadata`：确认存在；不存在 → `"unable to locate image at ..."`。
   - `metadata.is_file` 为假 → `"image path ... is not a file"`。
   - `fs.read_file`：读取字节。
7. **校验是合法图片**：`image::load_from_memory(&file_bytes)`——解析失败 → `"unable to process image: invalid or unsupported image data"`。**这一步在把字节交给 code 模式之前就拒绝非图片**，避免非图片字节泄露。
8. **决定 detail**：
   ```rust
   let can_request_original_detail = can_request_original_image_detail(&turn.model_info);
   let use_original_detail = self.options.unified_image_budget
       || can_request_original_detail && matches!(detail, Some(ViewImageDetail::Original));
   let image_detail = if use_original_detail { ImageDetail::Original } else { DEFAULT_IMAGE_DETAIL };
   ```
9. **构造 data URL**：`data_url_from_bytes("application/octet-stream", &file_bytes)` → `data:...;base64,...`。**图片的尺寸缩放/准备由「历史插入路径」统一负责**（`image_detail` 只传递意图，实际缩放发生在把图片注入上下文时）。
10. **发出事件**：`session.emit_turn_item_started/completed` 发送 `TurnItem::ImageView`（含 call_id + path），用于前端展示。
11. **返回**：`ViewImageOutput { image_url, image_detail, unified_image_budget }`。

### 数据存储在哪里

- 图片字节以 **data URL 内联在工具调用输出** 里返回，随模型可见上下文一起提交给模型。
- `TurnItem::ImageView` 事件只记录路径，不记录字节，供 UI 展示。
- 不做文件系统写入，不持久化。

### 边界条件与错误处理

- 模型不支持图片输入 → 报错。
- 图片不存在 / 非文件 / 非法图片字节 → 各自报错文本。
- `detail` 非法 → 报错。
- 无可用环境 → 报错。
- 沙箱权限拒绝读取 → 由 fs 层报错。
- 支持并行查看多张图。

## 四、输出格式

`ViewImageOutput` 实现了 `ToolOutput`：

- **`to_response_item`**：`FunctionCallOutputBody::ContentItems([FunctionCallOutputContentItem::InputImage { image_url, detail }])`——即把图片作为 `input_image` 内容注入，`success = true`。
- **`log_preview`**：`"<image data URL omitted: {bytes} bytes>"`——**日志中绝不输出图片数据**，只报字节数。
- **`code_mode_result`**：
  - `unified_image_budget`：`{"image_url": ...}`；
  - 否则：`{"image_url": ..., "detail": ...}`。
- **`success_for_logging`**：true。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
@Serializable
data class ViewImageArgs(
    val path: String,                          // 必填
    @SerialName("environment_id") val environmentId: String? = null,
    val detail: String? = null,                // "high" | "original"，null 默认 high
)

@Serializable
data class ViewImageResult(
    @SerialName("image_url") val imageUrl: String,        // data URL
    val detail: String = "high",                          // "high" | "original"
)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class ViewImageTool(
    private val fs: FileSystemProvider.ReadWrite<Path>,
    private val modelSupportsImage: () -> Boolean,        // 当前模型 input_modalities 是否含 Image
) : Tool<ViewImageArgs, ViewImageResult>("view_image") {

    override val description: String =
        "View a local image file from the filesystem when visual inspection is needed. Use this for images already available on disk."

    override suspend fun execute(args: ViewImageArgs): ViewImageResult {
        // 1. 能力校验
        if (!modelSupportsImage()) {
            throw ToolException("view_image is not allowed because you do not support image inputs")
        }
        // 2. detail 校验
        val detail = when (args.detail) {
            null -> "high"
            "high", "original" -> args.detail!!
            else -> throw ToolException(
                "view_image.detail only supports `high` or `original`; omit `detail` for default high resized behavior, got `${args.detail}`")
        }
        // 3. 解析并读取（相对当前环境 cwd；按 Mederi 权限模型校验）
        val uri = resolvePath(args.path) ?: throw ToolException("unable to resolve image path `${args.path}`")
        val bytes = fs.read(uri) ?: throw ToolException("unable to locate image at `$uri`")
        // 4. 合法性校验：必须是可解码图片（用 ImageIO/Coil decode 校验）
        if (!isDecodableImage(bytes)) {
            throw ToolException("unable to process image: invalid or unsupported image data")
        }
        // 5. 构造 data URL 返回；尺寸缩放由上下文注入路径统一处理
        return ViewImageResult(
            imageUrl = dataUrlFromBytes(bytes),     // data:...;base64,...
            detail = detail,
        )
    }
}
```

### 5.3 执行逻辑要点

- **能力门禁**：模型不支持图片输入时必须拒绝，避免把图片字节白白塞给纯文本模型。
- **合法性校验必须在返回前**：读取后先 `decode` 校验，非法图片直接报错，不要把任意二进制当图片注入上下文。
- **detail 意图传递**：本工具只决定 `detail`（`high`/`original`），实际缩放/准备交给「把图片注入上下文」的公共路径，保持单一职责。
- **日志脱敏**：日志/预览输出绝不能打印 base64 字节，只输出 `<image data URL omitted: N bytes>`。
- **data URL 编码**：用 `data:application/octet-stream;base64,...`（或按解码出的真实 mime）。
- **可并行**：声明 `supports_parallel_tool_calls = true`。
- 若实现 `unified_image_budget` 或 `original` 详情预算，按统一预算逻辑处理（初版可只支持 `high` + 非预算模式）。

### 5.4 依赖

- **FileSystemProvider.ReadWrite&lt;Path&gt;**：读取文件（复用 Koog 的 `FileSystemProvider`，与 `EditFileTool` 同一抽象）。
- **图片解码器**：Java 端可用 `javax.imageio.ImageIO`，或引入 Coil/其他图像库做字节合法性校验；编码 data URL 用 `Base64`。
- **模型能力信息**：当前模型的 `input_modalities`（是否支持 Image）。
- **环境/路径解析**：多环境支持时需环境 id → cwd 解析（可省略 `environment_id` 简化初版）。
- **不需要** tokenizer（token 计算由上下文注入路径负责）。
