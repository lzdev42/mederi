# Mederi 流式验证测试报告

- **Model**: `agnes-2.5-flash`
- **总快照数**: 60（流式时应有大量中间快照）
- **Working 快照数**: 57

## 流式块统计
| 类型 | 总数 | 流式中出现 |
|---|---|---|
| 文本 block | 13 | 9 |
| 推理 block | 58 | 56 |
| 工具调用 block | 9 | 8 |

## 最终 Assistant 文本
```text



```

## 判定
- **流式数据出现过**（有 isStreaming Assistant 占位）：PASS
- **工具调用流式出现**：PASS
- **对话文本流式出现**：PASS
- **推理流式出现**：PASS
- **最终文本非空**：FAIL
