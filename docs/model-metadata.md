# 模型元数据所有权与合并规则

> 本文档从 AGENTS.md 拆出，记录 AIModel 元数据所有权、ModelMerge 合并真理源、图片能力门禁链路。
> 只在改动 provider/model 同步逻辑、模型元数据字段时读。推理机制的核心思想与档位真理源仍在 AGENTS.md §7。

## AIModel 字段所有权（`ModelOrigin` 标记模型级权威）

**AIModel 每个字段必须声明所有权**（`ModelOrigin` 标记模型级权威）：

- **FETCHED 模型**（端点拉取 + models.dev 目录合并）：元数据权威 = 端点/目录，
  唯一写入路径 = `ModelMerge.mergeFetched`（core/provider/ModelMerge.kt，表驱动、穷举测试锁定），
  入口只有 `ProviderManager.applyRemoteMetadata`（refresh 传 endpoint、启动回填传 null）
- **MANUAL 模型**（用户手动添加）：用户权威，同步（refresh/回填）**永不触碰**，UI 元数据可编辑
- **用户覆盖层**：`supportsImagesOverride`（用户权威，同步永不触碰）——目录对长尾/私有模型经常
  缺数据或标错（真实案例：models.dev agnes 条目只收录 3/13 个模型），用户在 UI 显式设置的
  图片能力必须压过目录且在 refresh/回填中存活（真实事故：用户 UI 设的支持图片被目录 false 洗掉且只读改不回）
- 用户可写字段只有 `isEnabled` 和图片覆盖（setModelEnabled / updateUserModel / setImageOverride）；
  `updateUserModel` 对 FETCHED 模型传入其他元数据修改会抛错
- 图片能力消费端门禁：附件按钮显隐 + `tryAttachImage` 粘贴附件（剔除放行：模型不支持图片时允许附加并给一次性轻提示，
  不掉"不支持图片输入"错误）+ `guardImageSupport` 发送守卫（send 与 rollbackMessage 共用，唯一实现）。
  **剔除放行语义**（2026-09）：模型不支持图片时不拒绝附加/发送，设置一次性轻提示 `imageStrippedNotice`（图片已剔除、
  历史保留）；core 侧 `HistoryStoreChatHistoryProvider(includeImages = model.supportsImages)` 在 AI 视图层剔除
  用户 Image part（历史存储仍带图，可切回支持图片的模型回退重发）。禁止绕过守卫直接组装带图 ChatPromptInput
- **能力必须传导到执行引擎**：`KoogModelBuilder.buildCapabilities` 按 `supportsImages` /
  `supportsReasoning` 添加 `LLMCapability.Image` / `Thinking`——缺 Image 时 Koog 会在发送时
  直接拒绝图片消息（历史事故：设置链路全通、唯独引擎能力缺失，用户怎么设都报不支持图片）
- UI 一律叫 **Image**（历史遗留叫 Vision，已废弃）；字段名统一 `supportsImages`

## 硬性规则

- **系统永不自动写存量模型的元数据**（2026-09 机制翻转，用户裁定）：启动回填已移除、
  refresh 只同步列表（新增模型带元数据落库，已有模型一律跳过）；目录数据进入存量模型的
  **唯一通道 = 用户显式点「自动设置」**（`autoSetupProviderModels` / `ProviderApi.autoSetupModels`）
- 两处同步（refresh 与自动设置）只能喂不同输入（endpoint 有无），**禁止各自手写字段合并逻辑**——
  合并规则改 `ModelMerge` 一处 + `ModelMergeTest` 穷举组合（历史事故：回填/刷新策略打架、
  用户开关被覆盖、跨重启闪烁）
- 新增 AIModel 元数据字段三件套：① 在 ModelMerge 所有权表登记 ② ModelMergeTest 补全组合断言
  ③ 契约四处同步（AiCore.kt → MederiAiCore / ServerAiCore → Server 路由，Mock 同步）
