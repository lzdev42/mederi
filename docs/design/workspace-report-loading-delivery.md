# R03c：报告异步加载与视图 I/O 迁出

> 2026-10-30：代码与组件验证完成。用户授权继续；本批只收口报告读取/反馈，不改导出或阅读器样式。完整APP实机待验。

1. 事件卡改为纯点击回调，完全移除文件provider/readText。VM建立以session+event为键的Report请求和专用阅读器产物；按需异步读取，重复打开合流，Content缓存，不把加载文案伪装成可复制/导出的文档。
2. ReportLoadPresenter在Default dispatcher通过宿主读取，提供Idle/Loading/Content/Missing/Error；取消+代际/活动目标校验，切报告/文档/会话/关闭面板后旧返回不得覆盖或抢焦点。显式重试重新读取，取消后再打开可恢复。
3. 文件宿主接口追加有类型Result读取，JVM区分缺失与真实I/O失败；旧readText签名/行为保留，其它读盘入口不改。无来源/空路径/空文件明确Missing；原事件摘要原样保留并标注，不冒充全文；异常共享ErrorBoard与真实重试。
4. 原文本/图片/计划/导出路径保持。跨会话报告键隔离，文档选择使旧文件打开请求失效。测试fake读取延迟/取消不响应/权限失败/缺失/重复打开/换会话/其它文档，真实阅读器状态组件与卡片回调；wasm编译和既有回归。架构/流程/计划同步，完整APP仍待idea-mcp。


## 实际交付

- EventMessageCard仅回调本地化title，ChatListContent交给VM。DocumentTarget/ArtifactItem新增UI-only Report变体，文件读取不再发生在组合过程中。
- ReportLoadPresenter在Default dispatcher读宿主文件，按session+event、完整请求与source引用身份绑定缓存/原闭包。重复打开合流；换文档/会话、关闭面板与VM销毁取消读取且使代际失效，旧结果不选择标签。旧会话标签保留原provider，不用新项目根重读相对路径。文件打开序号也随新的文档选择失效。
- ProjectFileTreeProvider追加兼容readTextResult，JVM区分不存在与真实I/O失败，旧readText不变。无provider/无路径/空白正文为Missing，不造报告全文；未增加远端获取或Core契约。
- 纯ReportReaderContent使用共享加载/错误/动作；加载无复制导出，实际全文复用旧Reader；原摘要明确标注来源并保留原值，空摘要无假工具栏。后台命令/定时器无独立报告时直接展示原事件正文，不误报缺失。
- 无新依赖、数据库或Core执行行为变化，中英文资源同步。架构02/04、canonical组件规范、主计划与部件清单已同步。

## 验证证据

29个相关suite共192项测试（既有176＋本批16），零失败/错误/跳过；JVM及wasm编译通过。ReportLoadingPresentationTest 7项覆盖延迟/重复打开/原文缓存、缺失/空白、Result及抛出I/O异常、取消、显式重试、跨session相同eventId、同key更换路径/摘要/来源、NonCancellable迟到返回、隐藏恢复原来源、关闭拒绝后续读取。ReportReaderDeliveryTest 7项覆盖五态、真实重试、原文行数字符数、摘要来源、空内容工具、注入抛异常provider证明Event卡组合/点击零读取，以及实际Reader深浅预览。ReportFileSourceTest 1项用temp内自有文件验证UTF-8原值、missing null与directory I/O失败，并清理夹具。ReportWorkspaceIntegrationTest 1项验证VM报告产物/状态、tab与关闭面板、后台原事件正文、跨会话同event的不同报告键。

```bash
./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*WorkspaceEffectRoutingTest' --tests '*BackgroundTaskPresentationTest' --tests '*BackgroundTaskOverviewDeliveryTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest' --tests '*ReportLoadingPresentationTest' --tests '*ReportReaderDeliveryTest' --tests '*ReportFileSourceTest' --tests '*ReportWorkspaceIntegrationTest'
```

已目视检查temp/workspace-report-loading-delivery/report-reader-dark.png与report-reader-light.png，为实际Content组件夹具，非完整APP。生成方式：`MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-report-loading-delivery" ./gradlew :app:shared:jvmTest --tests '*ReportReaderDeliveryTest'`。资源XML合法且无重复键；git diff --check通过。

## 保留与下一步

当前会话报告键与加载隔离已完成；原窗口级artifactItems的完整会话归属仍待R02a，不宣称全局状态已隔离。原导出工作态/原生保存、其它概览Store查询、R02a/b/c整体MVVM、S6与完整APP三平台验收保留。当前会话无idea-mcp/Hot Reload工具，按AGENTS规则未用bash启动APP/server；运行完整APP需先启动/配置idea-mcp。下一批先按架构与真实源码复核R02a/b，优先明确状态所有权，再缩小View接口；浏览器继续排除。
