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

fun interface AdConsentChangeHandler {
    suspend fun onConsentChanged(decision: AdConsentDecision)
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
        parseStoredAdConsent(
            value = preferences.getString(DecisionKey, null),
            storedPolicyVersion = preferences.getInt(PolicyVersionKey, 0),
        )

    override fun updateDecision(decision: AdConsentDecision) {
        if (decision == AdConsentDecision.UNDECIDED) {
            preferences.edit()
                .remove(DecisionKey)
                .remove(PolicyVersionKey)
                .apply()
        } else {
            preferences.edit()
                .putString(DecisionKey, decision.name)
                .putInt(PolicyVersionKey, CurrentPolicyVersion)
                .apply()
        }
    }

    companion object {
        const val PreferencesName = "inplacex_ad_privacy"
        private const val DecisionKey = "consent_decision"
        private const val PolicyVersionKey = "consent_policy_version"
        internal const val CurrentPolicyVersion = 1
    }
}

internal fun parseStoredAdConsent(
    value: String?,
    storedPolicyVersion: Int = SharedPreferencesAdConsentController.CurrentPolicyVersion,
): AdConsentDecision =
    if (storedPolicyVersion != SharedPreferencesAdConsentController.CurrentPolicyVersion) {
        AdConsentDecision.UNDECIDED
    } else {
        when (value) {
            AdConsentDecision.ACCEPTED.name -> AdConsentDecision.ACCEPTED
            AdConsentDecision.DECLINED.name -> AdConsentDecision.DECLINED
            else -> AdConsentDecision.UNDECIDED
        }
    }
