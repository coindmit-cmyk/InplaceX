package com.mirkori.inplacex.platform.localization

@JvmInline
value class LocalizationKey(val value: String)

data class LocalizationEntry(
    val key: LocalizationKey,
    val value: String,
)

class LocalizationCatalog internal constructor(
    val name: String,
    entries: List<LocalizationEntry>,
) {
    val entries: List<LocalizationEntry> = entries.toList()
    val keys: Set<String> = this.entries.map { it.key.value }.toSet()
    val values: Map<String, String> = this.entries.associate { it.key.value to it.value }

    init {
        val duplicateKeys = this.entries
            .groupingBy { it.key.value }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateKeys.isEmpty()) {
            "Localization catalog '$name' contains duplicate keys: ${duplicateKeys.sorted().joinToString()}"
        }
    }
}

internal fun localizationEntry(key: String, value: String): LocalizationEntry =
    LocalizationEntry(LocalizationKey(key), value)

internal fun localizationCatalog(
    name: String,
    entries: List<LocalizationEntry>,
): LocalizationCatalog = LocalizationCatalog(name, entries)

internal fun aggregateLocalizationCatalogs(
    name: String,
    catalogs: List<LocalizationCatalog>,
): LocalizationCatalog = LocalizationCatalog(name, catalogs.flatMap { it.entries })
