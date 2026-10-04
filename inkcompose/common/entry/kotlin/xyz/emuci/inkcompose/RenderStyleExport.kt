package xyz.emuci.inkcompose

import androidx.compose.runtime.ProvidableCompositionLocal
import xyz.emuci.markdown.renderer.RenderStyle as InternalRenderStyle
import xyz.emuci.markdown.renderer.MarkdownColors as InternalMarkdownColors
import xyz.emuci.markdown.renderer.MarkdownConfig as InternalMarkdownConfig
import xyz.emuci.markdown.renderer.LocalRenderStyle as InternalLocalRenderStyle
import xyz.emuci.markdown.renderer.LocalMarkdownColors as InternalLocalMarkdownColors
import xyz.emuci.markdown.renderer.LocalMarkdownConfig as InternalLocalMarkdownConfig

/** 排版版式配置（纯几何与结构规则，提供 [RenderStyle.Chat] 与 [RenderStyle.Github] 两套预设） */
typealias RenderStyle = InternalRenderStyle

/** 色彩皮肤配置（纯颜色系统，解耦排版版式，提供 [MarkdownColors.light]、[MarkdownColors.dark] 与 [MarkdownColors.auto]） */
typealias MarkdownColors = InternalMarkdownColors

/** Markdown 渲染与解析配置（方言、表情、标题编号、高亮开关、流式合并阈值等） */
typealias MarkdownConfig = InternalMarkdownConfig

val LocalRenderStyle: ProvidableCompositionLocal<RenderStyle> = InternalLocalRenderStyle
val LocalMarkdownColors: ProvidableCompositionLocal<MarkdownColors> = InternalLocalMarkdownColors
val LocalMarkdownConfig: ProvidableCompositionLocal<MarkdownConfig> = InternalLocalMarkdownConfig

