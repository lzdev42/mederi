# 首批交付：消息＋输入区＋概览

> 2026-10-29；用户已明确授权优先交付可用的视觉闭环，功能保持原样，重点是交互、样式、体验。
> 状态：代码实现与组件测试已完成，真实APP视觉验收待idea-mcp环境。该范围覆盖整体计划R04/R05/R06/R08的表现层子集，不代表全量架构迁移完成。

## 边界与统一规格

- 保留Core/VM的发送、接纳、任务、停止、审批、档位与草稿语义；本批不迁契约、不修效果通道/轮询、不新增业务队列。
- 继续采用现有MVVM：组件只修改本地展开/菜单/交互态，不另存业务事实。必要的新展示组件使用state+callbacks。
- 中性surface、iris选中/焦点；少框线、不叠加强调；正文14sp/22sp，UI13sp/20sp，说明12sp/18sp。
- 共用`theme/WorkspaceStyle.kt`，控件30dp、图标16dp、热区30dp，hover120ms、展开180ms、任务组呼吸2400ms。颜色全部来自主题。
- 用户消息柔和背景、助手正文无壳；诊断信息可展开，复制/回滚仍可发现；文档入口减轻但保留所有打开能力。
- 输入外壳单一边界、统一选择器/控件，保留焦点状态；不因后台任务活动禁用发送。
- 概览仅显示/隐藏；标题固定、内容限高滚动；减少逐行分割线，统一折叠反馈；任务只保留组级呼吸，文字不闪。
- 保留深浅主题、中英资源、稳定消息ID/列表key、读历史不抢滚动、主/子renderer共用规则。

## 子任务与验证

| 子任务 | 文件范围 | 行为验收 | 状态 |
|---|---|---|---|
| B1 消息与产物样式 | ChatListContent/UserMessageCards/DocumentArtifactCard/EventMessageCard，必要的新消息展示组件 | 完整正文/复制/回滚/详情入口保留；元数据展开正确；阶段交付不折叠丢失 | 代码完成；组件验证通过 |
| B2 输入区与选择器 | ChatInputCard/Controls/Selectors/Attachments | 文字、附件、IME、模型/推理、发送与停止回调不变；统一控件与菜单风格 | 代码完成；组件验证通过 |
| B3 概览与共享规格 | WorkspaceStyle、overview、Workspace定位接线 | 显隐保留展开项；内容不越界；折叠/展开均显示组级运行态，任务终态停止 | 代码完成；组件验证通过 |

不并发写同一文件；整合后统一更新标准/架构和跑Gradle，子任务不重复构建。

验证命令：`./gradlew :app:shared:jvmTest --tests '*WorkTracePresentationTest' --tests '*ChatScrollLayoutTest' --tests '*OverviewWindowStateTest' --tests '*ReasoningMenuTest' --tests '*PromptComposerTest' --tests '*SlashCommandModelTest' --tests '*AppLanguageResourceTest' --tests '*WorkspaceFirstDeliveryTest'`。

通过条件：上述实际suite行为断言通过、新交互用例实际执行；可做Compose组件测试渲染。APP实际运行只能走idea-mcp，当前未发现该工具，故最终真实APP视觉验收仍需明确记录，不冒充已完成。

## 衔接与预算

先完成这批并留下可用状态，再决定下一批；不以前置全量架构迁移阻止视觉闭环。整体计划的架构风险和未完成项保留，不顺手扩大本批范围。


## 实际交付记录（2026-10-29）

- B1：用户柔和气泡/助手14sp正文、按需回复详情、轻量文档/终态行；复制/完整复制/回滚/阅读器和报告入口保留。
- B2：单边界输入外壳、统一轻量选择器与菜单、30dp最小热区、附件操作标签；字体放大可撑高控件。Wasm输入lineHeight与Canvas同源，16px防iOS缩放仍保留。
- B3：概览使用真实可用宽高约束，固定标题＋滚动内容、无阴影/逐行框线、展开语义与同节奏箭头；子Agent/后台任务仅组级呼吸，单任务文字稳定。
- 新增 `WorkspaceFirstDeliveryTest` 6项实际Compose交互验证：固定关闭入口与内容滚动、折叠摘要/详情、活动中带内容发送/空内容停止、诊断展开、字体放大、深浅主题下文档/选择回调。
- 相关JVM回归：44 tests，0 failures，0 errors，0 skipped；`compileKotlinWasmJs`通过。未运行全量测试或声称完成Android/iOS/Windows/Linux实机验收。
- 深浅色组件预览由真实Compose组件测试生成：`temp/workspace-first-delivery/workspace-components-dark.png` / `workspace-components-light.png`。这是测试夹具的组件组合，不是完整APP截图；可用 `MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-first-delivery"` 配合上面的测试命令重新生成，临时图片不纳入Git。
- 静态复核发现菜单固定高度会裁放大字体，已改为最小高度，并加入2倍字体下控件增高的交互测试。
- 本批未改Core/VM业务逻辑与contract；原有effect消费/后台查询/报告同步I/O等债务仍按总计划保留。未继续扩展到其它面板或全局主题。
- 下一步：在idea-mcp启动的APP查看这批真实页面，收集视觉反馈；下一批范围另定，不自动继续执行20项整体改造。
