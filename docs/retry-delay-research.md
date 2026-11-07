# 服务端重试建议调研（2026-11-04）

本文记录当前查到的一手协议、官方文档与官方 SDK 行为，供重试实现和后续扩展参考。它不是对所有 OpenAI 兼容服务的穷尽清单，也不声称未公开格式不存在。仅查公开资料，未对真实账户制造限流或欠费请求。

## 可确定解析的等待指令

| 来源 | 载体与格式 | 单位/含义 | 处理边界与一手依据 |
|---|---|---|---|
| HTTP 标准 | `Retry-After: 120` 或 HTTP-date | 等待秒数，或允许后续请求的绝对时间 | [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after)。HTTP-date 不是 ISO 8601。 |
| OpenAI Chat Completions / Responses | `Retry-After` | 官方说明临时 429、503 可以返回最短等待秒数 | [OpenAI rate limits](https://developers.openai.com/api/docs/guides/rate-limits)。这是 HTTP 层行为，不依赖两种 JSON API 的请求结构。 |
| Azure OpenAI | `retry-after-ms` | 毫秒 | [Azure OpenAI quota](https://learn.microsoft.com/azure/foundry/openai/how-to/quota) 明确列举 429 上的毫秒建议。 |
| OpenAI 官方 SDK 的兼容行为 | `retry-after-ms` → 数字 `retry-after` → 日期 `retry-after` | 前者毫秒，数字后者秒；SDK 还接受非标准小数 | [openai-python `_base_client.py`](https://github.com/openai/openai-python/blob/main/src/openai/_base_client.py)。这是 SDK 实现证据，小数是兼容扩展，不应描述成 RFC 要求。 |
| Google 原生 API | `error.details[]` 中 `@type=type.googleapis.com/google.rpc.RetryInfo`，其 `retryDelay` | `google.protobuf.Duration` 的 JSON 表示，如 `1s`、`1.000340012s` | [Google 官方错误详情 proto](https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto)、[ProtoJSON Duration](https://protobuf.dev/programming-guides/json/)。按收到错误后至少等待该时长处理；精度转换不能向下取整导致提早重试。 |
| Groq | `retry-after` | 秒；官方说明在触发 429 时提供 | [Groq rate limits](https://console.groq.com/docs/rate-limits)。同页的 reset 头是独立的额度遥测，不属于本次等待指令。 |
| OpenRouter | `Retry-After` | 等待秒数 | [Errors and Debugging](https://openrouter.ai/docs/api_reference/errors-and-debugging)。429/503 可提供；其在途预算的 402 是有条件的可恢复特例，见下文。 |
| Fireworks | `Retry-After` | 使用标准头所表达的等待 | [Serverless rate limits](https://docs.fireworks.ai/serverless/rate-limits) 明确要求 429 时遵从此头。未找到本次需要新增的 Fireworks 私有等待格式。 |
| Moonshot / Kimi | `Retry-After` | 使用标准等待头 | [Common Error Codes](https://platform.kimi.ai/docs/api/errors) 在 `engine_overloaded_error` 中明确要求按此头等待。 |

这些可归并成三个载体、四种编码：毫秒重试头、秒数重试头、HTTP 日期重试头、Google 结构化 duration。无需按供应商名称分叉复制同一标准解析器。

本产品的优先级为：先判断是否可重试，再采用有效 `retry-after-ms`，否则有效 `Retry-After`（数字秒或 HTTP-date），否则有效 Google `RetryInfo.retryDelay`，最后每次独立随机 0～30 秒。有效服务端等待作为下限，额外加 0～1 秒随机抖动，避免同步重发。无指数递增。没有合法建议与建议明确为零是不同情况；格式无效、负数或溢出不能伪装成零秒。

服务端指令可以超过 30 秒或 60 秒；随机兜底范围不是服务端等待上限。OpenAI SDK 版本可能限制自己接受的最长等待时间，这属于该 SDK 的产品策略，不能移植成协议规则；尤其不能把较长合法建议截短后提前重试。当前查询的 SDK main 已有最长两分钟的实现，进一步说明不能把“60 秒”视为稳定协议常量。

## 重试许可与确定性失败

等待建议只解决“等多久”，不自动证明“重发就能解决”。

- OpenAI 官方 SDK 识别非标准 `x-should-retry: true/false`，见上面的 `_base_client.py`。它是重试许可提示，不是等待时间。本产品优先遵守显式 false；true 不用来绕过已知鉴权、参数或明确需要用户处理的结构化失败。
- [OpenAI Error codes](https://developers.openai.com/api/docs/guides/error-codes) 明确区分 `error.code` 中的 `credit_balance_exhausted`、`organization_spend_limit_exceeded`、`project_spend_limit_exceeded`、`organization_usage_limit_exceeded`：应添加余额或修改限额；`error.type` 仍可能是较宽的 `insufficient_quota`。不能用自由文本里的 `quota` 一词区分短期速率与账单问题。兼容协议的 `insufficient_quota` 也不能全局判为不可重试：商汤实际 429 样例为 `type=rate_limit_error, code=insufficient_quota, message=inference exceeds tpm/rpm limit`。本产品对该宽泛码保留 429 重试，仅明确余额/消费限额码或显式服务端拒绝等信号才停止。
- [Kimi Common Error Codes](https://platform.kimi.ai/docs/api/errors) 的 `error.type=exceeded_current_quota_error` 指余额不足/账户停用/需充值的 token 额度。`rate_limit_reached_error` 则覆盖并发、RPM、TPM、TPD，不能把它整体归为余额问题。
- Google 的 `QuotaFailure.violations[].quotaId` 明确包含 `PerDay` 时，本产品选择停止本轮重试并反馈长期配额信息。这是产品策略，不是 Google 声明的永久失败：日配额下次重置后可能恢复；也不通过任意 `RESOURCE_EXHAUSTED`、`quota` 文本或长 delay 来推断永久失败。[Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) 描述日额度与重置；Google 官方 CLI 仓库的 [结构化配额讨论](https://github.com/google-gemini/gemini-cli/issues/9248) 给出了包含 `QuotaFailure` 与 `RetryInfo` 的实际错误样例，该 issue 里的建议本身不属于协议规范。
- OpenRouter 的 402 不能一律等同欠费。其官方错误文档明确说明 `error.metadata.limit_source=openrouter_in_flight_budget` 的在途支出预算可能返回 `Retry-After`；可以按建议等待。无此等待建议的 402 应交给用户处理。本产品允许 402 携有效服务端等待建议走恢复路径；不扩展为“所有 402 都重试”。

本产品仍保留首帧后不自动重发与用户停止可取消等待的约束。

## 不作为本次重试等待依据的字段

| 字段/来源 | 调研结果与排除理由 |
|---|---|
| `x-ratelimit-reset-requests` / `x-ratelimit-reset-tokens` / `x-ratelimit-reset-project-tokens` | OpenAI 文档定义为额度恢复到初始状态所需时间；Groq 同名头还分别对应日请求/分钟 token 窗口。它们不是针对当前请求的可重试时间，不能当作 `Retry-After` 的同义词。 |
| `x-ratelimit-reset` / `RateLimit-Reset` | 跨服务的语义可能是相对秒数，也可能是 Unix 时间，不能只凭头名或数值大小猜单位；本次全部排除，包括看起来可以解析的 reset 值。 |
| `x-ms-retry-after-ms` | [Azure 通用 RetryPolicy](https://learn.microsoft.com/en-us/java/api/com.azure.core.http.policy.retrypolicy?view=azure-java-stable) 有此扩展，一手证据主要落在 Azure 通用组件及 Cosmos DB。甚至 [Cosmos Gremlin headers](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/headers) 同名值是 .NET TimeSpan 字符串；不是已确认的 OpenAI 兼容通用毫秒协议。本次不加入。 |
| `X-DashScope-Wait-Timeout` | [阿里云最佳实践](https://help.aliyun.com/en/model-studio/rate-limiting-best-practices) 定义为客户端发送的服务端排队等待上界，不是错误响应建议；不偷偷为请求注入此头。 |
| 人类可读 `message` 中 “retry in …” / 中文重置时间 | 本次不做字符串手术、跨语言正则抓取或猜时区。Google 已有 `RetryInfo`，优先解析结构化详情。 |
| 任意未知 JSON `retry_after` / `retryAfter` 字段 | 未找到能够覆盖所有 OpenAI 兼容服务的统一规范，不能按相似拼写全局启用。新适配必须补一手格式/单位证据和样例测试。 |

## 其他已查看的兼容供应商资料

| 供应商 | 当前公开资料能确认的内容 | 本次结论 |
|---|---|---|
| Together | [当前 serverless rate limits](https://docs.together.ai/docs/serverless/rate-limits) 说明 429/503 与重试建议，但当前页未给新的私有等待字段。 | 不把历史/第三方文章中的 `x-ratelimit-reset` 说法当现行协议。收到标准 Retry-After 可按通用逻辑处理，否则随机兜底。 |
| Cerebras | [当前 rate limits](https://inference-docs.cerebras.ai/support/rate-limits) 说明连续补充的额度桶；未在该页找到新的私有重试时间字段。 | 无证据不加专有解析。 |
| Mistral | [错误说明](https://docs.mistral.ai/resources/error-glossary) 与 [限制说明](https://docs.mistral.ai/resources/known-limitations) 列举 429 等状态及客户端退避，未在所查页面找到专有重试等待格式。 | 无证据不加专有解析。 |
| DeepSeek | [官方错误码](https://api-docs.deepseek.com/quick_start/error_codes/) 区分 402 余额不足、429 频率/并发/QPS、503 容量过载。 | 所查页未承诺专有等待字段；保持标准头解析与随机兜底。 |
| 阿里云 Model Studio | [官方错误码](https://help.aliyun.com/en/model-studio/error-code-start) 对部分 API 的重试策略提到 Retry-After；API 类别很多，不能扩展为所有端点保证发送该头。 | 标准头通用支持，无需私有同义字段。 |
| 智谱 | [官方错误码](https://docs.bigmodel.cn/cn/api/api-code) 对 429 还细分欠费、速率、套餐、长窗口等业务码；部分重置时间只嵌在人类文案。 | 所查页面未给新私有等待载体；不解析文案时间，不把所有 429 当账单失败。 |
| SiliconFlow | [Chat Completions API](https://docs.siliconflow.cn/docs/api/chat-completions-post) 所查响应文档未声明新的私有等待格式。 | 无证据不加专有解析。 |

这份清单记录“本次找到且能解释单位/含义的格式”，不是供应商行为保证。代理网关可能丢弃或改写响应头；没有可识别信息时照产品要求使用 0～30 秒随机等待。诊断只记录白名单等待头与来源，不全量输出响应头（可能包含 Cookie 等敏感数据）。
