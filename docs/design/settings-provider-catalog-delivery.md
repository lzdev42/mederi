# 设置S2a：已配置管理与添加目录

> 2026-10-29，用户批准供应商布局方案并要求修改。状态：代码与组件验证完成，完整APP实机待验。
> 基于provider-settings-layout-plan.md；本批解决规模/查找/添加，原模型详情完整迁移为后续S2b。

## 具体变更契约

- 管理只显示已配置项，固定添加与名称/端点查询；目录独立搜索+惰性列表、字母序，预设达到300项仍可查找与滚动。
- 添加进入同一区域目录→预设凭据/自定义流程；已配置内置回到精确ID，多匹配进入ID集合过滤而不随机选择。自定义同名不标成内置。
- ProviderPage统一Manage/Detail/Catalog/Custom/Preset(name)；窄屏Detail用于下钻，宽屏仍显示管理+详情；isCreatingCustom只从page派生，无并行布尔。
- providerQuery、presetQuery、模型searchQuery独立；过滤不改变选择，目录返回保留搜索/滚动；凭据草稿仅在对应View composition中，退出不持久化。
- 添加同步置saving后才launch，重复提交/保存中导航被阻止；失败保留步骤与Key，只有成功回Detail/选新ID并保留原自动模型同步。Core/契约创建能力不改。
- 错误统一ErrorBoard；扩展可选详情回调，无详情不渲染假动作，完整错误可阅读；详情旧错误条移除，成功反馈仍按原4秒，只清成功，不能顺带清新错误。
- 删除旧常驻BuiltinPresetsSection/AddBuiltinProviderDialog/自绘添加行与侧栏行，替换为共享ProviderChoiceRow/SearchField，宽窄共用，不保留两套新旧入口。

## 小任务与验收

1. 纯投影/VM导航：真实内置来源匹配、同名多个ID、大小写/空白、三种查询互不影响、返回保留选择、忙态不跳页。
2. 添加状态：fake AiCore挂起请求断言重复提交只一次，失败不离开凭据、重试成功才选新ID、Key trim、旧成功计时不清错误。
3. 实际Content：管理不渲染未配置预设；目录300项可滚动到最后/搜索/无结果/自定义入口；凭据密码/空白禁用、失败重组草稿保留；窄宽与大字体动作可达。
4. 中英资源、wasm编译、S1/前六批/过滤推理回归、深浅实际组件预览；APP仍必须idea-mcp，未具备则写未验。


## 用户截图反馈：管理头对齐（2026-10-29）

用户截图确认原FlowRow导致计数文字和按钮默认顶对齐，并且工具栏悬在整块详情区上方。更正布局契约：ProviderManagementHeader归左侧已配置列表，宽屏与搜索/列表共用列宽，右侧详情与该栏头同时从顶端开始；足宽Row垂直居中、计数左/添加右，不足宽（按实际中英文文字测量、字体缩放及共享按钮内距计算）明确改Column左对齐，不靠FlowRow随意换行。新增几何行为断言检查两控件中心、左右边界、纵向分支和详情不再下推。

另补查询变化回到首个结果，步骤返回若query未变则保持目录阅读位置；防止滚到第300项再搜索时停在结果末尾。


## 最终验证与交接

完整回归12个suite共88项（原75项＋本批13项：ProviderCatalogDeliveryTest 10、ProviderCatalogViewModelTest 3），0失败/错误/跳过；wasm编译通过。另定向复跑本批10项Compose测试生成最新工具栏预览，0失败/错误/跳过。真实测试覆盖300项末尾滚动/修改查询回首个匹配、步骤返回保留query与阅读位置、同名自定义不误认内置、多ID匹配不随机选、3种查询独立、VM挂起请求重复提交只调用一次、失败留在步骤/原地重试成功、忙态导航/编辑禁用、凭据草稿保留、统一无详情错误提示、窄屏下钻与返回，以及截图反馈的几何对齐。

已目视检查实际组件fixture预览：`temp/provider-catalog-delivery/provider-directory-dark.png`/`provider-directory-light.png`、`provider-credentials-dark.png`/`provider-credentials-light.png`、`provider-header-horizontal.png`/`provider-header-stacked.png`。目录使用300项测试名称与虚构配置，不是用户真实供应商；工具栏预览是实际ProviderManagementHeader（320dp常规与180dp/1.5倍字体），实际224dp列表列的对齐另由几何测试验证。截图不是完整APP。中英资源XML无重复键、git diff --check通过，无新依赖/数据库/契约变化。

当前无idea-mcp可用，读取现有Mederi窗口也未能解析应用，故不宣称真实APP/三平台/原生焦点集成已验；APP/server仍需启动或配置idea-mcp，未用bash启动。

```bash
MEDERI_UI_PREVIEW_DIR="$PWD/temp/provider-catalog-delivery" ./gradlew :app:shared:compileKotlinWasmJs :app:shared:jvmTest --tests '*ProviderCatalog*Test' --tests '*SettingsFirstDeliveryTest' --tests '*Workspace*DeliveryTest' --tests '*ProviderSettingsFreeFilterTest' --tests '*AppLanguageResourceTest' --tests '*ReasoningMenuTest'

MEDERI_UI_PREVIEW_DIR="$PWD/temp/provider-catalog-delivery" ./gradlew :app:shared:jvmTest --tests '*ProviderCatalogDeliveryTest'
```

下一批S2b：供应商详情/模型/Key和Model表单完整样式迁移，继续共用错误/确认/菜单和状态真理源。S2a只解决规模、选取、添加流程与本次对齐问题，不标整个供应商/设置改造完成。
