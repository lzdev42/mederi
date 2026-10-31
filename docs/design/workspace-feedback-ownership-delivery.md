# R02a/b第三步：主对话错误反馈归属

> 2026-10-31：代码与组件验证完成。沿用现有ErrorBoard/ErrorDetailDialog，只迁移反馈归属和VM接线，不改Core错误事实/发送/回滚契约；完整APP实机待验。

1. Core快照错误与本地操作失败分开，按Session或项目Draft归档；同错误快照不撤销显式关闭，新错误可重新出现。详情绑定当前错误身份，切目标关闭，不将旧诊断/断流标记附加给新本地错误。
2. 发送、创建与回滚反馈捕获原owner+操作序号；后台失败只存原目标，不写当前窗口。新操作/关闭使旧反馈失效，新Core错误优先；首发创建后发送反馈归新Session。回滚成功只有原输入上下文仍有效时恢复编辑内容，避免污染另一会话。
3. VM保留只读兼容属性，错误详情装配消费同一分组状态；共享View和文案不另造风格。
4. 验证按目标隔离、重复快照关闭、新错误/断流/本地错误区别、详情身份、旧序号、创建/发送/回滚延迟返回、原上下文编辑恢复；全套相关回归/JVM/wasm，架构与主计划同步。完整APP仍待idea-mcp。

## 实际实现

- WorkspaceFeedbackPresenter按FeedbackOwner.Session/Draft归档Core投影、本地提示、关闭身份、详情身份和本地操作序号。反馈投影仍为ErrorState，VM兼容error/errorDiagnostic/errorId/isStreamInterrupted/isErrorDetailOpen只读同源。
- Core快照只更新对应session的Core错误投影，相同错误不撤销关闭；不同非空Core错误优先，关闭旧详情并使旧本地操作反馈失效。Core清空复位断流能力，本地失败独立保留。关闭不改Core事实/缓存/持久化。
- 本地失败只投影自身UiMessage，不附加前一条Core诊断/错误ID/断流标记。新操作和关闭推进原目标sequence，过期响应不顶替新反馈。后台失败可留在原目标供切回查看，不显示到当前其它目标。
- 发送校验、创建失败与发送失败统一该入口；创建失败归原项目草稿，创建成功后的发送归新Session。原Core发送/创建、稳定消息ID、乐观对账、未接纳输入恢复保持。
- 回滚反馈归原Session，Core回滚仍执行；只在原会话、inputContextVersion、操作票据、草稿文本与图片/粘贴附件都未被替换时恢复编辑。切去其它会话、离开再返回、新操作或后来打字均不被旧成功结果覆盖。
- 新纯WorkspaceFeedbackContent只本地化分组状态并委托原ErrorBoard；输入区与错误详情装配消费同一个feedbackState，详情仍是原ErrorDetailDialog。无新风格/动效/提示壳、资源、依赖、Core/AiCore/Server/数据库变更。

## 保留与下一步

本批仅主对话错误归属与反馈分组；reader/diff/报告/后台查询已交付的切片保持。完整SessionPresentationState/WorkspaceChrome、剩余分组接口与R02c契约UI依赖仍需复核，不能把本批当作整体R02完成。其它独立提示（文件打开失败/图片剔除/能力提示等）、原生导出与布局偏好生命周期没有迁移；全局缓存删除回收和S6/三平台实机仍待整体清理。ReaderOwner/FeedbackOwner当前各自为小切片键，最终聚合需按完整所有权表收口，避免在契约层引入UI类型。

架构02/04、canonical组件规则、主计划与部件清单同步。当前无idea-mcp/Hot Reload工具，按AGENTS未用bash启动APP/server；完整APP/原生实机仍需先启动或配置idea-mcp。浏览器由另一AI处理，本批排除。


## 验证证据

最终41个suite共256项（上批234＋本批15＋额外既有会话设置恢复7），零失败/错误/跳过；JVM及wasm编译通过。

- WorkspaceFeedbackPresentationTest 7项：本地失败不继承Core字段；同错误关闭/重复快照/切回保持与新身份重现；session与project Draft原目标归属；新操作及关闭拒旧票据；新Core错误优先、普通快照不吞本地错误；详情身份与换目标；Core清空复位断流/空态无详情。
- WorkspaceFeedbackIntegrationTest 6项：真实VM快照关闭/切换/新错误；延迟发送失败归原会话且无旧诊断；双发送晚旧失败不覆盖新反馈；创建失败归原项目；首发创建后发送失败归新Session；延迟回滚失败归原目标，成功不覆盖其它会话或后来输入，而原上下文未变时仍能恢复编辑。
- WorkspaceFeedbackDeliveryTest 2项：纯反馈适配实际共享ErrorBoard，Core警告继续/详情/关闭真实回调、本地错误无继续；清空无旧正文或幽灵动作。既有工作台/设置/查询/阅读器/diff/动效、快照/概览及7项会话设置恢复回归通过。

```bash
./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*WorkspaceEffectRoutingTest' --tests '*BackgroundTaskPresentationTest' --tests '*BackgroundTaskOverviewDeliveryTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest' --tests '*ReportLoadingPresentationTest' --tests '*ReportReaderDeliveryTest' --tests '*ReportFileSourceTest' --tests '*ReportWorkspaceIntegrationTest' --tests '*WorkspacePresentationStateTest' --tests '*ReaderPanelDeliveryTest' --tests '*MergeLocalUiStateTest' --tests '*ConversationStateAndSubagentTest' --tests '*OverviewWindowStateTest' --tests '*DiffPresentationTest' --tests '*DiffWorkspaceIntegrationTest' --tests '*DiffQueryDeliveryTest' --tests '*WorkspaceFeedbackPresentationTest' --tests '*WorkspaceFeedbackIntegrationTest' --tests '*SessionSettingsRestoreTest'
```

git diff --check通过。本批保留原共享View/视觉规格，无新样式方案或新完整窗口截图证据；没有用组件行为测试冒充原生实机验收。全部Fake/Mock Core，不调用真实模型或回滚用户数据。无资源变化，不需新增i18n键。完整APP仍待idea-mcp。
