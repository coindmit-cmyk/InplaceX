package com.mirkori.inplacex.platform.localization

internal object UpdateCatalog {
    val ru = localizationCatalog(
        name = "sideload_update.ru",
        entries = listOf(
            localizationEntry("sideload_update.optional.title", "Доступно обновление"),
            localizationEntry(
                "sideload_update.optional.body",
                "Доступна версия {version}. Можно обновить сейчас или продолжить игру.",
            ),
            localizationEntry("sideload_update.required.title", "Нужно обновить InplaceX"),
            localizationEntry(
                "sideload_update.required.body",
                "Версия {version} обязательна для продолжения. Игра скачает и проверит подписанный APK Mirkori Games.",
            ),
            localizationEntry("sideload_update.size", "Размер: {size}"),
            localizationEntry("sideload_update.download", "Скачать и проверить"),
            localizationEntry("sideload_update.allow_installation", "Разрешить установку"),
            localizationEntry("sideload_update.install", "Установить"),
            localizationEntry("sideload_update.retry", "Повторить"),
            localizationEntry("sideload_update.later", "Позже"),
            localizationEntry("sideload_update.status.downloading", "APK скачивается в защищённый кэш игры…"),
            localizationEntry("sideload_update.status.verifying", "Проверяем файл, версию и подпись…"),
            localizationEntry("sideload_update.status.refreshing", "Проверяем актуальность обновления…"),
            localizationEntry("sideload_update.status.handing_off", "Открываем системную установку…"),
            localizationEntry("sideload_update.status.permission", "Разрешите InplaceX устанавливать обновления из этого источника."),
            localizationEntry("sideload_update.status.ready", "APK проверен и готов к системной установке."),
            localizationEntry("sideload_update.status.unsupported", "Эта версия требует более новую версию Android."),
            localizationEntry("sideload_update.status.recoverable_error", "Проверку не удалось завершить. Можно безопасно повторить."),
            localizationEntry("sideload_update.status.policy_recovery", "Состояние обязательного обновления нужно подтвердить через Mirkori Games."),
            localizationEntry("sideload_update.policy_recovery.body", "До успешной проверки каталога продолжить игру нельзя."),
        ),
    )

    val en = localizationCatalog(
        name = "sideload_update.en",
        entries = listOf(
            localizationEntry("sideload_update.optional.title", "Update available"),
            localizationEntry(
                "sideload_update.optional.body",
                "Version {version} is available. You can update now or keep playing.",
            ),
            localizationEntry("sideload_update.required.title", "InplaceX must be updated"),
            localizationEntry(
                "sideload_update.required.body",
                "Version {version} is required to continue. The game will download and verify the signed Mirkori Games APK.",
            ),
            localizationEntry("sideload_update.size", "Size: {size}"),
            localizationEntry("sideload_update.download", "Download and verify"),
            localizationEntry("sideload_update.allow_installation", "Allow installation"),
            localizationEntry("sideload_update.install", "Install"),
            localizationEntry("sideload_update.retry", "Retry"),
            localizationEntry("sideload_update.later", "Later"),
            localizationEntry("sideload_update.status.downloading", "Downloading the APK into the app's protected cache…"),
            localizationEntry("sideload_update.status.verifying", "Verifying the file, version, and signer…"),
            localizationEntry("sideload_update.status.refreshing", "Checking whether this update is still current…"),
            localizationEntry("sideload_update.status.handing_off", "Opening the system installer…"),
            localizationEntry("sideload_update.status.permission", "Allow InplaceX to install updates from this source."),
            localizationEntry("sideload_update.status.ready", "The APK is verified and ready for the system installer."),
            localizationEntry("sideload_update.status.unsupported", "This release requires a newer Android version."),
            localizationEntry("sideload_update.status.recoverable_error", "Verification could not finish. It is safe to retry."),
            localizationEntry("sideload_update.status.policy_recovery", "The mandatory update state must be confirmed with Mirkori Games."),
            localizationEntry("sideload_update.policy_recovery.body", "The game remains blocked until the catalog is checked successfully."),
        ),
    )
}
