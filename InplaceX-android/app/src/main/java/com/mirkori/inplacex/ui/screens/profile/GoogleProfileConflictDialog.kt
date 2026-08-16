package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mirkori.inplacex.platform.localization.LocalizationProvider

@Composable
internal fun GoogleProfileConflictDialog(
    strings: LocalizationProvider,
    busy: Boolean,
    onUseExistingProfile: () -> Unit,
    onKeepCurrentProfile: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("google-profile-conflict-dialog"),
        onDismissRequest = { if (!busy) onKeepCurrentProfile() },
        title = { Text(strings.text("profile.google.conflict.title")) },
        text = { Text(strings.text("profile.google.conflict.message")) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("google-profile-conflict-use-existing"),
                enabled = !busy,
                onClick = onUseExistingProfile,
            ) {
                Text(strings.text("profile.google.conflict.use_existing"))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("google-profile-conflict-keep-current"),
                enabled = !busy,
                onClick = onKeepCurrentProfile,
            ) {
                Text(strings.text("profile.google.conflict.keep_current"))
            }
        },
    )
}
