package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.Res
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
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionSurface

/**
 * 需要用户处理的问答，选中值始终由调用方提供，组件不保存答案副本。
 * 单选保留已有自定义回答，多选和自定义输入继续经同一 onAnswer 回调更新。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    modifier: Modifier = Modifier,
) {
    if (question == null) return
    val colors = LocalMederiColors.current
    val qList = question.questions
    val qInfo = qList.getOrNull(currentIndex) ?: qList.firstOrNull() ?: return
    val typeTag = when {
        qInfo.options.isEmpty() -> stringResource(Res.string.question_free_input_tag)
        qInfo.multiSelect -> stringResource(Res.string.question_multi_tag)
        else -> stringResource(Res.string.question_single_tag)
    }

    WorkspaceActionSurface(modifier = modifier.widthIn(max = ChatLayout.actionCardMaxWidth)) {
        Text(
            text = stringResource(Res.string.question_title, currentIndex + 1, qList.size) + " · $typeTag",
            style = WorkspaceStyle.Caption,
            color = colors.textSecondary,
        )
        Text(text = qInfo.prompt, style = WorkspaceStyle.Title, color = colors.textPrimary)

        if (qInfo.options.isEmpty()) {
            OutlinedTextField(
                value = selectedAnswers.firstOrNull() ?: "",
                onValueChange = { onAnswer(if (it.isBlank()) emptyList() else listOf(it)) },
                placeholder = {
                    Text(stringResource(Res.string.question_input_placeholder),
                        style = WorkspaceStyle.Body, color = colors.textMuted)
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = WorkspaceStyle.Body.copy(color = colors.textPrimary),
                shape = RoundedCornerShape(MederiRadius.Control),
                colors = questionFieldColors(),
            )
        } else {
            Column(
                modifier = if (qInfo.multiSelect) Modifier else Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            ) {
                qInfo.options.forEach { opt ->
                    val isSelected = selectedAnswers.contains(opt)
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    val focused by source.collectIsFocusedAsState()
                    val background by animateColorAsState(
                        targetValue = when {
                            isSelected -> colors.accentBg
                            hovered || focused -> colors.surfaceHover
                            else -> Color.Transparent
                        },
                        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
                        label = "questionOptionBackground",
                    )
                    val onSelect = {
                        if (qInfo.multiSelect) {
                            onAnswer(if (isSelected) selectedAnswers - opt else selectedAnswers + opt)
                        } else {
                            val nonOptions = selectedAnswers.filter { it !in qInfo.options }
                            onAnswer(listOf(opt) + nonOptions)
                        }
                    }
                    val shape = RoundedCornerShape(MederiRadius.Control)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = WorkspaceStyle.ControlHeight)
                            .clip(shape).background(background)
                            .border(1.dp, if (focused) colors.accentFocus else Color.Transparent, shape)
                            .hoverable(source)
                            .then(if (qInfo.multiSelect) {
                                Modifier.toggleable(
                                    value = isSelected, role = Role.Checkbox,
                                    interactionSource = source, indication = null,
                                    onValueChange = { onSelect() },
                                )
                            } else {
                                Modifier.selectable(
                                    selected = isSelected, role = Role.RadioButton,
                                    interactionSource = source, indication = null,
                                    onClick = onSelect,
                                )
                            })
                            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.Tight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
                    ) {
                        if (qInfo.multiSelect) {
                            Checkbox(
                                checked = isSelected, onCheckedChange = null,
                                modifier = Modifier.size(20.dp),
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.accentPrimary,
                                    checkmarkColor = colors.onAccentPrimary,
                                    uncheckedColor = colors.textMuted,
                                ),
                            )
                        } else {
                            RadioButton(
                                selected = isSelected, onClick = null,
                                modifier = Modifier.size(20.dp),
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.accentPrimary,
                                    unselectedColor = colors.textMuted,
                                ),
                            )
                        }
                        Text(
                            text = opt, style = WorkspaceStyle.Body,
                            color = if (isSelected || hovered || focused) colors.textPrimary else colors.textSecondary,
                        )
                    }
                }
            }
            val customValue = selectedAnswers.firstOrNull { it !in qInfo.options } ?: ""
            OutlinedTextField(
                value = customValue,
                onValueChange = { custom ->
                    val options = selectedAnswers.filter { it in qInfo.options }
                    onAnswer(if (custom.isBlank()) options else options + custom)
                },
                placeholder = {
                    Text(stringResource(Res.string.question_custom_placeholder),
                        style = WorkspaceStyle.Body, color = colors.textMuted)
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = WorkspaceStyle.Body.copy(color = colors.textPrimary),
                shape = RoundedCornerShape(MederiRadius.Control),
                singleLine = true,
                colors = questionFieldColors(),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            WorkspaceActionButton(stringResource(Res.string.question_cancel), onCancel)
            if (currentIndex > 0) WorkspaceActionButton(stringResource(Res.string.question_prev), onPrevPage)
            if (currentIndex < qList.size - 1) {
                WorkspaceActionButton(stringResource(Res.string.question_next), onNextPage, primary = true)
            } else {
                WorkspaceActionButton(stringResource(Res.string.question_submit), onSubmit, primary = true)
            }
        }
    }
}

@Composable
private fun questionFieldColors(): TextFieldColors {
    val colors = LocalMederiColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceWorkspace,
        unfocusedContainerColor = colors.surfaceWorkspace,
        focusedBorderColor = colors.accentFocus,
        unfocusedBorderColor = colors.divider,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        cursorColor = colors.accentPrimary,
    )
}
