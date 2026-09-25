package xyz.emuci.diagram

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.emuci.diagram.theme.DiagramTheme

/**
 * Mermaid 图表块的渲染接缝（expect/actual）。
 *
 * 各平台渲染策略（全部基于官方 mermaid.js，原生渲染管线已删除）：
 * - jvm：KBrowser JCEF 离屏 Worker——全局唯一后台 KBWebView 串行渲染为 2x PNG，
 *   经 [xyz.emuci.diagram.mermaid.MermaidDiskCache] 落盘缓存（自愈 + 原子写）；
 * - android / ios：KBrowser 桥接系统 WebView / WKWebView，内嵌页面从 CDN 加载 mermaid
 *   渲染为内联 SVG，高度回报给 Compose 自适应；
 * - wasmJs：同文档 DOM——mermaid.js（webApp 同源静态资源）离屏 Canvas 超采样为 PNG。
 *
 * 入参是 markdown 管线的原始事实：源码 + 类型提示 + 会话键；
 * 主题为 [DiagramTheme]，各平台映射为 mermaid themeVariables。
 */
@Composable
internal expect fun DiagramBlockView(
    source: String,
    theme: DiagramTheme,
    languageHint: String?,
    sessionKey: Any?,
    modifier: Modifier,
)
