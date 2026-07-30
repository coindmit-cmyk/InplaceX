package com.mirkori.inplacex.platform.ads

import android.content.Context

enum class AdConsentDecision {
    UNDECIDED,
    ACCEPTED,
    DECLINED,
}

fun interface AdConsentProvider {
    fun currentDecision(): AdConsentDecision
}

interface AdConsentController : AdConsentProvider {
    fun updateDecision(decision: AdConsentDecision)
}

object UndecidedAdConsentProvider : AdConsentProvider {
    override fun currentDecision(): AdConsentDecision = AdConsentDecision.UNDECIDED
}

object AcceptedAdConsentProvider : AdConsentProvider {
    override fun currentDecision(): AdConsentDecision = AdConsentDecision.ACCEPTED
}

class SharedPreferencesAdConsentController(
    context: Context,
) : AdConsentController {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun currentDecision(): AdConsentDecision =
        parseStoredAdConsent(preferences.getString(DecisionKey, null))

    override fun updateDecision(decision: AdConsentDecision) {
        if (decision == AdConsentDecision.UNDECIDED) {
            preferences.edit().remove(DecisionKey).apply()
        } else {
            preferences.edit().putString(DecisionKey, decision.name).apply()
        }
    }

    companion object {
        const val PreferencesName = "inplacex_ad_privacy"
        private const val DecisionKey = "consent_decision"
    }
}

internal fun parseStoredAdConsent(value: String?): AdConsentDecision = when (value) {
    AdConsentDecision.ACCEPTED.name -> AdConsentDecision.ACCEPTED
    AdConsentDecision.DECLINED.name -> AdConsentDecision.DECLINED
    else -> AdConsentDecision.UNDECIDED
}
