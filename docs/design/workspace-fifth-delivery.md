# 第五批：诊断与概览步骤

> 2026-10-29，用户授权继续。状态：代码与组件验证完成，APP实机待验；R13表现层子集。

延续中性轻量、内容优先以及“对话继续、任务并行”。只改表现与视图本地展开态；Core/VM数据与异步生命周期保持。通用Tooltip/Menu/Dialog与全局ReducedMotion单独后续验证，避免扩大到设置等未验区域。

## 实施与验收

1. 资源监控：默认一行可展开入口，次级CPU摘要；展开显示真实CPU/核数/RSS/堆，缺失沿用明确缺失符号。取消四边框与固定高度，窄宽和字体缩放允许换行。展开只改变呈现，不改变采集。
2. 原始消息：保留VM绑定与派生、倒序和280dp有界JSON滚动；共享展开语义、字号与复制反馈，复制移到独立工具栏，保持原格式化JSON复制。
3. Todo和计划：共享纯步骤表现，保留两套真实数据源；五态Todo可辨识；计划批准/阅读全文/Spec/展开行为保留。所有动作统一30dp最小热区、13/20及12/18。
4. 实际Compose行为测试、深浅组件预览、wasm编译以及前四批回归；新增纯接口同步架构文档。

不将本批标记整个R13完成。完整APP、三平台原生焦点/遮挡与全页键盘回焦仍待idea-mcp环境。


## 验证与交接

最终六个suite共50项测试（本批9、前四批38、语言资源3），0失败/错误/跳过；wasm编译通过。实际Compose测试覆盖默认收起/标准展开动作、实时指标不重置展开、无数据不伪造0、280dp/1.5字体诊断与长原始消息操作可达、复制与展开独立且格式化文本不变、键盘Enter与语义展开、Todo五态/空列表、计划批准/原文/Spec独立与ID切换展开复位。

已检查实际组件fixture深浅预览：`temp/workspace-fifth-delivery/workspace-diagnostics-dark.png`与`workspace-diagnostics-light.png`。JSON保留横向滚动，长计划标题换行，状态文字与图形同时存在；预览发现核数资源的多余字面引号，已修正并通过最终同组回归。资源中英XML无重复键；git diff --check通过。截图不是完整APP，不宣称全页键盘回焦/三平台原生集成已验。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-fifth-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*WorkspaceFifthDeliveryTest' --tests '*WorkspaceFourthDeliveryTest' --tests '*WorkspaceThirdDeliveryTest' --tests '*WorkspaceSecondDeliveryTest' --tests '*WorkspaceFirstDeliveryTest' --tests '*AppLanguageResourceTest'
```

下一批：通用Tooltip/Menu/Dialog与跨区域动作一致性；随后全局ReducedMotion/架构迁移与完整APP三平台验收继续按主计划推进。本批没有改Core/VM/契约/采样机制，未引入新依赖。当前无idea-mcp工具，必须先启动/配置idea-mcp才能运行APP/server，未通过bash启动。
