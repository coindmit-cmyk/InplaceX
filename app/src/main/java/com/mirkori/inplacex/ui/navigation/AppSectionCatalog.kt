package com.mirkori.inplacex.ui.navigation

import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.config.SectionSpec
import com.mirkori.inplacex.platform.localization.LocalizationProvider

object AppSectionCatalog {
    private val specsById = AppConfigCatalog.platformConfig.navigationItems.associateBy(SectionSpec::id)

    fun title(section: AppSection, strings: LocalizationProvider): String = text(section, SectionSpec::titleKey, strings)

    fun shortLabel(section: AppSection, strings: LocalizationProvider): String = text(section, SectionSpec::shortLabelKey, strings)

    fun reserveText(section: AppSection, strings: LocalizationProvider): String = text(section, SectionSpec::reserveTextKey, strings)

    private fun text(
        section: AppSection,
        selector: (SectionSpec) -> String,
        strings: LocalizationProvider
    ): String {
        val spec = specsById[section.sectionId]
        return if (spec != null) strings.text(selector(spec)) else section.name
    }
}
