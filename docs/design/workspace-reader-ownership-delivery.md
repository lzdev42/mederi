# R02a/b第一步：阅读器状态归属与纯视图

> 2026-10-30：代码与组件验证完成。本批为R02a/b窄切片，不表示整体MVVM完成；完整APP实机待验。

1. 阅读器标签、选中ID、文件打开确认统一为不可变ReaderPresentationState，只由VM动作更新；按会话隔离，新对话按项目隔离，无快照双写，无Core DTO或Browser修改。
2. VM保留只读兼容artifactItems/activeArtifactId/pendingFileOpen；打开/选择/确认均针对当前owner。无效标签ID不改变选择。切换会话清除待确认意图而保留标签，异步文件结果检查owner+序号，捕获原provider，防止新项目读错/旧确认串台。
3. 阅读面板通过ReaderPanelState只读投影和onSelect/onRetry纯回调渲染，流式正文仍从当前Core聊天投影派生，不增加内容真理源。报告恢复缓存/原来源；切换隐藏只取消读取，不停止任何任务。
4. 验证WorkspacePresentationStateTest、ReaderPanelDeliveryTest与VM集成：同ID跨会话独立、切回恢复、项目草稿隔离、Core快照不冲选择、无效ID、原文/图片/报告/流式、待确认不串台及晚返回防护。既有回归/JVM/wasm；无新视觉风格，完整APP仍需idea-mcp。

## 实际实现

- 新增ReaderOwner.Session/Draft、不可变ReaderPresentationState与ReaderPanelState。标签列表、选中ID和待确认意图同归VM readerStates，兼容属性变为只读List/派生值；未知标签ID无副作用。同ID更新原位替换，相同图片来源仍复用，新增图片用Uuid避免同毫秒ID碰撞。
- VM按当前conversation或新对话project定位状态。切会话暂停报告并清离开目标的确认；切项目清草稿确认、保留文档。首发创建成功且用户仍在原project/context时将Draft标签移交新Session并删除旧Draft条目，不影响下一次新对话；失败/已切走不移交。
- 文件读取捕获原provider及owner，晚返回须同时通过owner+请求序号。每种新打开意图（包括目录/揭示）均使旧请求失效。普通文档与标签选择清待确认，当前报告重复打开继续合流，不引入重复读取。
- ArtifactsPanelContent成为小型VM适配层，纯ReaderPanelContent只接state/onSelect/onRetry；活动Text由VM叠加当前聊天流式文档，报告加载状态由原ReportLoadPresenter派生。纯View不再持有VM、调用getLiveArtifactItem或读取文件/Core。已有Text/Image/Report阅读、Tab、按钮、动效与导出原样复用，无新增UI字符串或资源。

## 所有权与保留项

| 内容 | 当前唯一来源 |
|---|---|
| 文档列表、选中ID、待打开确认 | readerStates[Session或Draft] |
| 实时正文与主/子代理任务事实 | Core快照与聊天投影，只派生给Reader |
| 报告读盘状态/来源缓存 | 原ReportLoadPresenter |
| Dock与其它本地工作台状态 | 原兼容层，本批不迁移 |
| 导出/图片缩放等局部状态 | 原Reader，后续R11复核 |

本批只完成R02a/b的阅读器切片，不宣称整个WorkspaceChrome/SessionPresentationState、Route/Screen、DTO解耦或导出编排已完成。错误族、diff、其它概览Store及其它VM分组接口继续逐项复核；currentPlan等兼容元数据未在本批迁移。会话/项目删除后的完整本地缓存回收仍随整体状态清理复核。浏览器排除，无Core契约/数据库/任务执行语义变化。


## 验证证据

最终34个suite共218项，零失败/错误/跳过；JVM及wasm编译通过。相对上批192，新增11项，额外纳入既有快照/会话/概览15项，不把扩展回归误算为新用例。

- WorkspacePresentationStateTest 8项：不可变原位更新/无效选择；跨session同ID正文与选择独立；新对话按project隔离；Core快照与显式关闭不冲掉选择；图片去重与唯一ID；真实VM首发创建的草稿移交；待确认不跨session且新文档清理；受控真实工作线程文件读取在换项目后晚返回，原provider捕获且不抢新文档。
- ReaderPanelDeliveryTest 3项：纯标签精确ID回调/VM投影正文替代本地缓存；切状态后报告重读精确ReportKey；空态无假文档操作。Markdown异步解析以真实内容语义节点等待，不靠固定sleep；首次大回归暴露的过早断言已修正，最终完整回归通过。
- 原报告VM接线、报告缓存/取消、消息/输入/概览、设置、共享控件、动效与MergeLocalUiState/ConversationStateAndSubagent/OverviewWindowState回归通过。

```bash
./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*WorkspaceEffectRoutingTest' --tests '*BackgroundTaskPresentationTest' --tests '*BackgroundTaskOverviewDeliveryTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest' --tests '*ReportLoadingPresentationTest' --tests '*ReportReaderDeliveryTest' --tests '*ReportFileSourceTest' --tests '*ReportWorkspaceIntegrationTest' --tests '*WorkspacePresentationStateTest' --tests '*ReaderPanelDeliveryTest' --tests '*MergeLocalUiStateTest' --tests '*ConversationStateAndSubagentTest' --tests '*OverviewWindowStateTest'
```

git diff --check通过。本批为状态归属/接口迁移，保留原真实阅读组件和视觉规格；未创建新视觉方案或声称新窗口截图验收。无idea-mcp/Hot Reload工具，依AGENTS未用bash启动APP/server，完整APP/原生保存/跨平台实机待启动或配置idea-mcp。未调用真实模型、系统文件打开或停止用户任务；受控文件读取为fake，无真实用户数据变更。

架构02/04、canonical交互标准、主计划与部件清单同步。下一步按架构和源码复核错误/diff等本地状态归属和只读投影，继续小批推进R02a/b；不扩展浏览器范围。
