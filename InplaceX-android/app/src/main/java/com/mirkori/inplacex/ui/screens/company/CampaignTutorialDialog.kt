package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalizationProvider

private data class CampaignTutorialStep(
    val titleKey: String,
    val bodyKey: String,
)

private val campaignTutorialSteps = listOf(
    CampaignTutorialStep(
        titleKey = "company.tutorial.goal.title",
        bodyKey = "company.tutorial.goal.body",
    ),
    CampaignTutorialStep(
        titleKey = "company.tutorial.feedback.title",
        bodyKey = "company.tutorial.feedback.body",
    ),
    CampaignTutorialStep(
        titleKey = "company.tutorial.hints.title",
        bodyKey = "company.tutorial.hints.body",
    ),
)

@Composable
internal fun CampaignTutorialDialog(
    strings: LocalizationProvider,
    onExit: () -> Unit,
    onComplete: () -> Unit,
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = campaignTutorialSteps[stepIndex]
    val isLastStep = stepIndex == campaignTutorialSteps.lastIndex

    AlertDialog(
        onDismissRequest = onExit,
        modifier = Modifier.testTag("company-tutorial"),
        title = { Text(strings.text(step.titleKey)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    strings.text("company.tutorial.step")
                        .replace("{current}", (stepIndex + 1).toString())
                        .replace("{total}", campaignTutorialSteps.size.toString()),
                )
                Text(strings.text(step.bodyKey))
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(
                    if (isLastStep) "company-tutorial-start" else "company-tutorial-next",
                ),
                onClick = {
                    if (isLastStep) {
                        onComplete()
                    } else {
                        stepIndex += 1
                    }
                },
            ) {
                Text(
                    strings.text(
                        if (isLastStep) "company.tutorial.start" else "company.tutorial.next",
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(strings.text("company.tutorial.later"))
            }
        },
    )
}
