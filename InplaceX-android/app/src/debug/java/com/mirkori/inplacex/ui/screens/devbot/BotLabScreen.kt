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
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BotLabScreen(
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
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
                text = strings.text("developer.bot_lab.title"),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) {
                Text(strings.text("developer.bot_lab.back"))
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
                    text = strings.text("developer.bot_lab.description"),
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = secretInput,
                    onValueChange = {
                        secretInput = it.filter(Char::isDigit).take(20)
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.text("developer.bot_lab.secret")) },
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
                        label = { Text(strings.text("developer.bot_lab.length")) },
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = samplesInput,
                        onValueChange = {
                            samplesInput = it.filter(Char::isDigit).take(3)
                            errorText = null
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(strings.text("developer.bot_lab.runs")) },
                        singleLine = true,
                    )
                }

                SwitchRow(
                    title = strings.text("developer.bot_lab.allow_duplicates"),
                    checked = allowDuplicates,
                    onCheckedChange = {
                        allowDuplicates = it
                        errorText = null
                    },
                )

                SwitchRow(
                    title = strings.text("developer.bot_lab.forbid_adjacent"),
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
                    title = strings.text("developer.bot_lab.forbid_triple"),
                    checked = forbidTripleDuplicates,
                    onCheckedChange = {
                        forbidTripleDuplicates = it
                        errorText = null
                    },
                )

                Text(
                    text = strings.text("developer.bot_lab.behavior"),
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
                            errorText = strings.text("developer.bot_lab.error.code_length")
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
                            errorText = strings.format("developer.bot_lab.error.secret_length", "value" to codeLength.toString())
                            return@Button
                        }
                        if (secret != null && !GuessValidator.validate(secret, config)) {
                            errorText = strings.text("developer.bot_lab.error.secret_rules")
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
                    Text(if (isRunning) strings.text("developer.bot_lab.running") else strings.text("developer.bot_lab.run"))
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
                        text = strings.text("developer.bot_lab.report"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(strings.format("developer.bot_lab.report.code_length", "value" to currentReport.config.codeLength.toString()))
                    Text(strings.format("developer.bot_lab.report.allow_duplicates", "value" to currentReport.config.allowDuplicates.toString()))
                    Text(strings.format("developer.bot_lab.report.forbid_adjacent", "value" to currentReport.config.forbidAdjacentDuplicates.toString()))
                    Text(strings.format("developer.bot_lab.report.forbid_triple", "value" to currentReport.config.forbidTripleDuplicates.toString()))
                    Text(strings.format("developer.bot_lab.report.behavior", "value" to BotBehaviorProfiles.forModel(currentReport.behavior).title))
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
                            Text(
                                strings.format(
                                    "developer.bot_lab.report.wins",
                                    "wins" to entry.wins.toString(),
                                    "samples" to entry.samples.toString(),
                                    "rate" to (entry.winRate * 100).toInt().toString(),
                                )
                            )
                            Text(strings.format("developer.bot_lab.report.average_turns", "value" to "%.2f".format(entry.averageMoves)))
                            Text(strings.format("developer.bot_lab.report.best_worst", "best" to entry.bestMoves.toString(), "worst" to entry.worstMoves.toString()))
                            Text(strings.format("developer.bot_lab.report.target_pace", "value" to entry.targetMoves.toString()))
                            Text(strings.format("developer.bot_lab.report.secrets", "value" to entry.secrets.joinToString()))
                        }
                    }
                }
            }
        }
    }
}

private fun LocalizationProvider.format(key: String, vararg values: Pair<String, String>): String =
    values.fold(text(key)) { formatted, (name, value) -> formatted.replace("{$name}", value) }

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
