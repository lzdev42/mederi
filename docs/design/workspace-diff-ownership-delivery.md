# R02a/b第二步：diff查询归属与反馈

> 2026-10-31：代码与组件验证完成。继续上一批阅读器切片，只迁移diff查询/选择/反馈；主对话ErrorState、浏览器、导出不在本批。完整APP实机待验。

1. diff表现状态按会话缓存，Core为内容真理源；显隐只读Dock，不再独立showPanel双写。查询绑定session/messageId、取消与代际校验；关闭/切会话/其它面板使旧返回失效，完成不打开面板。
2. 打开立即显示读取状态；重复在途同目标合流；新目标清旧内容，已有同范围刷新保留快照。刷新沿当前messageId范围，保留有效文件选择；失败明确可见不冒充空成功，重试/刷新共用真实动作。
3. DiffPanelContent仅装配纯DiffQueryContent(state,onSelect,onRefresh,colors)，复用已有DiffFilesContent、ErrorBoard、WorkspaceLoadingIndicator与按钮/Tab；无新样式与动效。
4. 验证状态机、VM接线和纯UI：会话与单轮隔离、NonCancellable晚返回、关闭不回弹、范围刷新、失败/重试/空态/有效选择、缓存恢复及重复点击；既有回归、JVM/wasm与文档同步。完整APP仍需idea-mcp。

## 实际交付

- 新增DiffPresenter，按session持有当前messageId范围、内容、文件选择和查询反馈。重复在途同范围请求合流；换范围清旧快照，同范围刷新保留最近成功内容。初始路径意图也留在会话状态，切走再恢复不会丢失；成功后仅保留实际存在的路径，否则选择首文件。
- VM保留diffItems/selectedDiffFilePath只读兼容投影；showDiffPanel直接派生Dock，删除DiffUiState.showPanel副本。openDiff立即打开面板，刷新经refreshDiff保留原范围。显隐/attach/快照更新同步协作者，可见时恢复未完成查询；onCleared关闭。
- 查询只提交当前session/代际且仍可见的结果。取消不冒充错误，失败不冒充成功空列表；关闭/其它面板/切会话后旧返回不打开Dock，不污染新会话。协作者完全没有打开Dock回调。
- 新纯DiffQueryContent仅消费状态/回调。初次读取与显式刷新都复用WorkspaceLoadingIndicator，失败复用ErrorBoard和真实刷新；旧非空快照继续保留。成功空态、未读取、失败与无会话明确区分；空快照失败只有一个重试入口；无会话保留原Dock入口但无无效刷新动作。DiffFilesContent末尾新增默认refreshing参数，空态刷新忙时禁用重复点击，原DiffView/Tab/共享动作与动效不变。
- 无新增依赖、Core/AiCore/Server/数据库变更，无任务停止或模型调用；中英文四条反馈资源同步。主对话ErrorState及其异步反馈未迁移，不把diff错误收口当作全部错误状态已完成。

## 本批边界与下一步

错误/diff原先同属R02a/b复核，本批只交付diff查询与错误反馈。主对话ErrorState仍有本地反馈/快照事实混存和异步发送/回滚目标归属，需要单独小批处理；其余WorkspaceChrome/SessionPresentationState、完整Route/Screen分组、DTO解耦、R11导出和S6/三平台实机仍待后续。浏览器由另一AI处理，本批不修改。

架构02/04、canonical组件标准、主计划和部件清单同步。当前无idea-mcp/Hot Reload工具，依AGENTS未用bash启动APP/server，完整APP/原生实机需先启动或配置idea-mcp。


## 验证证据

最终37个suite共234项（原218＋本批16），零失败/错误/跳过；JVM及wasm编译通过。

- DiffPresentationTest 8项：不可见不读、重复在途合流；保留单轮messageId/有效选择与刷新快照；跨会话NonCancellable迟到返回、同session换轮拒旧结果；隐藏/恢复；Result失败/抛出异常/取消/成功空结果区分；关闭拒后续操作；显式重新打开已有缓存确实刷新。初始文件路径意图跨会话保留。
- DiffWorkspaceIntegrationTest 2项：真实VM关闭及打开阅读器后旧结果不抢回Dock；无会话仍可打开原入口但不发无效查询；单轮刷新、切会话隔离和切回恢复，精确Core调用范围。
- DiffQueryDeliveryTest 6项：首次等待/暂停/成功空态；共用ErrorBoard且只有一个真实重试；刷新忙时禁重复并显示等待；失败保留文件Tab及精确路径回调；实际Content深浅预览；无会话有诚实可用说明且无无效刷新。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-diff-ownership-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*WorkspaceEffectRoutingTest' --tests '*BackgroundTaskPresentationTest' --tests '*BackgroundTaskOverviewDeliveryTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest' --tests '*ReportLoadingPresentationTest' --tests '*ReportReaderDeliveryTest' --tests '*ReportFileSourceTest' --tests '*ReportWorkspaceIntegrationTest' --tests '*WorkspacePresentationStateTest' --tests '*ReaderPanelDeliveryTest' --tests '*MergeLocalUiStateTest' --tests '*ConversationStateAndSubagentTest' --tests '*OverviewWindowStateTest' --tests '*DiffPresentationTest' --tests '*DiffWorkspaceIntegrationTest' --tests '*DiffQueryDeliveryTest'
```

已目视检查temp/workspace-diff-ownership-delivery/diff-query-dark.png与diff-query-light.png：为实际错误反馈＋保留diff组件夹具，非完整APP。单独生成可用相同环境变量并运行`./gradlew :app:shared:jvmTest --tests '*DiffQueryDeliveryTest'`。资源XML合法且无重复键，git diff --check通过。测试全部fake Core，无真实文件修改、模型调用或任务停止。完整APP/原生实机仍待idea-mcp，不据组件测试宣称三平台已验收。
