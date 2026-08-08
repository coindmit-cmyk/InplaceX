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
                "Версия {version} обязательна для продолжения. Игра скачает и проверит подписанный APK Mirkori Games.",
            ),
            localizationEntry("update.size", "Размер: {size}"),
            localizationEntry("update.download", "Скачать и проверить"),
            localizationEntry("update.allow_installation", "Разрешить установку"),
            localizationEntry("update.install", "Установить"),
            localizationEntry("update.retry", "Повторить"),
            localizationEntry("update.later", "Позже"),
            localizationEntry("update.status.downloading", "APK скачивается в защищённый кэш игры…"),
            localizationEntry("update.status.verifying", "Проверяем файл, версию и подпись…"),
            localizationEntry("update.status.refreshing", "Проверяем актуальность обновления…"),
            localizationEntry("update.status.handing_off", "Открываем системную установку…"),
            localizationEntry("update.status.permission", "Разрешите InplaceX устанавливать обновления из этого источника."),
            localizationEntry("update.status.ready", "APK проверен и готов к системной установке."),
            localizationEntry("update.status.unsupported", "Эта версия требует более новую версию Android."),
            localizationEntry("update.status.recoverable_error", "Проверку не удалось завершить. Можно безопасно повторить."),
            localizationEntry("update.status.policy_recovery", "Состояние обязательного обновления нужно подтвердить через Mirkori Games."),
            localizationEntry("update.policy_recovery.body", "До успешной проверки каталога продолжить игру нельзя."),
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
                "Version {version} is required to continue. The game will download and verify the signed Mirkori Games APK.",
            ),
            localizationEntry("update.size", "Size: {size}"),
            localizationEntry("update.download", "Download and verify"),
            localizationEntry("update.allow_installation", "Allow installation"),
            localizationEntry("update.install", "Install"),
            localizationEntry("update.retry", "Retry"),
            localizationEntry("update.later", "Later"),
            localizationEntry("update.status.downloading", "Downloading the APK into the app's protected cache…"),
            localizationEntry("update.status.verifying", "Verifying the file, version, and signer…"),
            localizationEntry("update.status.refreshing", "Checking whether this update is still current…"),
            localizationEntry("update.status.handing_off", "Opening the system installer…"),
            localizationEntry("update.status.permission", "Allow InplaceX to install updates from this source."),
            localizationEntry("update.status.ready", "The APK is verified and ready for the system installer."),
            localizationEntry("update.status.unsupported", "This release requires a newer Android version."),
            localizationEntry("update.status.recoverable_error", "Verification could not finish. It is safe to retry."),
            localizationEntry("update.status.policy_recovery", "The mandatory update state must be confirmed with Mirkori Games."),
            localizationEntry("update.policy_recovery.body", "The game remains blocked until the catalog is checked successfully."),
        ),
    )
}
