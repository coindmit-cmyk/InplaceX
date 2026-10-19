package com.mirkori.inplacex.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.TypeGame
import com.mirkori.inplacex.ui.theme.InplaceXTheme

/** Isolated debug fixture: it renders the production route without opening MainActivity. */
class GameFieldRecreationTestActivity : ComponentActivity() {
    var observedSecret: String? = null
        private set
    var matchStartCount: Int = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observedSecret = savedInstanceState?.getString(SECRET_KEY)
        matchStartCount = savedInstanceState?.getInt(START_COUNT_KEY) ?: 0
        setContent {
            var session by rememberSaveable { mutableIntStateOf(0) }
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU),
            ) {
                InplaceXTheme {
                    Column {
                        Button(
                            onClick = { session++ },
                            modifier = Modifier.testTag("game-fixture-new-session"),
                        ) { Text("New session") }
                        key(session) {
                            GameFieldScreen(
                                params = GameFieldParams(
                                    typeGame = TypeGame.RaceMatch,
                                    useHints = false,
                                    timeAll = 120,
                                    limitMoves = 12,
                                    lenSecret = 4,
                                ),
                                title = "",
                                fixedSecret = "1234",
                                onBack = {},
                                onDebugSecretChange = { observedSecret = it },
                                onMatchStarted = { matchStartCount++ },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SECRET_KEY, observedSecret)
        outState.putInt(START_COUNT_KEY, matchStartCount)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val SECRET_KEY = "fixture_observed_secret"
        const val START_COUNT_KEY = "fixture_match_start_count"
    }
}
