# 设置S2b：供应商详情、模型与表单

> 2026-10-30，用户要求继续。状态：代码与组件验证完成，完整APP实机待验。保留Core/VM原配置和元数据权限，只迁移表现与纯Content边界。

## 分工与验证契约

1. 详情/模型：去凭据大外框和双份宽窄工具栏；Header、凭据、动作、搜索、筛选单一组合，模型列表按ID保留；长名称/端点、大字体不挤操作。同步空/失败/不支持真实表达，错误继续共享ErrorBoard，成功沿原4秒。
   验证：实际ProviderDetailContent/ModelItemRow交互，查询/筛选/批量/刷新/自动设置/新增/逐模型三动作回调独立；同步禁用、内置不开放原来禁止的编辑/删除；窄屏与字体缩放动作可达。
2. 共享字段/表单：ProviderSettingsAtoms/ProviderTokens、Key/Model Dialog、自定义表单统一已有WorkspaceStyle/Action/Dialog/Menu；特殊业务字段仍在领域组装层。可选enabled字段保持兼容，去第二套字号/按钮。
   验证：字段原值/掩码、协议选择、空白禁用、Key管理与默认Key/精确删除、模型FETCHED/MANUAL权限及图片覆盖原语义；共同确认复用、长表单滚动。
3. 维护与收口：新增纯Content与资源中英同步，更新架构/流程和canonical标准；wasm编译、既有S2a/S1与前六批回归，实际深浅组件预览。不运行真实创建/删除/网络供应商操作。

独立文件分工，主代理统一构建/资源/文档；无APP工具时明确实机未验。所有变更保留MVVM及状态唯一来源，不改变模型参数或Core行为。


## 已确认的实现取舍

- 详情整区采用同一惰性阅读滚动，头部、凭据、工具栏与模型一起可达；小窗口/大字体不把工具栏固定成占满屏幕的不可滚区域。列表视图状态按provider.id归属，切换供应商从其身份开始；同一供应商的模型更新不重新创建滚动状态。静态/模型item key使用不同前缀。
- Root只装配领域弹窗与VM回调，新增ProviderDetailContent/ProviderModelToolbar/ProviderDetailActions纯表现边界。弹窗开关按provider.id复位，编辑模型移除即清理，不用陈旧model兜底。
- 同步失败复用ErrorBoard：新增可选onDismiss与continueLabel；无真实dismiss不显示关闭，无诊断不显示详情；刷新回调真实可用，不自绘第二种报错壳。
- 源码复核发现FETCHED编辑表单原本开放受保护字段，与元数据文档/后端门禁不一致；本批按既有不变量置只读，图片仍走VM已有setImageOverride，MANUAL沿原updateModelConfig。没有改变Core权限。
- 源码复核发现凭据编辑原本把ProviderItemUiState.apiKey（DTO掩码）作为输入值提交。改为只有用户新输入才更新Key，留空通过原VM映射成null保留已有Key；保留名称/端点/推理JSON的原值。新增提示明确说明，避免掩码被回写。


目视预览后的收口：将FETCHED的名称/上下文/输出上限改成共同ModelInfoRow可选择只读数据，而不是三个灰色不可操作输入框；图片仍可改，受保护推理控件不开放。MANUAL继续同一配置Content里的编辑字段。补头部名称/删除中心线断言，避免再次出现计数/按钮式顶对齐差异。


## 最终验证与交接

最终14个suite共103项测试（既有88＋本批15：ProviderDetailsDeliveryTest 8、ProviderFormsDeliveryTest 7），0失败/错误/跳过，wasm编译通过。覆盖精确工具栏/批量/查询/五过滤回调、逐模型三动作与隐藏状态可读、内置Provider权限、同步中刷新禁用/失败统一错误与真实恢复、280dp/200%长名称操作可达、切供应商不沿用旧模型阅读偏移；表单覆盖FETCHED不可编辑元数据且图片覆盖保留原值、MANUAL数字/trim/档位排序、空模型ID禁用、Key旋转哨兵/同别名精确ID、创建原值/忙态、凭据留空Key不提交掩码/显式新Key与原JSON空白保持。

已检查实际组件fixture深浅预览：`temp/provider-details-delivery/provider-details-dark.png`/`provider-details-light.png`及`provider-form-dark.png`/`provider-form-light.png`。为实际ProviderDetailContent/ModelConfigurationContent，不是另外画的模拟页面；数据为fixture，不代表真实用户配置，也不是完整APP截图。头部删除与名称几何中心断言通过；只读信息去掉灰色输入框后重新验收。资源中英XML有效且无重复键，git diff --check通过。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/provider-details-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*ProviderDetailsDeliveryTest' --tests '*ProviderFormsDeliveryTest' --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*Workspace*DeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'
```

仅去掉数学等价的冗余非空判断后追加wasm/JVM编译检查，未引入行为变化。无新依赖/Core/数据库/AiCore契约变化。当前没有idea-mcp工具，完整APP仍需先启动或配置idea-mcp，未用bash启动APP/server；三平台原生窗口/完整焦点与业务端到端设置尚未实机验收。

下一批S3 Agents设置：沿用本批字段、单选、表单、错误与确认，配置/角色/模型/推理/Key数据保持各自真理源；P0遗漏管理弹窗、MotionPolicy与整体MVVM/三平台收口保留。供应商S2a/S2b代码/组件交付不等于整个设置/主计划完成。
