# 第二批：工作过程与需要用户处理的交互

> 2026-10-29，延续用户已确认的首批风格；用户明确功能正常，重点在交互、样式、体验。
> 状态：代码与组件验证完成；完整APP视觉验收待idea-mcp环境。只推进主计划R04/R07/R09的表现层子集，不展开契约/VM业务迁移。

## 统一规格与边界

- 复用WorkspaceStyle的正文14/22、控件13/20、说明12/18、30dp最小热区、16dp图标、120ms反馈和180ms展开。
- 过程保持无外框；参数/输出可读可复制，滚动归属不变；去文字呼吸与局部装饰，让状态标记承担运行提示。
- 问答/审批/错误使用同一个纯视图WorkspaceActionSurface：中性背景，必要的低对比语义边界；主要动作始终可见，内容随字体/窗口换行。
- 新组件仅持有展开/hover等本地表现状态，选择答案、翻页、批准、取消、恢复仍经原回调；不更改Core事实、提交/接纳、任务、队列或停止影响范围。
- 状态栏与临时提示减重，但真实重试信息/计时/错误详情和恢复入口完整保留。
- 不隐藏问答/审批，不将后台任务运行表现成禁止继续对话；不添加未实现的快捷键提示。

## 文件分工、独立验收

| 子任务 | 文件 | 必须验证的行为 | 状态 |
|---|---|---|---|
| C1 过程与工具 | atoms/ProcessRow/ExpandableRow、WorkTraceCard、ReasoningBlock、ToolCallsBlock | 空推理不可假展开；运行/终态参数输出真实，复制可达；历史阅读/展开不移位 | 代码与组件验证完成 |
| C2 提问与计划 | QuestionCard、PlanApprovalCard | 单选/多选/自定义、翻页/提交/取消保持原回调；仅pending可批准，查看不误批准 | 代码与组件验证完成 |
| C3 状态与错误 | WorkspaceActionSurface、ErrorBoard、StatusBar、StatusStrip、WorkspaceFloatingOverlay | 详情/恢复/关闭只调用对应动作；idle无占位，真实重试信息可见，窄屏不挤没操作 | 代码与组件验证完成 |

各子任务不并发写同一文件；主代理统一资源、文档与Gradle测试，不重复构建。

验证：`./gradlew :app:shared:jvmTest --tests '*WorkspaceSecondDeliveryTest' --tests '*WorkspaceFirstDeliveryTest' --tests '*ExpandableDetailsTest' --tests '*ChatScrollLayoutTest' --tests '*WorkTracePresentationTest' --tests '*PlanApprovalLayoutLogicTest' --tests '*ParseAskItemsTest' --tests '*AppLanguageResourceTest'`。

通过标准：相关实际测试suite执行、行为断言通过；生成深浅色真实Compose组件预览，不冒称完整APP截图。APP只通过idea-mcp运行；缺该环境时明确记录完整APP视觉验收待办。

## 实际结果与交接

- 2026-10-29：上述8个suite共44项测试，0失败、0错误、0跳过；其中WorkspaceSecondDeliveryTest新增9项真实Compose组件测试。
- `:app:shared:compileKotlinWasmJs`编译通过；资源XML中英两份解析有效且无重复键，`git diff --check`通过。
- 覆盖单选/多选/自定义答案、纯文本与翻页、计划查看与批准互不误触、非pending真实状态且无批准动作、280dp错误卡各动作独立、idle无占位与真实重试详情、标准展开/收起语义，以及深浅主题绘制。原首批与滚动/投影/解析/资源回归同时通过。
- 深浅色组件预览已生成并检查：`temp/workspace-second-delivery/workspace-interactions-dark.png`及`workspace-interactions-light.png`。这是Compose测试场景组合，不是完整APP截图；不构成macOS/Windows/Linux实机验收。
- 本批沿用MVVM＋单向数据流：公共壳只管表现，答案与业务动作仍由调用方控制；未增加任务队列、业务状态副本或Core生命周期。
- 下一步按主计划推进文件/diff与阅读器家族；完整APP滚动、字体缩放、长内容及三平台视觉仍按主计划矩阵验收。APP/server只允许idea-mcp运行，当前无该工具，不以bash替代。
