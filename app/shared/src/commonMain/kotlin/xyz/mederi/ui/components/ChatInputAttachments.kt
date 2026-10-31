package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.File
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.input_image_n
import mederi.app.shared.generated.resources.input_image_unsupported
import mederi.app.shared.generated.resources.input_pending_image_thumbnail
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.input_remove_image
import mederi.app.shared.generated.resources.input_remove_text
import mederi.app.shared.generated.resources.input_text_n
import mederi.app.shared.generated.resources.input_text_meta
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.InkImage
import xyz.mederi.core.contract.models.ImageAttachment
import xyz.mederi.core.contract.models.PastedTextAttachment
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.MederiColors

/**
 * 输入框顶部附件缩略图/卡片区（对标设计图，置于 TextField 正上方）。
 * - 水平滚动展示待发送图片缩略图与粘贴文本卡片；
 * - 每项右上角浮动微型删除按钮（经 [onRemoveImage]/[onRemovePastedText] 回调移除）；
 * - 点击缩略图/卡片经 [onOpenImage]/[onOpenPastedText] 在外部扩展查看器中打开；
 * - 末尾附分隔线与图片门禁内联提示（挂了图片但当前模型不支持时）。
 */
@Composable
internal fun ChatInputAttachments(
    pendingImages: List<ImageAttachment>,
    pendingPastedTexts: List<PastedTextAttachment>,
    modelSupportsImages: Boolean,
    colors: MederiColors,
    onRemoveImage: (String) -> Unit,
    onRemovePastedText: (String) -> Unit,
    onOpenImage: (String, ImageAttachment) -> Unit,
    onOpenPastedText: (String, PastedTextAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            pendingImages.forEachIndexed { i, img ->
                val imageTitle = stringResource(Res.string.input_image_n, i + 1)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceInput)
                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                        .clickable {
                            onOpenImage(imageTitle, img)
                        }
                ) {
                    InkImage(
                        model = img.base64DataUrl,
                        contentDescription = stringResource(Res.string.input_pending_image_thumbnail),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 右上角浮动微型删除按钮
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(WorkspaceStyle.IconTarget)
                            .clip(CircleShape)
                            .background(colors.surfaceCard.copy(alpha = 0.94f))
                            .clickable { onRemoveImage(img.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(Res.string.input_remove_image),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(WorkspaceStyle.IconSize)
                        )
                    }
                }
            }

            pendingPastedTexts.forEach { item ->
                val pastedTitle = stringResource(Res.string.input_pasted_text_n, item.index)
                Box(
                    modifier = Modifier
                        .height(56.dp)
                        .widthIn(min = 150.dp, max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceHover)
                        .inputControlSurface(onClick = {
                            onOpenPastedText(pastedTitle, item)
                        })
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = WorkspaceStyle.IconTarget),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.File,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(WorkspaceStyle.IconSize)
                            )
                            Text(
                                text = stringResource(Res.string.input_text_n, item.index),
                                color = colors.textPrimary,
                                fontSize = WorkspaceStyle.Caption.fontSize,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = stringResource(Res.string.input_text_meta, item.lineCount, item.charCount),
                            color = colors.textMuted,
                            fontSize = WorkspaceStyle.Caption.fontSize,
                            maxLines = 1
                        )
                    }

                    // 右上角浮动微型删除按钮
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(WorkspaceStyle.IconTarget)
                            .clip(CircleShape)
                            .background(colors.surfaceCard.copy(alpha = 0.94f))
                            .clickable { onRemovePastedText(item.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(Res.string.input_remove_text),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(WorkspaceStyle.IconSize)
                        )
                    }
                }
            }
        }

        // 图片门禁内联提示：挂了图片但当前模型不支持（如切换模型后），发送会被拦截
        if (pendingImages.isNotEmpty() && !modelSupportsImages) {
            Text(
                text = stringResource(Res.string.input_image_unsupported),
                color = colors.accentWarning,
                fontSize = WorkspaceStyle.Caption.fontSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}
