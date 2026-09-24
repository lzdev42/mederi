package xyz.emuci.inkcompose

import androidx.compose.runtime.ProvidableCompositionLocal
import xyz.emuci.markdown.renderer.RenderStyle as InternalRenderStyle
import xyz.emuci.markdown.renderer.MarkdownColors as InternalMarkdownColors
import xyz.emuci.markdown.renderer.LocalRenderStyle as InternalLocalRenderStyle
import xyz.emuci.markdown.renderer.LocalMarkdownColors as InternalLocalMarkdownColors

/** 排版版式配置（纯几何与结构规则，提供 [RenderStyle.Chat] 与 [RenderStyle.Github] 两套预设） */
typealias RenderStyle = InternalRenderStyle

/** 色彩皮肤配置（纯颜色系统，解耦排版版式，提供 [MarkdownColors.light]、[MarkdownColors.dark] 与 [MarkdownColors.auto]） */
typealias MarkdownColors = InternalMarkdownColors

val LocalRenderStyle: ProvidableCompositionLocal<RenderStyle> = InternalLocalRenderStyle
val LocalMarkdownColors: ProvidableCompositionLocal<MarkdownColors> = InternalLocalMarkdownColors

