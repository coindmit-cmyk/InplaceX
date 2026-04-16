package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AppBottomReserve(
    text: String,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val screenWidth = maxWidth
        val horizontalPadding = screenWidth * 0.04f

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = horizontalPadding, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (content == null) {
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    content()
                }
            }
        }
    }
}
