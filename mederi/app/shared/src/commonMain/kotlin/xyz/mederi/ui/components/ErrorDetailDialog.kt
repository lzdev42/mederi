package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import xyz.mederi.core.ui.buildBugReportMarkdown
import xyz.mederi.core.ui.cleanErrorSummary
import xyz.mederi.core.ui.extractErrorCategory
import xyz.mederi.core.ui.extractErrorSuggestion
import xyz.mederi.theme.LocalMederiColors
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.error_report_app_version
import mederi.app.shared.generated.resources.error_report_client_platform
import mederi.app.shared.generated.resources.error_report_copy_done
import mederi.app.shared.generated.resources.error_report_copy_logs
import mederi.app.shared.generated.resources.error_report_copied_github
import mederi.app.shared.generated.resources.error_report_details_summary
import mederi.app.shared.generated.resources.error_report_env_title
import mederi.app.shared.generated.resources.error_report_fallback_summary
import mederi.app.shared.generated.resources.error_report_footer
import mederi.app.shared.generated.resources.error_report_logs_title
import mederi.app.shared.generated.resources.error_report_no_logs
import mederi.app.shared.generated.resources.error_report_no_summary
import mederi.app.shared.generated.resources.error_report_submit_bug
import mederi.app.shared.generated.resources.error_report_summary_title
import mederi.app.shared.generated.resources.error_report_title
import org.jetbrains.compose.resources.stringResource

/**
 * 详细错误诊断报告对话框。
 *
 * 默认不展开大段堆栈，仅在用户点击错误简报时弹出：
 * 1. 结构化展示错误分类与简述
 * 2. 醒目展示恢复建议（Suggestion）
 * 3. 暗色等宽代码块完整展示全量诊断日志与堆栈（可滚动、可选择）
 * 4. 底部支持一键“复制日志”与预填好内容的“提交Bug”（跳转 GitHub Issue）
 */
@Composable
fun ErrorDetailDialog(
    errorSummary: String,
    errorDiagnostic: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    var copyHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyHint) {
        if (copyHint != null) {
            delay(2000)
            copyHint = null
        }
    }

    // 从 errorSummary 中提取分类 Badge，例如 "[API] KoogHttpClientException..."（解析下沉 core/ui/ErrorDetailFormatter.kt）
    val categoryBadge = remember(errorSummary) { extractErrorCategory(errorSummary) }
    val cleanSummary = remember(errorSummary, categoryBadge) { cleanErrorSummary(errorSummary, categoryBadge) }
    // copyHint 文案在组合上下文取值（Button onClick 不是 @Composable）
    val copyDoneHint = stringResource(Res.string.error_report_copy_done)
    val copiedGithubHint = stringResource(Res.string.error_report_copied_github)

    // 从诊断报告中提取 Suggestion 恢复建议（如有）——兼容 `Suggestion:`（异常路径）与 `建议：`（collectWarning 断流路径）
    val suggestion = remember(errorDiagnostic) { extractErrorSuggestion(errorDiagnostic) }

    // 格式化为 Markdown Bug Report 模板（用于剪贴板复制和 GitHub Issue 内容填充）
    val envTitle = stringResource(Res.string.error_report_env_title)
    val appVersionLabel = stringResource(Res.string.error_report_app_version)
    val platformLabel = stringResource(Res.string.error_report_client_platform)
    val summaryTitle = stringResource(Res.string.error_report_summary_title)
    val noSummaryText = stringResource(Res.string.error_report_no_summary)
    val logsTitle = stringResource(Res.string.error_report_logs_title)
    val detailsSummary = stringResource(Res.string.error_report_details_summary)
    val reportFooter = stringResource(Res.string.error_report_footer)
    // 模板拼装下沉 core/ui/ErrorDetailFormatter.kt（内部取 AppInfo.VERSION / getPlatform()）
    val bugReportMarkdown = remember(errorSummary, errorDiagnostic, envTitle, appVersionLabel, platformLabel, summaryTitle, noSummaryText, logsTitle, detailsSummary, reportFooter) {
        buildBugReportMarkdown(
            envTitle = envTitle,
            appVersionLabel = appVersionLabel,
            platformLabel = platformLabel,
            summaryTitle = summaryTitle,
            noSummaryText = noSummaryText,
            logsTitle = logsTitle,
            detailsSummary = detailsSummary,
            reportFooter = reportFooter,
            errorSummary = errorSummary,
            errorDiagnostic = errorDiagnostic,
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.surfaceCardBorder),
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.AlertCircle,
                            contentDescription = null,
                            tint = colors.accentDanger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(Res.string.error_report_title),
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.close),
                        tint = colors.textSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onDismiss() }
                    )
                }

                // 2. 错误分类与简述卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accentDanger.copy(alpha = 0.12f))
                        .border(1.dp, colors.accentDanger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (categoryBadge != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.accentDanger,
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Text(
                                text = categoryBadge,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cleanSummary.ifBlank { stringResource(Res.string.error_report_fallback_summary) },
                            color = colors.accentDanger,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 17.sp
                        )
                    }
                }

                // 3. 恢复建议（如果提取到了 Suggestion）
                if (!suggestion.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accentWarning.copy(alpha = 0.1f))
                            .border(1.dp, colors.accentWarning.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Info,
                            contentDescription = null,
                            tint = colors.accentWarning,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Text(
                            text = suggestion,
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                // 4. 详细诊断日志（等宽字体、滚动区）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.error_report_logs_title),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 280.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceSidebar)
                            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                text = errorDiagnostic.ifBlank { errorSummary.ifBlank { stringResource(Res.string.error_report_no_logs) } },
                                color = colors.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // 5. 底部操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态提示反馈（如“已复制日志”）
                    Box(modifier = Modifier.weight(1f)) {
                        if (copyHint != null) {
                            Text(
                                text = copyHint.orEmpty(),
                                color = colors.accentPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 复制日志按钮
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(bugReportMarkdown))
                                copyHint = copyDoneHint
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.divider)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Copy,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(Res.string.error_report_copy_logs),
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }

                        // 提交 Bug 按钮（自动预填好标题与内容模板并调起浏览器）
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(bugReportMarkdown))
                                copyHint = copiedGithubHint
                                val issueTitle = "[Bug]: ${cleanSummary.take(80)}".encodeURLParameter()
                                val issueBody = bugReportMarkdown.encodeURLParameter()
                                val githubIssueUrl = "https://github.com/lzdev42/mederi/issues/new?title=$issueTitle&body=$issueBody"
                                uriHandler.openUri(githubIssueUrl)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.ExternalLink,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(Res.string.error_report_submit_bug),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // 关闭按钮
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.close),
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
