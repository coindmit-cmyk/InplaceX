package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.mirkori.inplacex.ui.navigation.AppSection
import androidx.compose.ui.unit.dp

@Composable
fun AppBottomMenu(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppSection.entries.forEach { section ->
            FilledTonalButton(
                onClick = { onSectionChange(section) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16)
            ) {
                Text(
                    text = section.shortLabel,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
