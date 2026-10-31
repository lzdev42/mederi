# S5：沙盒与遥控设置

> 2026-10-30用户授权继续，代码与组件验证完成，完整APP实机待验。沿用轻量中性风格、MVVM＋单向数据流和共同MotionPolicy；浏览器排除。

1. 沙盒：状态查询迁入表现VM，白名单仍读写AppState唯一源。无hooks/未知状态不造available；失败就近ErrorBoard与真实重读。完整路径可读/可选择，添加trim去重、移除精确路径，保持原写穿与持久化行为。
2. 遥控：Route读取AppState/宿主hook，Content只收状态和回调。服务状态、地址、密码、隧道分层；复用共同字段、动作、开关、错误/加载与复制反馈。令牌/域名仍即时经原setter保存；密码仍随原开关提交，不新增隐式重启/保存。服务/隧道启动中、失败、不支持、无地址均如实呈现。
3. 可维护性：共享可复制值行给系统与遥控，密码/Token共享显隐字段；现有mini开关补真实Switch语义/键盘与热区，同一View所有调用一致。关闭/切页只卸载UI，不调用停止；不改Core/hooks生命周期、安全规则或密码门。
4. 验证：fake回调/hooks，不启动真实网络服务/隧道，不修改真实白名单。覆盖精确路径/请求/凭据/复制、busy/失败/不支持、长路径/域名/窄屏大字体，深浅实际Content预览与wasm编译、已交付组件回归；更新架构/流程/共同标准及主计划。


## 实际交付

- 沙盒新建SandboxSettingsViewModel/UiState，仅查询宿主status；Default dispatcher、防重入、取消透传、失败共同ErrorBoard/真实reload。Route绑定原AppState白名单流与setter，无配置副本；所有路径完整可选，添加trim/去重、删除精确路径。无hooks只说明，不将设备本地路径冒充远端服务器白名单，也不显示假编辑操作。
- 遥控拆为Route、只读RemoteSettingsUiState、RemoteSettingsActions及纯Content；状态/地址/密码/隧道顺序清楚，无彩色托盘/重卡片。服务缺LAN地址不再用127.0.0.1假冒LAN，实际端口回退可见；隧道运行但无URL时说明真实状态并保留停止动作。
- SettingsCopyableValue成为系统信息与遥控地址同一实际View，完整值可选/换行，复制精确原值；密码和Token共享SettingsSecretField，显隐有可访问标签且无保存副作用。共同MederiMiniSwitch补Role.Switch、checked/disabled/键盘与独立30dp桌面/44dp其它平台热区，视觉仍28×16，沿用MotionPolicy。
- 密码草稿保持原随开关提交、关闭再开启生效；未编辑的初始化偏好可回填，已编辑不被上游回流覆盖。Token/domain仍直接经原setter即时保存，保留原值/空白处理，不新增自动重启。启动/失败/未安装/不支持均由已有状态驱动；错误、重试和加载复用共同组件。页面隐藏/切分类没有停止动作。
- 未改AppState服务/隧道生命周期、Core安全规则、密码门、AiCore/Server API、数据库或依赖。旧私有SandboxStatusCard/SandboxWhitelistCard/RemoteControlCard/TunnelInputFields删除，Provider/设置通用输入保持共享。浏览器依赖本线未碰。

## 验证证据

最终22个suite共152项测试（既有135＋本批17），0失败/错误/跳过；wasm/JVM编译通过。SandboxSettingsDeliveryTest 7项：空白/重复路径禁用、trim及精确删除、无hooks不伪造远端路径/操作、加载/失败真实重试、null/不可用/防重入、280dp/200%字体长路径动作可达、实际Route经AppState写穿fake hooks并持久化InMemoryPreferences。

RemoteSettingsDeliveryTest 10项：密码草稿/显隐不保存且随原开关发完整原值；Token/domain受控字段真实回流/原值与隧道重试；真实回退端口与两个完整地址独立复制；缺地址无假值且隧道仍可停；启动中无重复启动/无Token编辑、未安装共同报错；不支持无假开关、服务失败真实重试；280dp/200%字体停止可达；Route隐藏/重开不启动/停止宿主；共同开关disabled/checked/Enter键盘与外部状态唯一；实际设置壳/纯Content深浅预览。

首轮Token/domain测试的fake回调未将值反馈给受控字段，导致IME恢复旧值；按实际AppState即时回流方式修正fixture后通过，未通过删除断言掩盖原值问题。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/settings-host-controls-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*Workspace*DeliveryTest' --tests '*WorkspaceMotionPolicyTest' --tests '*SandboxSettingsDeliveryTest' --tests '*RemoteSettingsDeliveryTest' --tests '*AgentSettings*Test' --tests '*SettingsControlsDeliveryTest' --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

已目视检查 `temp/settings-host-controls-delivery/remote-dark.png`、`remote-light.png`、`sandbox-dark.png`、`sandbox-light.png`，为实际SettingsWorkspaceContent+纯Content配fixture，不是完整APP截图。中英XML合法/无重复键、git diff --check通过。全部hooks/Store写入为fake或内存偏好，未启停真实网络服务/隧道、未改真实白名单。

## 当前交接

S5代码/组件交付完成。当前没有idea-mcp工具，完整APP需先启动/配置idea-mcp才可运行；未用bash启动APP/server。真实宿主服务/隧道/OS沙盒端到端、原生焦点与三平台布局仍待验。下一步是剩余工作台MVVM边界复核、S6一致性/键盘清理与最终实机验收；先按现有代码确认问题，不重复其他AI已完成工作。浏览器继续由另一AI删除，不回到本线。
