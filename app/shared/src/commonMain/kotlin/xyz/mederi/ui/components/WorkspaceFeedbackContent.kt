package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.ErrorState

/** 主对话错误的纯适配：只使用已有公共ErrorBoard，不持有VM或副作用。 */
@Composable
internal fun WorkspaceFeedbackContent(
    state: ErrorState,
    onShowDetail: () -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ErrorBoard(
        errorMessage = state.message?.let { stringResource(it.key, *it.args.toTypedArray()) },
        onShowDetail = onShowDetail,
        onDismiss = onDismiss,
        showContinue = state.isStreamInterrupted,
        onContinue = onContinue,
        modifier = modifier,
    )
}
