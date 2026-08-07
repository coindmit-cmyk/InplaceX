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

@Composable
internal fun AppUpdateDialog(
    release: AppUpdateRelease,
    required: Boolean,
    downloadError: Boolean,
    onDownload: () -> Unit,
    onDismissOptional: () -> Unit,
) {
    val strings = LocalAppStrings.current
    AlertDialog(
        modifier = Modifier.testTag(if (required) "required-update-dialog" else "optional-update-dialog"),
        onDismissRequest = { if (!required) onDismissOptional() },
        title = {
            Text(strings.text(if (required) "update.required.title" else "update.optional.title"))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    strings.text(if (required) "update.required.body" else "update.optional.body")
                        .replace("{version}", release.versionName),
                )
                Text(
                    strings.text("update.size")
                        .replace("{size}", formatMegabytes(release.sizeBytes)),
                )
                Text(release.changelog)
                if (downloadError) {
                    Text(strings.text("update.download.unavailable"))
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("update-download"),
                onClick = onDownload,
            ) {
                Text(strings.text("update.download"))
            }
        },
        dismissButton = if (required) {
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

private const val BytesPerMegabyte = 1024L * 1024L
