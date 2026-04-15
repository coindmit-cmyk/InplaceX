package com.mirkori.inplacex.platform.config

data class PlatformConfig(
    val navigationItems: List<SectionSpec>,
    val featureFlags: FeatureFlags = FeatureFlags(),
    val layoutProfile: LayoutProfile = LayoutProfile(),
)

data class SectionSpec(
    val id: String,
    val titleKey: String,
    val shortLabelKey: String,
    val reserveTextKey: String,
)

data class FeatureFlags(
    val adsEnabled: Boolean = true,
    val analyticsEnabled: Boolean = false,
    val socialEnabled: Boolean = true,
    val remotePlayEnabled: Boolean = false,
)

data class LayoutProfile(
    val autoAdaptToScreenSize: Boolean = true,
    val compactModeEnabled: Boolean = true,
)
