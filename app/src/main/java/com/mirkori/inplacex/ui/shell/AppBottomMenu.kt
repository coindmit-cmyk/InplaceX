package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppBottomMenu(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val screenWidth = maxWidth
        val buttonGap = screenWidth * 0.015f
        val buttonCorner = 16.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(buttonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSection.entries.forEach { section ->
                FilledTonalButton(
                    onClick = { onSectionChange(section) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(buttonCorner)
                ) {
                    Text(
                        text = section.shortLabel,
                        textAlign = TextAlign.Center,
                        style = if (section == currentSection) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.labelMedium
                        }
                    )
                }
            }
        }
    }
}
