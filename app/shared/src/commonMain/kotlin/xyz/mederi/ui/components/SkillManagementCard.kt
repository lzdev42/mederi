package xyz.mederi.ui.components

import androidx.compose.runtime.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.TextSubmissionDialog
import xyz.mederi.ui.components.atoms.rememberDialogSubmission
import xyz.mederi.util.pickDirectory

/** 领域层只装配文案和原 Store 回调，共用提交表单。 */
@Composable
internal fun InstallSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<SkillItem>,
    colors: MederiColors,
) {
    CompositionLocalProvider(LocalMederiColors provides colors) {
        TextSubmissionDialog(stringResource(Res.string.skill_install), stringResource(Res.string.skill_install_prompt), "",
            stringResource(Res.string.skill_download_url), stringResource(Res.string.skill_install_action),
            stringResource(Res.string.skill_installing), stringResource(Res.string.skill_install_failed),
            onDismiss, onConfirm, placeholder = stringResource(Res.string.skill_url_placeholder))
    }
}

@Composable
internal fun SetSkillRootDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>,
    colors: MederiColors,
) {
    val pickDirectoryTitle = stringResource(Res.string.pick_directory_title)
    CompositionLocalProvider(LocalMederiColors provides colors) {
        TextSubmissionDialog(
            if (isDesktopPlatform) stringResource(Res.string.skill_set_root_title) else stringResource(Res.string.skill_set_root_title_remote),
            if (isDesktopPlatform) stringResource(Res.string.skill_root_prompt) else stringResource(Res.string.skill_root_prompt_remote),
            initialPath, stringResource(Res.string.skill_directory_path), stringResource(Res.string.skill_save),
            stringResource(Res.string.skill_saving), stringResource(Res.string.skill_save_dir_failed), onDismiss, onConfirm,
            onPick = if (isDesktopPlatform) ({ pickDirectory(pickDirectoryTitle) }) else null,
            pickLabel = stringResource(Res.string.skill_pick_dir_native))
    }
}

@Composable
internal fun UninstallSkillConfirmDialog(
    skill: SkillItem,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Result<Unit>,
    colors: MederiColors,
) {
    val submission = rememberDialogSubmission()
    val fallbackError = stringResource(Res.string.skill_uninstall_failed)
    val busy = isDeleting || submission.busy
    CompositionLocalProvider(LocalMederiColors provides colors) {
        ConfirmDialog(title = stringResource(Res.string.skill_uninstall_title),
            message = stringResource(Res.string.skill_uninstall_confirm, skill.name),
            confirmLabel = stringResource(if (busy) Res.string.skill_deleting else Res.string.skill_uninstall_confirm_action),
            cancelLabel = stringResource(Res.string.cancel),
            onConfirm = { if (!busy) submission.submit(fallbackError, onConfirm, onDismiss) },
            onDismiss = onDismiss, danger = true, enabled = !busy,
            supportingContent = { submission.error?.let { ErrorBoard(it) } })
    }
}
