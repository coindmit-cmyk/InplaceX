package com.mirkori.inplacex.ui.screens.devbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.bot.BotBehaviorModel
import com.mirkori.inplacex.core.bot.BotBehaviorProfiles
import com.mirkori.inplacex.core.bot.BotBenchmarkReport
import com.mirkori.inplacex.core.bot.BotBenchmarkRequest
import com.mirkori.inplacex.core.bot.BotBenchmarkRunner
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.model.GameConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BotLabScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var secretInput by remember { mutableStateOf("") }
    var codeLengthInput by remember { mutableStateOf("6") }
    var samplesInput by remember { mutableStateOf("1") }
    var allowDuplicates by remember { mutableStateOf(true) }
    var forbidAdjacentDuplicates by remember { mutableStateOf(false) }
    var forbidTripleDuplicates by remember { mutableStateOf(false) }
    var behavior by remember { mutableStateOf(BotBehaviorModel.BALANCED) }
    var report by remember { mutableStateOf<BotBenchmarkReport?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Bot Lab",
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Developer screen for bot checks. Fix one secret and compare how many turns each difficulty needs.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = secretInput,
                    onValueChange = {
                        secretInput = it.filter(Char::isDigit).take(20)
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Secret (empty = auto)") },
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = codeLengthInput,
                        onValueChange = {
                            codeLengthInput = it.filter(Char::isDigit).take(2)
                            errorText = null
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Length") },
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = samplesInput,
                        onValueChange = {
                            samplesInput = it.filter(Char::isDigit).take(3)
                            errorText = null
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Runs") },
                        singleLine = true,
                    )
                }

                SwitchRow(
                    title = "Allow duplicate digits",
                    checked = allowDuplicates,
                    onCheckedChange = {
                        allowDuplicates = it
                        errorText = null
                    },
                )

                SwitchRow(
                    title = "Forbid adjacent duplicates",
                    checked = forbidAdjacentDuplicates,
                    onCheckedChange = {
                        forbidAdjacentDuplicates = it
                        if (it) {
                            forbidTripleDuplicates = true
                        }
                        errorText = null
                    },
                )

                SwitchRow(
                    title = "Forbid triple duplicates",
                    checked = forbidTripleDuplicates,
                    onCheckedChange = {
                        forbidTripleDuplicates = it
                        errorText = null
                    },
                )

                Text(
                    text = "Behavior model",
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotBehaviorModel.entries.forEach { candidate ->
                        FilterChip(
                            selected = behavior == candidate,
                            onClick = { behavior = candidate },
                            label = { Text(BotBehaviorProfiles.forModel(candidate).title) },
                        )
                    }
                }

                Text(
                    text = BotBehaviorProfiles.forModel(behavior).description,
                    style = MaterialTheme.typography.bodySmall,
                )

                errorText?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = {
                        val codeLength = codeLengthInput.toIntOrNull()
                        val samples = samplesInput.toIntOrNull() ?: 1
                        if (codeLength == null || codeLength !in 4..20) {
                            errorText = "Code length must be between 4 and 20."
                            return@Button
                        }

                        val config = GameConfig(
                            codeLength = codeLength,
                            allowDuplicates = allowDuplicates,
                            attemptLimit = 40,
                            forbidAllSameDigitsGuess = true,
                            forbidAdjacentDuplicates = forbidAdjacentDuplicates,
                            forbidTripleDuplicates = forbidTripleDuplicates,
                        )
                        val secret = secretInput.takeIf { it.isNotBlank() }
                        if (secret != null && secret.length != codeLength) {
                            errorText = "Secret must contain exactly $codeLength digits."
                            return@Button
                        }
                        if (secret != null && !GuessValidator.validate(secret, config)) {
                            errorText = "Secret does not match the active rules."
                            return@Button
                        }

                        isRunning = true
                        errorText = null
                        scope.launch {
                            report = withContext(Dispatchers.Default) {
                                BotBenchmarkRunner.run(
                                    BotBenchmarkRequest(
                                        config = config,
                                        secret = secret,
                                        behavior = behavior,
                                        samplesPerDifficulty = samples.coerceAtLeast(1),
                                    ),
                                )
                            }
                            isRunning = false
                        }
                    },
                    enabled = !isRunning,
                ) {
                    Text(if (isRunning) "Running..." else "Run benchmark")
                }
            }
        }

        if (isRunning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        report?.let { currentReport ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Report",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Code length: ${currentReport.config.codeLength}")
                    Text("Allow duplicates: ${currentReport.config.allowDuplicates}")
                    Text("Forbid adjacent duplicates: ${currentReport.config.forbidAdjacentDuplicates}")
                    Text("Forbid triple duplicates: ${currentReport.config.forbidTripleDuplicates}")
                    Text("Behavior: ${BotBehaviorProfiles.forModel(currentReport.behavior).title}")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(currentReport.entries) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = entry.difficulty.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Wins: ${entry.wins}/${entry.samples} (${(entry.winRate * 100).toInt()}%)")
                            Text("Average turns: ${"%.2f".format(entry.averageMoves)}")
                            Text("Best / worst: ${entry.bestMoves} / ${entry.worstMoves}")
                            Text("Target pace: ${entry.targetMoves} turns")
                            Text("Secrets: ${entry.secrets.joinToString()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
