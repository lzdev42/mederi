# R03a：一次性效果唯一消费与项目菜单受控状态

> 2026-10-30用户授权继续。代码与组件验证完成，完整APP实机待验。先核对当前代码，确认MainScreen与ChatInputCard仍竞争消费同一receiveAsFlow，实际问题尚存；本批只收口这条窄接口，不顺带重写整个WorkspaceViewModel。

1. 保留原Channel与UiEffect三种命令，不改为广播或用计数器重放。MainScreen Route通过同一个表现状态协调器唯一消费，分别落为设置显示、项目菜单展开、压缩提示状态。
2. Workspace与ChatInputCard只接项目菜单expanded只读值+onExpandedChange回调；ProjectSelectorMenu也受控，按钮/菜单dismiss/项目选择/打开目录统一修改同一个展开状态。输入区不订阅全局effects，不保留openRequest计数或第二份expanded。
3. 本地关闭不发新effect，菜单/设置/提示状态不进Core或Preferences；切会话/欢迎页与对话分支切换不重播已消费命令。保持原Core生命周期、异步输入、目录选择、项目ID和压缩文案。
4. 验证：交错三类命令、重复/重新订阅、重组/模拟分支及会话切换、菜单关闭后再次打开、精确选择/目录回调、设置与压缩独立。用实际表现协调器+菜单组件，不启动真实APP/网络或目录选择器。wasm编译、相关既有测试，更新架构/流程/主计划。


## 实际交付

- 新增WorkspaceEffectState及rememberWorkspaceEffectState：窗口Route唯一收集稳定effects Flow，穷尽UiEffect三类命令，无else静默丢弃。设置显示/项目菜单展开/压缩提示是同一窗口协调器内各自独立的短命状态；关闭只改本地状态。
- MainScreen原本地设置/压缩状态迁至协调器，三种Workspace布局都显式传projectMenuExpanded/onProjectMenuExpandedChange；Workspace欢迎页/有消息两条输入分支继续传同一只读值/回调。ChatInputCard不再collect全局effects，删除打开计数器；ProjectSelectorMenu删除openRequest和内部expanded/LaunchedEffect，按钮、选择、取消与目录入口共用唯一展开状态。
- 原Channel/UiEffect/VM派发方式保持，不改SharedFlow、不增加持久化、计数器或Core字段。关闭过的菜单不会因输入区重建而重新打开；新命令或按钮仍能打开。项目精确ID/null与原目录回调保持，选中或目录动作前先关闭菜单。
- 未改变布局/颜色/动效、Core/会话/压缩/发送生命周期，未新增依赖或用户文案。ProjectSelectorMenu仍在既有包装层读取AppState项目流，此批不冒充完整R02b Route/Screen拆分。

## 验证证据

最终23个suite共160项测试（既有152＋本批8），0失败/错误/跳过；wasm/JVM编译通过。新增WorkspaceEffectRoutingTest覆盖：真实Channel交错/重复三类命令全部到达；本地关闭独立且不发新effect；重组/欢迎页/模拟会话切换不重启收集器；卸载重订阅不重放旧命令但能接新缓冲命令；实际菜单聚焦项Escape关闭/新命令再开；同名项目精确ID与清空null；目录回调前关闭、普通按钮再开；已关闭实际菜单随key/分支移除重建仍关闭。

首轮修正：JVM属性私有setter与同名公开方法冲突，动作命名为updateProjectMenuExpanded；可空项目ID断言补明确泛型；Escape测试对真实菜单项请求焦点后发键，沿用既有共享菜单测试方式，不向未聚焦窗口根发送键事件。

```bash
./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*WorkspaceEffectRoutingTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

静态复核：effects只在MainScreen装配、在WorkspaceEffectState单点collect，ChatInputCard无订阅；openRequest/projectMenuOpenCount为零。git diff --check通过。测试使用fake AiCore/内存项目流与偏好，不打开原生目录选择器、不创建真实项目；没有视觉设计变更，不新造另一份效果模拟图。

## 当前交接

R03a窄接口收口完成，不表示整个MVVM计划完成。已复核R03b的UI轮询与单份后台列表仍存在，R03c报告读取仍在视图相关路径；后续分别建立会话隔离/取消/旧响应防护与报告Loading/Content/Missing/Error协调，再继续状态所有权/契约解耦/S6清理。不能按旧文档直接推定所有问题仍存在，实施前继续定向复核。

当前没有idea-mcp工具，完整APP需先启动/配置后才能运行；未用bash启动APP/server。整个MainScreen窗口、多平台原生焦点/IME覆盖与项目选择器的端到端仍待实机验收。浏览器专属改动继续排除，原有宿主遮挡处理保持。
