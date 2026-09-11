package xyz.emuci.inkcompose

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前渲染会话的唯一标识，用于在 Mermaid 等图表离屏渲染时为生成的缓存文件加上会话前缀，
 * 便于按会话追溯或在会话删除时同步清理关联的位图资源。
 */
val LocalSessionKey = compositionLocalOf<String?> { null }
