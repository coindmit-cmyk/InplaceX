package com.mirkori.inplacex.platform.localization

internal object UpdateCatalog {
    val ru = localizationCatalog(
        name = "update.ru",
        entries = listOf(
            localizationEntry("update.optional.title", "Доступно обновление"),
            localizationEntry(
                "update.optional.body",
                "Доступна версия {version}. Можно обновить сейчас или продолжить игру.",
            ),
            localizationEntry("update.required.title", "Нужно обновить InplaceX"),
            localizationEntry(
                "update.required.body",
                "Версия {version} обязательна для продолжения. Скачайте подписанный APK с Mirkori Games.",
            ),
            localizationEntry("update.size", "Размер: {size}"),
            localizationEntry("update.download", "Скачать APK"),
            localizationEntry("update.later", "Позже"),
            localizationEntry(
                "update.download.unavailable",
                "Не удалось открыть браузер. Проверьте, что на устройстве есть приложение для HTTPS-ссылок.",
            ),
        ),
    )

    val en = localizationCatalog(
        name = "update.en",
        entries = listOf(
            localizationEntry("update.optional.title", "Update available"),
            localizationEntry(
                "update.optional.body",
                "Version {version} is available. You can update now or keep playing.",
            ),
            localizationEntry("update.required.title", "InplaceX must be updated"),
            localizationEntry(
                "update.required.body",
                "Version {version} is required to continue. Download the signed APK from Mirkori Games.",
            ),
            localizationEntry("update.size", "Size: {size}"),
            localizationEntry("update.download", "Download APK"),
            localizationEntry("update.later", "Later"),
            localizationEntry(
                "update.download.unavailable",
                "The browser could not be opened. Check that this device has an app for HTTPS links.",
            ),
        ),
    )
}
