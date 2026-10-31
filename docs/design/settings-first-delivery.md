# 设置第一批：导航、公共分组与外观/系统样板

> 2026-10-29，用户要求优先改设置，再做其余工作台收口。状态：代码与组件验证完成，完整APP实机待验；设置主计划S1。

顺序调整：先S1→后续设置页，P0遗漏Skill/MCP弹窗及全局动效/状态架构任务保留，不因调序消失。

- 设置七分类和原分发保留，默认Agents不变；桌面左侧分类+右侧单层标题/说明，窄屏或大字体用同源横向导航；关闭独立固定，内容空白不触发背景关闭。
- SettingsWorkspaceContent只收分类/回调/内容槽。GeneralSettingsPanel仍读AppState.theme/language并发原setTheme/setLanguage，纯GeneralSettingsContent只渲染；不新建配置副本。
- SystemSettingsPanel仍显示原版本/路径值，完整选择复制；共享复制状态与动作，不伪造文件系统路径。
- SettingsSection统一Title/Caption，SettingsCard改8dp无框中性分组，SettingsRow在窄宽/大字体上下排列，图标取消彩色底座。输入/Stepper/模式控件随后续领域页迁移，不能宣称所有设置控件已统一。
- 选择复用WorkspaceNavigationTab，新增末尾可选role参数（默认Tab兼容原调用），外观/语言用RadioButton语义，不另写近似选项控件。

验证目标：七分类/选中/原目标、关闭/内容点击、主题/语言精确回调、系统完整值复制、窄宽/大字体动作可达、公共分组控件；实际Content深浅主题预览、wasm编译和前六批回归。APP只能idea-mcp运行，未具备环境则明确未验。


## 实际验证与交接

10个suite共75项测试（本批SettingsFirstDeliveryTest 8、前六批工作台56、语言资源3、ReasoningMenu 6、ProviderSettingsFreeFilter 2），0失败/错误/跳过；wasm编译通过。测试断言七分类精确回调/selected、320dp与1.5字体导航/关闭可达、焦点分类Enter选择/Escape关闭独立、设置空白不关闭而背景关闭、全部主题/12种语言保留与外部状态回流、280dp/2倍字体完整路径复制与反馈、窄宽长说明不丢操作。未操作真实供应商或设置偏好，测试只使用实际Content与fake回调。

已检查4张实际组件预览：`temp/settings-first-delivery/settings-general-dark.png`、`settings-general-light.png`、`settings-system-dark.png`、`settings-system-light.png`。它们是实际SettingsWorkspaceContent+General/System Content组合，不是完整APP截图。`git diff --check`通过。当前无idea-mcp，需先启动/配置才能运行APP/server；真实窗口焦点归还、全页Tab顺序、原生网页/终端遮挡及三平台完整设置仍未验。未用bash启动APP/server。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/settings-first-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*SettingsFirstDeliveryTest' --tests '*Workspace*DeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

下一批：S2供应商列表/详情/模型与表单，复用当前导航、公共动作/弹窗/菜单；保留所有Provider行为，不另建风格。Agents/浏览器/沙盒/遥控内部旧控件还未全部迁移；P0遗漏弹窗、MotionPolicy、剩余MVVM与实机验收继续在总计划保留。
