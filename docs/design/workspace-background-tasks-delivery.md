# R03b：会话隔离的后台任务查询

> 2026-10-30用户授权继续。代码与组件验证完成，完整APP实机待验。复核确认旧实现仍由概览行每2秒触发VM launch，结果写单份列表，停止失败仅日志。本批收口查询/反馈，不改后台任务执行与停止契约。

1. 新表现协作者按会话保存查询快照、加载/已加载、查询错误、停止busy与错误；切会话立即切读对应快照，不串旧列表。单个当前轮询job，串行请求；取消+代际标识拒绝不响应取消的旧结果，手动刷新合流不叠请求。
2. Workspace Route按概览可见性持有查询订阅，重复订阅共用一个loop，释放最后订阅暂停查询，显示时恢复。View只读state/回调，不调度delay/while。隐藏/切会话/释放订阅不停止Core任务。
3. 用户停止请求捕获当前会话/精确任务ID，重复在途停止去重，失败按会话/目标保留共享ErrorBoard，查询成功不吞掉停止错误。成功仍回读Core，不伪造任务已停止。错误只在对应会话展示。
4. 验证：虚拟时钟+fake AiCore控制A→B晚返回、同会话隐藏/显示旧响应、单请求与刷新合流、订阅/暂停/恢复、停止失败/重试与切会话、取消不冒充错误；实际概览纯Content精确动作、加载/错误/空态/busy。wasm编译、既有组件回归；更新架构/流程和主计划，完整APP仍待idea-mcp。


## 实际交付

- 新增BackgroundTaskPresenter/BackgroundTaskQueryState，按会话持有Core查询快照和查询/停止反馈。VM attach绑定会话、onCleared关闭协作者；backgroundTasks为只读兼容投影，不再有单份可写列表或旧pollBackgroundTasks入口。
- Workspace Route在概览偏好已加载且可见时持有幂等释放租约，重复订阅共用一条当前循环；最后释放只取消查询。View不再LaunchedEffect/while/delay定时触发。请求串行，完成后2秒或合流刷新信号继续，20次刷新在途可合流为下一次读取，不堆叠网络请求。
- bind/隐藏/关闭递增代际并取消查询；即使旧接口NonCancellable晚返回，提交前仍校验当前session/代际/观察状态。查询取消不显示假错误，清busy后允许重新刷新。异常保留上次成功列表及错误，不假空成功。
- 停止捕获原session/taskId，只接当前已知running目标，逐目标在途去重；停止协程不被隐藏概览的query取消。失败按原会话/目标保留，成功读取不清停止错误；成功停止仅刷新Core，不伪造终态。取消不冒充失败，finally清停止busy。
- OverviewRunningTaskContent为纯视图，真实loading/loaded空列表/未加载状态区分；查询/停止失败共用ErrorBoard及实际刷新/重试/关闭。错误在折叠头也有文字提示。初次查询可显示loading，已有快照的定期刷新不反复插入spinner/改变正文高度；原整栏运行呼吸与异步对话保持。
- 无Core业务/停止契约、AiCore/Server、数据库或依赖变化；kotlinx-coroutines-test沿用既有测试依赖。新文案中英同步。UI快照只为表现缓存，任务执行真理源仍为Core。

## 验证证据

全量相关25个suite共176项测试（既有160＋本批16），0失败/错误/跳过，wasm/JVM编译通过。BackgroundTaskPresentationTest 8项以虚拟时钟/fake AiCore控制：重复订阅/幂等释放、20次刷新合流及最大并发1；A→B旧NonCancellable响应晚到不覆盖；同session隐藏再显示旧响应不覆盖；失败保留快照/成功清查询错误/取消可恢复；停止晚失败只回原session、成功查询不吞stopError、旧目标不能误停新会话任务；停止成功仅权威回读；抛出或Result包含取消均清busy无假错误；Core就绪/无会话/close不假查或停止任务。

BackgroundTaskOverviewDeliveryTest 8项覆盖实际Content：真实加载/取消后未loaded/成功empty区分；错误/刷新合流动作；精确目标停止与单目标busy；running/completed/gone错误只给真实重试/关闭；busy错误不能二次停止；会话投影/null不展示旧目标；折叠count与外部展开唯一状态；折叠错误可发现、缓存刷新不叠loop及深浅实际组件预览。

```bash
./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*WorkspaceEffectRoutingTest' --tests '*BackgroundTaskPresentationTest' --tests '*BackgroundTaskOverviewDeliveryTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

最后仅将预览夹具强制560dp高度改为自然高度，定向8项概览测试追加通过，未改生产行为。已目视检查 `temp/workspace-background-tasks-delivery/background-tasks-dark.png`、`background-tasks-light.png`，为实际Content配fixture，不是完整APP。新预览命令：`MEDERI_UI_PREVIEW_DIR="$PWD/temp/workspace-background-tasks-delivery" ./gradlew :app:shared:jvmTest --tests '*BackgroundTaskOverviewDeliveryTest'`。资源XML合法/无重复键，git diff --check通过。所有任务/停止均fake，未查询/停止真实用户后台进程。

## 当前交接

R03b窄接口代码/组件收口完成，不代表整体MVVM完成。下一批R03c报告异步加载与视图I/O迁出，然后继续状态所有权/契约分层、S6一致性和最终实机。当前没有idea-mcp工具，完整APP需先启动/配置后才能运行，未用bash启动APP/server；真实进程/定时器/遥控端HTTP取消、完整窗口/三平台仍待端到端验收。浏览器专属功能继续交另一AI删除，本线排除。
