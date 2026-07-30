package com.mirkori.inplacex.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun AdPrivacyConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(strings.text("ads.privacy.title"))
        },
        text = {
            Text(strings.text("ads.privacy.description"))
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(strings.text("ads.privacy.accept"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(strings.text("ads.privacy.decline"))
            }
        },
    )
}
