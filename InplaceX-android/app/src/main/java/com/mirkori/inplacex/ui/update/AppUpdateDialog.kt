package com.mirkori.inplacex.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.update.AppUpdateRelease
import com.mirkori.inplacex.platform.update.AppUpdateState

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    onPrimaryAction: () -> Unit,
    onDismissOptional: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val release = state.releaseOrNull()
    val required = state.isRequired()
    val busy = state is AppUpdateState.Downloading ||
        state is AppUpdateState.Verifying ||
        state is AppUpdateState.Refreshing ||
        state is AppUpdateState.HandingOff
    AlertDialog(
        modifier = Modifier.testTag(if (required) "required-update-dialog" else "optional-update-dialog"),
        onDismissRequest = { if (!required && !busy) onDismissOptional() },
        title = {
            Text(strings.text(if (required) "update.required.title" else "update.optional.title"))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (release == null) {
                    Text(strings.text("update.policy_recovery.body"))
                } else {
                    Text(
                        strings.text(if (required) "update.required.body" else "update.optional.body")
                            .replace("{version}", release.versionName),
                    )
                    Text(strings.text("update.size").replace("{size}", formatMegabytes(release.sizeBytes)))
                    Text(release.changelog)
                }
                state.statusKeyOrNull()?.let { key -> Text(strings.text(key)) }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("update-primary-action"),
                enabled = !busy,
                onClick = onPrimaryAction,
            ) {
                Text(strings.text(state.primaryActionKey()))
            }
        },
        dismissButton = if (required || busy) {
            null
        } else {
            {
                TextButton(
                    modifier = Modifier.testTag("update-later"),
                    onClick = onDismissOptional,
                ) {
                    Text(strings.text("update.later"))
                }
            }
        },
    )
}

internal fun formatMegabytes(sizeBytes: Long): String {
    require(sizeBytes > 0)
    val tenths = (sizeBytes * 10L + BytesPerMegabyte - 1L) / BytesPerMegabyte
    return "${tenths / 10}.${tenths % 10} MB"
}

private fun AppUpdateState.releaseOrNull(): AppUpdateRelease? = when (this) {
    is AppUpdateState.Optional -> release
    is AppUpdateState.Required -> release
    is AppUpdateState.Downloading -> release
    is AppUpdateState.Verifying -> release
    is AppUpdateState.Refreshing -> release
    is AppUpdateState.NeedsPermission -> release
    is AppUpdateState.Ready -> release
    is AppUpdateState.HandingOff -> release
    is AppUpdateState.Unsupported -> release
    is AppUpdateState.RecoverableError -> release
    AppUpdateState.Checking, AppUpdateState.Current, AppUpdateState.PolicyRecovery -> null
}

private fun AppUpdateState.isRequired(): Boolean = when (this) {
    is AppUpdateState.Required -> true
    is AppUpdateState.Downloading -> required
    is AppUpdateState.Verifying -> required
    is AppUpdateState.Refreshing -> required
    is AppUpdateState.NeedsPermission -> required
    is AppUpdateState.Ready -> required
    is AppUpdateState.HandingOff -> required
    is AppUpdateState.Unsupported -> required
    is AppUpdateState.RecoverableError -> required
    AppUpdateState.PolicyRecovery -> true
    else -> false
}

private fun AppUpdateState.statusKeyOrNull(): String? = when (this) {
    is AppUpdateState.Downloading -> "update.status.downloading"
    is AppUpdateState.Verifying -> "update.status.verifying"
    is AppUpdateState.Refreshing -> "update.status.refreshing"
    is AppUpdateState.NeedsPermission -> "update.status.permission"
    is AppUpdateState.Ready -> "update.status.ready"
    is AppUpdateState.HandingOff -> "update.status.handing_off"
    is AppUpdateState.Unsupported -> "update.status.unsupported"
    is AppUpdateState.RecoverableError -> "update.status.recoverable_error"
    AppUpdateState.PolicyRecovery -> "update.status.policy_recovery"
    else -> null
}

private fun AppUpdateState.primaryActionKey(): String = when (this) {
    is AppUpdateState.NeedsPermission -> "update.allow_installation"
    is AppUpdateState.Ready -> "update.install"
    is AppUpdateState.Unsupported -> "update.retry"
    is AppUpdateState.RecoverableError -> if (artifact == null) "update.retry" else "update.install"
    is AppUpdateState.Downloading -> "update.status.downloading"
    is AppUpdateState.Verifying -> "update.status.verifying"
    is AppUpdateState.Refreshing -> "update.status.refreshing"
    is AppUpdateState.HandingOff -> "update.status.handing_off"
    AppUpdateState.PolicyRecovery -> "update.retry"
    else -> "update.download"
}

private const val BytesPerMegabyte = 1024L * 1024L
