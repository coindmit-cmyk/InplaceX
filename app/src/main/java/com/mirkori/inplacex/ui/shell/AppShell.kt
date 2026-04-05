package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppShell(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        ShellBackground(
            paddingValues = paddingValues,
            currentSection = currentSection,
            onSectionChange = onSectionChange,
            content = content
        )
    }
}

@Composable
private fun ShellBackground(
    paddingValues: PaddingValues,
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    content: @Composable () -> Unit
) {
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(
                top = safeDrawing.calculateTopPadding(),
                bottom = navBar.calculateBottomPadding()
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    content()
                }
            }

            AppBottomBar(
                currentSection = currentSection,
                onSectionChange = onSectionChange
            )
        }
    }
}
