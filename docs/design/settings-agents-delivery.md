# 设置 S3：Agents 角色与并发

> 2026-10-30，用户授权继续。代码与组件验证完成，完整APP实机待验。下列规范与实际交付一致。

1. 状态协调：新增 AgentSettingsViewModel，只经现有 AiCore 四个配置 API 读取/写入；角色列表与并发独立加载、错误、忙态。保存成功后重新读取权威值；失败展示共同 ErrorBoard，不再把读取失败伪装为空列表或并发 2。保存期间拒绝同类重复操作，防止全量角色配置互相覆盖。
2. 内容与控件：Route 取得 AppState 模型/供应商/当前主模型流，纯 Content 收状态和回调。角色采用留白、16dp 图标、共同字号与设置行；模型/推理/Key 菜单复用 WorkspaceDropdownMenu/MenuItem/ActionButton，模型组复用 ModelPickerList。菜单状态按角色归属。并发步进与分段单选迁入共同控件，兼容浏览器现有调用。
3. 保持语义：模型选择保留可用推理档，恢复继承清空三个覆盖值；Key 使用精确 ID，继承 null，轮询仅多 Key 时可选且发契约哨兵。无效/缺失 ID 明示原 ID，不伪装成继承。保持现有 API 与运行中任务行为，不新增角色/模式/业务层。

验收：VM fake AiCore 验证请求完整值、保存后回读、并发边界、失败/重试/重复提交；Compose 验证继承/指定/推理/Key 精确回调、共享步进禁用与边界、窄屏/大字体/长名称及深浅实际组件预览。运行本批测试、既有设置回归与 wasm 编译；更新架构/流程、共同标准和主计划。完整 APP 仅经 idea-mcp，本环境缺工具时注明未验。


## 实际交付与边界

- 新增 `AgentSettingsViewModel/AgentSettingsUiState` 和纯 `AgentSettingsContent/AgentRoleSettings/AgentSettingsActions`；删除旧 `AgentConfigCard/SubagentModelMenu/SubagentReasoningMenu`。页面没有 AiCore I/O；模型、供应商与主模型仍读 AppState 权威流，角色和全局配置只以 Core 回读结果更新。
- 角色按同一 SettingsRow 排列，无图标彩色底盘/嵌套继承框/粗徽章。菜单和按钮复用工作台共享原子，模型组继续共用 ModelPickerList；选项触发保存后关菜单。宽屏左说明右操作；窄屏/200%字体自然上下排列，整页惰性滚动可达全部角色。
- 配置读取失败不再显示默认2或伪空列表；共同 ErrorBoard 提供对应区重新读取。写失败保持已确认快照，写成功但回读失败阻止继续覆盖。角色三个字段同一次提交，在回读前串行；并发区独立忙态；进入页面时刷新配置，加载请求去重。
- 原 Key 菜单未正确显示轮询状态；现在当前轮询明确标注、多真实Key时提供轮询选项，传契约哨兵；同别名按精确ID操作，默认Key说明/掩码保留。缺失Key显示原ID。模型更换保留有效推理档，否则送null；恢复全部继承清空三个覆盖。Core已有并发大于16时允许逐步减小，避免新增UI门禁使合法旧值无法调整。没有新建运行时默认值。
- NumberStepper 复用 WorkspaceIconButton 并追加兼容 enabled；SegmentedPillToggle 复用可换行 RadioButton 导航项。浏览器等已有调用保持接口；未重写尚待 S4 的浏览器表单/Store。现有Agents面板没有AgentMode选择，本批未新增，主工作台原模式选择保持。
- 无Core行为、数据库、AiCore契约字段/API、依赖变化；只修正 SubagentModels 注释中轮询哨兵的过时拼写。

## 验证证据

最终17个suite共117项测试（既有103＋本批14），0失败/错误/跳过；`:app:shared:compileKotlinWasmJs` 和 JVM 编译通过。新增 AgentSettingsViewModelTest 4项、AgentSettingsDeliveryTest 6项、SettingsControlsDeliveryTest 4项：完整三个字段/精确角色与模型/Key ID、继承与轮询/重复别名、有效推理保留与失效清除、初始无假默认/独立失败重读、在途重复写阻止、权威回读/回读失败阻止覆盖、原大并发向下调整、步进边界/disabled、单选外部状态/窄屏换行、280dp/200%字体操作可达。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/agents-settings-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*Workspace*DeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

已目视检查实际Content fixture深浅截图：`temp/agents-settings-delivery/agents-dark.png`、`agents-light.png`。截图为真实组件配测试数据，不是完整APP，也未操作真实用户配置。中英资源XML合法且无重复键，git diff --check通过。当前没有idea-mcp工具；完整APP需先启动/配置idea-mcp才可运行验证，未用bash启动APP/server。三平台原生窗口、完整焦点/回焦与真实Core端到端仍待验。

下一批 S4 浏览器设置，沿用相同原子与 Route/Content 边界，重点保持即时保存、500ms防抖、草稿不被回流覆盖、JSON校验与安装状态；不改现有功能。P0遗漏弹窗、MotionPolicy及整体MVVM/三平台收口继续保留。

2026-10-30用户后续调整：取消原下一批S4浏览器，删除交给另一位AI；本线下一批改为S5沙盒/遥控，详见设置主计划。
