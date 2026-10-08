package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import xyz.mederi.util.rememberClipboardCopy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.atoms.MederiRunningPulseBadge
import xyz.mederi.util.DocumentExporter
import xyz.mederi.util.ExportStatus

/**
 * 扩展面板图片查看器内容
 */
@Composable
internal fun ImageViewerTabContent(
    title: String,
    imageUrl: String,
    colors: MederiColors
) {
    var isOriginalScale by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 工具条：标题、比例适应/原始大小切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(colors.surfaceCard)
                .border(1.dp, colors.surfaceCardBorder)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = FeatherIcons.Image,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceWorkspace)
                    .clickable { isOriginalScale = !isOriginalScale }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isOriginalScale) stringResource(Res.string.dock_fit_window) else stringResource(Res.string.dock_original_ratio),
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 大图居中展示画板（暗色背景衬托细节）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceOverlay.copy(alpha = 0.9f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            InkImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = if (isOriginalScale) ContentScale.None else ContentScale.Fit,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isOriginalScale) {
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
            )
        }
    }
}

/**
 * 扩展面板大文本阅读器内容 (使用 inkcompose.MarkdownView 进行富文本排版)
 */
@Composable
internal fun TextReaderTabContent(
    title: String,
    content: String,
    lineCount: Int,
    charCount: Int,
    isStreaming: Boolean = false,
    colors: MederiColors
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
            kotlinx.coroutines.delay(2000)
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
        // 顶部信息条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(colors.surfaceCard)
                .border(1.dp, colors.surfaceCardBorder)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = colors.accentSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (charCount > 0 || lineCount > 0) {
                    Text(
                        text = stringResource(Res.string.attachment_meta, lineCount, charCount),
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
                if (isStreaming) {
                    // 流式生成中微标：收敛为 MederiRunningPulseBadge（Pill 底 + accent 描边 + 脉冲点）
                    MederiRunningPulseBadge(text = stringResource(Res.string.dock_generating))
                }
            }

            // 操作按钮区：复制全文、导出 HTML、导出 PDF
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (exportError != null) {
                    Text(
                        text = exportError ?: "",
                        color = colors.accentDanger,
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                }

                // 复制全文按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (copied) colors.accentSuccess.copy(alpha = 0.15f) else colors.surfaceWorkspace)
                        .clickable {
                            copyToClipboard(content)
                            copied = true
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = null,
                        tint = if (copied) colors.accentSuccess else colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (copied) stringResource(Res.string.copy_done) else stringResource(Res.string.dock_copy_full),
                        color = if (copied) colors.accentSuccess else colors.textPrimary,
                        fontSize = 11.sp
                    )
                }

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
        }

        // 正文阅读器区域：使用 inkcompose.MarkdownView 统一高质量渲染，支持流式渲染状态
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceWorkspace)
                .padding(14.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            MarkdownView(
                content = content,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = ChatLayout.contentMaxWidth)
                    .fillMaxWidth(),
                enableScrollOverride = true,
                isStreaming = isStreaming,
                markdownTheme = rememberMederiMarkdownTheme(style = RenderStyle.Github)
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
private data class ExportOutcome(val status: ExportStatus, val error: String? = null)

/**
 * 一次性导出按钮原子：内部持有 [ExportRunState] 状态机。
 *
 * - I/O 全链路在 util/DocumentExporter.kt，本组件只做状态与视觉；
 * - Done 展示 2.5s 后自动回 Idle（对应旧版导出成功态 + LaunchedEffect 复位语义）；
 * - Exporting 期间自身禁用；[enabled] 由父级控制互斥（任一导出进行中另一按钮禁用）；
 * - WRITE_FAILED / EXPORT_FAILED 时把错误文案经 [onError] 上抛给父级 exportError。
 */
@Composable
private fun ExportActionButton(
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when (runState) {
                    ExportRunState.Done -> colors.accentSuccess.copy(alpha = 0.15f)
                    ExportRunState.Exporting -> colors.accentPrimary.copy(alpha = 0.15f)
                    ExportRunState.Idle -> colors.surfaceWorkspace
                }
            )
            .clickable(enabled = enabled && runState != ExportRunState.Exporting) {
                coroutineScope.launch {
                    runState = ExportRunState.Exporting
                    val r = onExport()
                    runState = when (r.status) {
                        ExportStatus.EXPORTED -> ExportRunState.Done
                        ExportStatus.CANCELLED -> ExportRunState.Idle // 用户取消，无提示
                        ExportStatus.WRITE_FAILED, ExportStatus.EXPORT_FAILED -> {
                            r.error?.let(onError)
                            ExportRunState.Idle
                        }
                    }
                }
            }
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = if (runState == ExportRunState.Done) FeatherIcons.Check else icon,
            contentDescription = null,
            tint = when (runState) {
                ExportRunState.Done -> colors.accentSuccess
                ExportRunState.Exporting -> colors.accentPrimary
                ExportRunState.Idle -> colors.textMuted
            },
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = when (runState) {
                ExportRunState.Exporting -> exportingLabel
                ExportRunState.Done -> doneLabel
                ExportRunState.Idle -> idleLabel
            },
            color = when (runState) {
                ExportRunState.Done -> colors.accentSuccess
                ExportRunState.Exporting -> colors.accentPrimary
                ExportRunState.Idle -> colors.textPrimary
            },
            fontSize = 11.sp
        )
    }
}