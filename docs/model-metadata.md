# 模型元数据所有权与合并规则

> 本文档从 AGENTS.md 拆出，记录 AIModel 元数据所有权、ModelMerge 合并真理源、图片能力门禁链路。
> 只在改动 provider/model 同步逻辑、模型元数据字段时读。推理机制的核心思想与档位真理源仍在 AGENTS.md §7。

## AIModel 字段所有权与模型来源

**AIModel 每个字段必须声明所有权**；`ModelOrigin` 表示来源，不作为编辑门禁：

- **FETCHED 模型**：端点拉取及目录提供初始值，设置页允许用户补录或纠正名称、窗口、输出上限、图片/推理能力和推理档位；来源不构成编辑权限。
- **MANUAL 模型**：用户手动添加，与 FETCHED 共用完整编辑和 `updateUserModel` 保存通道。
- **用户覆盖层**：`supportsImagesOverride`（用户权威）——目录对长尾/私有模型可能缺数据或标错，显式图片设置压过目录，包含「自动设置」在内的合并均保留覆盖。
- **请求策略**：`optimizeForRequestCount` 是用户设置，默认关闭，普通刷新和显式「自动设置」均保留。它选择按请求次数优化的工具提示词，目录价格和模型名不参与判定。
- `updateUserModel` 对两种来源均接受现有可编辑字段，null 参数表示保留已有值；普通列表刷新不覆盖存量设置。
- **显式自动设置**按 `ModelMerge.mergeFetched` 合并目录：上下文/输出上限仅补空；推理能力、非空推理档位和价格可更新；名称不改，图片覆盖保留。此操作需用户显式触发，不等同普通刷新。
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
