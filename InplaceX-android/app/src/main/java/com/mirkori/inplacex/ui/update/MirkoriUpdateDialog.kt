package com.mirkori.inplacex.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriAvailableUpdate
import com.mirkori.inplacex.platform.mirkori.MirkoriUpdateTarget

@Composable
internal fun MirkoriUpdateDialog(
    strings: LocalizationProvider,
    language: AppLanguage,
    update: MirkoriAvailableUpdate,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("mirkori-update-dialog"),
        onDismissRequest = { if (!update.required) onDismiss() },
        title = {
            Text(
                strings.text(
                    if (update.required) "update.required.title" else "update.optional.title",
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text("update.version").replace("{version}", update.versionName))
                Text(update.changelogs.getValue(language.name.lowercase()))
                if (!update.compatible) {
                    Text(
                        strings.text("update.android.unsupported")
                            .replace("{sdk}", update.minimumAndroidSdk.toString()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("mirkori-update-open"),
                onClick = onOpen,
            ) {
                Text(
                    strings.text(
                        when (update.target) {
                            is MirkoriUpdateTarget.DirectApk -> "update.action.download"
                            is MirkoriUpdateTarget.GooglePlay -> "update.action.google_play"
                        },
                    ),
                )
            }
        },
        dismissButton = if (update.required) {
            null
        } else {
            {
                TextButton(
                    modifier = Modifier.testTag("mirkori-update-dismiss"),
                    onClick = onDismiss,
                ) {
                    Text(strings.text("update.action.later"))
                }
            }
        },
    )
}
