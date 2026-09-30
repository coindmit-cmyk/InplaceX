package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.navigation.AppSectionCatalog

@Composable
fun AppBottomBar(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = AppSectionCatalog.title(currentSection, strings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            AppBottomMenu(
                currentSection = currentSection,
                onSectionChange = onSectionChange
            )

            AppBottomReserve(
                text = AppSectionCatalog.reserveText(currentSection, strings)
            )
        }
    }
}
