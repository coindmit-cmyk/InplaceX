package com.mirkori.inplacex.platform.services

interface AdService {
    fun showBanner(slotId: String)
}

interface AnalyticsService {
    fun track(event: String, properties: Map<String, String> = emptyMap())
}

interface ProfileService {
    fun isSignedIn(): Boolean
}

interface SocialService {
    fun openFriends()
}
