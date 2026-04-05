package com.mirkori.inplacex.ui.screens.tournaments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TournamentsRootScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Турниры",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Экран-заглушка под список турниров и событий",
            style = MaterialTheme.typography.bodyLarge
        )
        FilledTonalButton(onClick = { }) {
            Text("Список турниров")
        }
    }
}
