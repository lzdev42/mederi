package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.question_cancel
import mederi.app.shared.generated.resources.question_custom_placeholder
import mederi.app.shared.generated.resources.question_free_input_tag
import mederi.app.shared.generated.resources.question_input_placeholder
import mederi.app.shared.generated.resources.question_multi_tag
import mederi.app.shared.generated.resources.question_next
import mederi.app.shared.generated.resources.question_prev
import mederi.app.shared.generated.resources.question_single_tag
import mederi.app.shared.generated.resources.question_submit
import mederi.app.shared.generated.resources.question_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.ui.components.atoms.MederiSurfaceButton

/**
 * 3. 选择题/问询交互卡片 (QuestionCard)
 *
 * 无状态组件：当前选中答案经 [selectedAnswers] 由调用方传入（唯一真理源 =
 * WorkspaceViewModel.questionAnswers），点击经 [onAnswer] 单向写回，卡片内不持有副本。
 * 支持三种模式：
 * 1. options 为空 -> 自由文本输入框（OutlinedTextField）
 * 2. options 非空且 multiSelect = false -> 单选列表（RadioButton）
 * 3. options 非空且 multiSelect = true -> 多选列表（Checkbox）
 * 额外支持 allowCustom = true 时的自定义输入框。
 */
@Composable
fun QuestionCard(
    question: QuestionRequest?,
    currentIndex: Int,
    selectedAnswers: List<String>,
    onAnswer: (List<String>) -> Unit,
    onNextPage: () -> Unit = {},
    onPrevPage: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (question == null) return
    val colors = LocalMederiColors.current
    val qList = question.questions
    val qInfo = qList.getOrNull(currentIndex) ?: qList.firstOrNull() ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.accentPrimary, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val typeTag = when {
                qInfo.options.isEmpty() -> stringResource(Res.string.question_free_input_tag)
                qInfo.multiSelect -> stringResource(Res.string.question_multi_tag)
                else -> stringResource(Res.string.question_single_tag)
            }
            Text(
                text = stringResource(Res.string.question_title, currentIndex + 1, qList.size) + " · $typeTag",
                color = colors.accentPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = qInfo.prompt,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        // Options or Free-text input
        if (qInfo.options.isEmpty()) {
            val freeText = selectedAnswers.firstOrNull() ?: ""
            OutlinedTextField(
                value = freeText,
                onValueChange = { onAnswer(if (it.isBlank()) emptyList() else listOf(it)) },
                placeholder = { Text(stringResource(Res.string.question_input_placeholder), fontSize = 11.5.sp, color = colors.textMuted) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 11.5.sp, color = colors.textPrimary),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceWorkspace,
                    unfocusedContainerColor = colors.surfaceWorkspace,
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.divider,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.accentPrimary
                )
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                qInfo.options.forEach { opt ->
                    val isSelected = selectedAnswers.contains(opt)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.15f) else colors.surfaceWorkspace)
                            .border(1.dp, if (isSelected) colors.accentPrimary else colors.divider, RoundedCornerShape(6.dp))
                            .clickable {
                                if (qInfo.multiSelect) {
                                    onAnswer(if (isSelected) selectedAnswers - opt else selectedAnswers + opt)
                                } else {
                                    val nonOptions = if (qInfo.allowCustom) selectedAnswers.filter { it !in qInfo.options } else emptyList()
                                    onAnswer(listOf(opt) + nonOptions)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (qInfo.multiSelect) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.accentPrimary,
                                    checkmarkColor = colors.onAccentPrimary,
                                    uncheckedColor = colors.textMuted
                                )
                            )
                        } else {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = colors.accentPrimary)
                            )
                        }
                        Text(text = opt, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (qInfo.allowCustom) {
                    val customValue = selectedAnswers.firstOrNull { it !in qInfo.options } ?: ""
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { newCustom ->
                            val currentOptions = selectedAnswers.filter { it in qInfo.options }
                            val next = if (newCustom.isBlank()) currentOptions else currentOptions + newCustom
                            onAnswer(next)
                        },
                        placeholder = { Text(stringResource(Res.string.question_custom_placeholder), fontSize = 11.sp, color = colors.textMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
                        shape = RoundedCornerShape(6.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surfaceWorkspace,
                            unfocusedContainerColor = colors.surfaceWorkspace,
                            focusedBorderColor = colors.accentPrimary,
                            unfocusedBorderColor = colors.divider,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = colors.accentPrimary
                        )
                    )
                }
            }
        }

        // Action Buttons (支持多题翻页 [上一步] / [下一步/提交])
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MederiGhostButton(
                text = stringResource(Res.string.question_cancel),
                onClick = onCancel,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentIndex > 0) {
                    MederiSurfaceButton(
                        text = stringResource(Res.string.question_prev),
                        onClick = onPrevPage,
                    )
                }

                if (currentIndex < qList.size - 1) {
                    MederiSurfaceButton(
                        text = stringResource(Res.string.question_next),
                        onClick = onNextPage,
                    )
                } else {
                    MederiPrimaryDecisionButton(
                        text = stringResource(Res.string.question_submit),
                        onClick = onSubmit,
                    )
                }
            }
        }
    }
}
