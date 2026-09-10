# 真实 Provider 测试配置（Agnes OpenAI 兼容端点）

> 以下信息由项目维护者提供，用于 `mederi/core` 与 `mederi/ui/shared` bridge 的真实网络集成测试。
>
> ⚠️ API Key 是敏感信息，请勿提交到公开仓库；本文档仅作为内部开发资料保留。

## 端点与认证

| 项 | 值 |
|---|---|
| 端点 | `https://apihub.agnes-ai.com/v1` |
| 兼容类型 | `OPENAI_CHAT`（OpenAI Chat Completions） |
| API Key | `sk-diJZzYna16M90qUD8JibXatGPjnccDU8iQbLqSlnJFb1NKWk` |

Mederi 的 `UrlNormalizer` 会把该 URL 拆成 `baseUrl=https://apihub.agnes-ai.com/v1` + 空 `versionPath`，最终调用 `/v1/chat/completions`。

## 可用模型

2026-08-17 通过 `/v1/models` 拉取结果：

| 模型 ID | 类型 | 说明 |
|---|---|---|
| `agnes-2.0-flash` | 文本 | Flash 系列旧版 |
| `agnes-2.5-flash` | 文本 | **Flash 系列最新版，推荐日常测试** |
| `agnes-2.5-pro` | 文本 | Pro 系列 |
| `agnes-2.5-pro-alpha` | 文本 | Pro 预览版 |
| `agnes-image-2.0-flash` | 图像生成 | Flash 图像 |
| `agnes-image-2.1-flash` | 图像生成 | Flash 图像新版 |
| `agnes-video-v2.0` | 视频生成 | 视频模型 |

**测试推荐**：`agnes-2.5-flash`（维护者说明 Flash 模型永久免费）。

## 快速验证 curl

### 拉模型列表

```bash
curl -sS -H "Authorization: Bearer sk-diJZzYna16M90qUD8JibXatGPjnccDU8iQbLqSlnJFb1NKWk" \
  https://apihub.agnes-ai.com/v1/models
```

### 单次对话

```bash
curl -sS -H "Authorization: Bearer sk-diJZzYna16M90qUD8JibXatGPjnccDU8iQbLqSlnJFb1NKWk" \
  -H "Content-Type: application/json" \
  -d '{"model":"agnes-2.5-flash","messages":[{"role":"user","content":"Say hello in one word"}],"max_tokens":100}' \
  https://apihub.agnes-ai.com/v1/chat/completions
```

返回示例：

```json
{
  "id": "ebdfce4a072e469db6968440dd30f611",
  "model": "agnes-2.5-flash",
  "choices": [{
    "message": {
      "content": "\n\nHello!",
      "role": "assistant",
      "reasoning_content": "The user wants me to say hello in one word. Simple enough.\n"
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 289,
    "completion_tokens": 20,
    "total_tokens": 309,
    "completion_tokens_details": { "reasoning_tokens": 16, "text_tokens": 4 },
    "prompt_tokens_details": { "cached_tokens": 256 }
  }
}
```

## 在 Mederi CLI 中配置

```bash
./gradlew :cli:run --args="/tmp/mederi-test"
# 在 REPL 中
provider-create AgnesOpenAI OPENAI_CHAT https://apihub.agnes-ai.com/v1 sk-diJZzYna16M90qUD8JibXatGPjnccDU8iQbLqSlnJFb1NKWk agnes-2.5-flash
```

## 自动化集成测试建议

1. **不要把 Key 写死到测试代码**：优先从环境变量 `AGNES_API_KEY` 读取；未设置时跳过或显式失败。
2. **测试链路**：
   - 创建 Project（绑定临时目录）
   - 创建 Session
   - 配置 Provider 并选择 `agnes-2.5-flash`
   - 调用 `MederiAiCore.sendMessage(...)`
   - 收集 `observeConversation(...)`
   - 断言：出现 `ChatBlock.Text`、token 用量非零、`ConversationStatus` 最终回到 `Idle`
3. **可同时验证的字段**：
   - `Message.inputTokens` / `outputTokens` / `cachedTokens`
   - `MessagePart.Reasoning`（ Agnes 返回 `reasoning_content`）
   - `ChatBlock.Reasoning` 在 UI 侧的展示
