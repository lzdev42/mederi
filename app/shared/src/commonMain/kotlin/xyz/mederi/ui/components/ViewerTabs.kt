package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.attachment_meta
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.dock_copy_full
import mederi.app.shared.generated.resources.dock_export_error
import mederi.app.shared.generated.resources.dock_export_html
import mederi.app.shared.generated.resources.dock_export_pdf
import mederi.app.shared.generated.resources.dock_exported
import mederi.app.shared.generated.resources.dock_exporting
import mederi.app.shared.generated.resources.dock_fit_window
import mederi.app.shared.generated.resources.dock_generating
import mederi.app.shared.generated.resources.dock_html_export_failed
import mederi.app.shared.generated.resources.dock_original_ratio
import mederi.app.shared.generated.resources.dock_pdf_export_failed
import mederi.app.shared.generated.resources.pick_file_filter
import mederi.app.shared.generated.resources.pick_save_file_title
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.InkImage
import xyz.emuci.inkcompose.MarkdownView
import xyz.emuci.inkcompose.RenderStyle
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.syntax.theme.LocalCodeTheme
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.util.DocumentExporter
import xyz.mederi.util.ExportStatus
import xyz.mederi.util.rememberClipboardCopy

/** 图片和文本共享轻量工具栏；缩放与滚动仍由原图片查看器负责。 */
@Composable
internal fun ImageViewerTabContent(
    title: String,
    imageUrl: String,
    colors: MederiColors,
    showTitle: Boolean = true,
) {
    var isOriginalScale by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        ImageReaderToolbar(
            title = title.takeIf { showTitle },
            isOriginalScale = isOriginalScale,
            onToggleScale = { isOriginalScale = !isOriginalScale },
            colors = colors,
        )
        Box(
            modifier = Modifier.fillMaxSize().background(colors.surfaceWorkspace).padding(MederiSpacing.LG),
            contentAlignment = Alignment.Center,
        ) {
            InkImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = if (isOriginalScale) ContentScale.None else ContentScale.Fit,
                modifier = Modifier.clip(RoundedCornerShape(MederiRadius.Control))
                    .then(if (isOriginalScale) {
                        Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                    } else Modifier.fillMaxSize()),
            )
        }
    }
}

/** 阅读器正文保持完整，不为消除重复标题解析或修改Markdown。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextReaderTabContent(
    title: String,
    content: String,
    lineCount: Int,
    charCount: Int,
    isStreaming: Boolean = false,
    colors: MederiColors,
    showTitle: Boolean = true,
) {
    val copyToClipboard = rememberClipboardCopy()
    var copied by remember { mutableStateOf(false) }

    var exportError by remember { mutableStateOf<String?>(null) }
    // 任一导出进行中时禁用另一导出按钮（互斥导出语义，收敛自旧的 4 枚导出 Boolean）
    var exportingNow by remember { mutableStateOf(false) }
    // 导出错误文案在组合上下文取值（coroutineScope.launch / 回调不是 @Composable）
    val htmlSaveFailedMsg = stringResource(Res.string.dock_html_export_failed)
    val pdfExportFailedMsg = stringResource(Res.string.dock_pdf_export_failed)
    val exportErrorMsg = stringResource(Res.string.dock_export_error)

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(WorkspaceStyle.CopyFeedbackMillis)
            copied = false
        }
    }

    LaunchedEffect(exportError) {
        if (exportError != null) {
            kotlinx.coroutines.delay(3500)
            exportError = null
        }
    }

    val safeBaseName = remember(title) {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        cleaned.ifBlank { "document" }
    }

    val saveDialogTitle = stringResource(Res.string.pick_save_file_title)
    val htmlFilterLabel = stringResource(Res.string.pick_file_filter, "HTML", "html")
    val pdfFilterLabel = stringResource(Res.string.pick_file_filter, "PDF", "pdf")

    Column(modifier = Modifier.fillMaxSize()) {
        TextReaderToolbar(
            title = title.takeIf { showTitle },
            lineCount = lineCount,
            charCount = charCount,
            isStreaming = isStreaming,
            copied = copied,
            onCopy = { copyToClipboard(content); copied = true },
            colors = colors,
        ) {
            // 导出 HTML 按钮（I/O 全链路在 util/DocumentExporter.kt，状态机收敛在 ExportActionButton）
            ExportActionButton(
                icon = FeatherIcons.Code,
                idleLabel = stringResource(Res.string.dock_export_html),
                exportingLabel = stringResource(Res.string.dock_exporting),
                doneLabel = stringResource(Res.string.dock_exported),
                enabled = !exportingNow,
                colors = colors,
                onExport = {
                    exportingNow = true
                    try {
                        val result = DocumentExporter.exportDocumentToHtml(
                            title = title,
                            content = content,
                            baseName = safeBaseName,
                            saveDialogTitle = saveDialogTitle,
                            filterLabel = htmlFilterLabel,
                        )
                        ExportOutcome(
                            status = result.status,
                            error = when (result.status) {
                                ExportStatus.WRITE_FAILED -> htmlSaveFailedMsg
                                ExportStatus.EXPORT_FAILED -> result.error ?: exportErrorMsg
                                else -> null
                            },
                        )
                    } finally {
                        exportingNow = false
                    }
                },
                onError = { exportError = it },
            )

            // 导出 PDF 按钮（I/O 全链路在 util/DocumentExporter.kt，状态机收敛在 ExportActionButton）
            ExportActionButton(
                icon = FeatherIcons.Download,
                idleLabel = stringResource(Res.string.dock_export_pdf),
                exportingLabel = stringResource(Res.string.dock_exporting),
                doneLabel = stringResource(Res.string.dock_exported),
                enabled = !exportingNow,
                colors = colors,
                onExport = {
                    exportingNow = true
                    try {
                        val result = DocumentExporter.exportDocumentToPdf(
                            title = title,
                            content = content,
                            baseName = safeBaseName,
                            saveDialogTitle = saveDialogTitle,
                            filterLabel = pdfFilterLabel,
                        )
                        ExportOutcome(
                            status = result.status,
                            error = when (result.status) {
                                ExportStatus.WRITE_FAILED -> pdfExportFailedMsg
                                ExportStatus.EXPORT_FAILED -> result.error ?: exportErrorMsg
                                else -> null
                            },
                        )
                    } finally {
                        exportingNow = false
                    }
                },
                onError = { exportError = it },
            )
        }
        exportError?.let { error ->
            Text(error, style = WorkspaceStyle.Caption, color = colors.accentDanger,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS))
        }

        val style = RenderStyle.Document
        val markdownTheme = rememberMederiMarkdownTheme(style).let { theme ->
            remember(theme, colors) {
                theme.copy(
                    linkColor = colors.accentText,
                    blockQuoteBorderColor = colors.accentBorder,
                    codeBlockBackground = colors.surfaceHover,
                    tableHeaderBackground = colors.surfaceHover,
                )
            }
        }
        val syntaxTheme = LocalCodeTheme.current
        val documentCodeTheme = remember(syntaxTheme, markdownTheme.codeBlockBackground) {
            object : CodeTheme by syntaxTheme {
                override val background = markdownTheme.codeBlockBackground
            }
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(colors.surfaceWorkspace),
            contentAlignment = Alignment.TopCenter,
        ) {
            val inset = if (maxWidth < 480.dp) MederiSpacing.MD else MederiSpacing.XL
            MarkdownView(
                content = content,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = inset, vertical = MederiSpacing.LG)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(),
                enableScrollOverride = true,
                isStreaming = isStreaming,
                style = style,
                markdownTheme = markdownTheme,
                codeTheme = documentCodeTheme,
            )
        }
    }
}

// ---------- 一次性导出动作状态机（HTML/PDF 两个按钮共用） ----------

/** 一次性导出动作的窄状态：Idle → Exporting → Done(2.5s 后自动回 Idle)；失败/取消直接回 Idle。 */
private enum class ExportRunState { Idle, Exporting, Done }

/**
 * 导出结果：status + 失败文案。
 * 错误文案由 [ExportActionButton] 经 onError 上抛给父级 exportError（共享展示在按钮上方）。
 */
internal data class ExportOutcome(val status: ExportStatus, val error: String? = null)

/**
 * 一次性导出按钮原子：内部持有 [ExportRunState] 状态机。
 *
 * - I/O 全链路在 util/DocumentExporter.kt，本组件只做状态与视觉；
 * - Done 展示 2.5s 后自动回 Idle（对应旧版导出成功态 + LaunchedEffect 复位语义）；
 * - Exporting 期间自身禁用；[enabled] 由父级控制互斥（任一导出进行中另一按钮禁用）；
 * - WRITE_FAILED / EXPORT_FAILED 时把错误文案经 [onError] 上抛给父级 exportError。
 */
@Composable
internal fun ExportActionButton(
    icon: ImageVector,
    idleLabel: String,
    exportingLabel: String,
    doneLabel: String,
    enabled: Boolean,
    colors: MederiColors,
    onExport: suspend () -> ExportOutcome,
    onError: (String) -> Unit,
) {
    var runState by remember { mutableStateOf(ExportRunState.Idle) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(runState) {
        if (runState == ExportRunState.Done) {
            kotlinx.coroutines.delay(2500)
            runState = ExportRunState.Idle
        }
    }

    ReaderActionButton(
        icon = if (runState == ExportRunState.Done) FeatherIcons.Check else icon,
        label = when (runState) {
            ExportRunState.Exporting -> exportingLabel
            ExportRunState.Done -> doneLabel
            ExportRunState.Idle -> idleLabel
        },
        enabled = enabled && runState != ExportRunState.Exporting,
        colors = colors,
        tint = when (runState) {
            ExportRunState.Done -> colors.accentSuccess
            ExportRunState.Exporting -> colors.accentText
            ExportRunState.Idle -> colors.textSecondary
        },
        onClick = {
            coroutineScope.launch {
                runState = ExportRunState.Exporting
                val r = onExport()
                runState = when (r.status) {
                    ExportStatus.EXPORTED -> ExportRunState.Done
                    ExportStatus.CANCELLED -> ExportRunState.Idle
                    ExportStatus.WRITE_FAILED, ExportStatus.EXPORT_FAILED -> {
                        r.error?.let(onError)
                        ExportRunState.Idle
                    }
                }
            }
        },
    )
}

/** 一个无框工具栏容纳身份/次级元数据/操作，窄面板自动换行，不复制tab上的标题。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderToolbar(
    title: String?,
    icon: ImageVector,
    colors: MederiColors,
    metadata: @Composable FlowRowScope.() -> Unit = {},
    actions: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            Row(
                modifier = Modifier.heightIn(min = WorkspaceStyle.ControlHeight).widthIn(max = 340.dp)
                    .padding(horizontal = MederiSpacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            ) {
                Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(WorkspaceStyle.IconSize))
                Text(title, style = WorkspaceStyle.Title, color = colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        metadata()
        actions()
    }
}

/** 阅读器图标文字动作，共用工作台最小热区与hover/focus反馈，不承载导出状态。 */
@Composable
internal fun ReaderActionButton(
    icon: ImageVector,
    label: String,
    colors: MederiColors,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = colors.textSecondary,
) {
    WorkspaceActionButton(text = label, onClick = onClick, enabled = enabled,
        icon = icon, contentColor = tint, colors = colors)
}


/** 图片比例动作的纯表现入口，状态由图片查看器传入。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageReaderToolbar(
    title: String?,
    isOriginalScale: Boolean,
    onToggleScale: () -> Unit,
    colors: MederiColors,
) {
    ReaderToolbar(title = title, icon = FeatherIcons.Image, colors = colors) {
        ReaderActionButton(
            icon = if (isOriginalScale) FeatherIcons.Minimize2 else FeatherIcons.Maximize2,
            label = stringResource(if (isOriginalScale) Res.string.dock_fit_window else Res.string.dock_original_ratio),
            colors = colors,
            onClick = onToggleScale,
        )
    }
}

/** 文本工具栏只渲染元数据与动作；复制和导出由原调用点编排。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextReaderToolbar(
    title: String?,
    lineCount: Int,
    charCount: Int,
    isStreaming: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    colors: MederiColors,
    exportActions: @Composable FlowRowScope.() -> Unit = {},
) {
    ReaderToolbar(
        title = title,
        icon = FeatherIcons.FileText,
        colors = colors,
        metadata = {
            if (charCount > 0 || lineCount > 0) {
                Text(stringResource(Res.string.attachment_meta, lineCount, charCount),
                    style = WorkspaceStyle.Caption, color = colors.textMuted)
            }
            if (isStreaming) {
                Text(stringResource(Res.string.dock_generating),
                    style = WorkspaceStyle.Caption, color = colors.accentText)
            }
        },
    ) {
        ReaderActionButton(
            icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            label = stringResource(if (copied) Res.string.copy_done else Res.string.dock_copy_full),
            colors = colors,
            tint = if (copied) colors.accentSuccess else colors.textSecondary,
            onClick = onCopy,
        )
        exportActions()
    }
}
