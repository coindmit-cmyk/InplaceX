package com.mirkori.inplacex.platform.localization

private class MapLocalizationProvider(
    private val values: Map<String, String>
) : LocalizationProvider {
    override fun text(key: String): String = values[key] ?: key
}

object StaticLocalizationProvider {
    fun forLanguage(language: AppLanguage): LocalizationProvider {
        return when (language) {
            AppLanguage.RU -> MapLocalizationProvider(ruPack)
            AppLanguage.EN -> MapLocalizationProvider(enPack)
        }
    }

    private val ruPack = mapOf(
        "top.back" to "Назад",
        "top.settings" to "Настройки",
        "top.energy" to "Энергия",
        "top.coins" to "Монеты",
        "section.home.title" to "Главная",
        "section.home.short" to "Главная",
        "section.home.reserve" to "Нижний резерв: баннер / акция / быстрый вход",
        "section.social.title" to "Игры с друзьями / Онлайн",
        "section.social.short" to "Друзья",
        "section.social.reserve" to "Нижний резерв: реклама / комната / онлайн-событие",
        "section.company.title" to "Компания",
        "section.company.short" to "Компания",
        "section.company.reserve" to "Нижний резерв: баннер компании / прогресс / особые цели",
        "section.shop.title" to "Магазин",
        "section.shop.short" to "Магазин",
        "section.shop.reserve" to "Нижний резерв: оффер / премиум / акция магазина",
        "section.profile.title" to "Профиль",
        "section.profile.short" to "Профиль",
        "section.profile.reserve" to "Нижний резерв: premium / статус / сервисная зона",
        "mode.pve.title" to "PvE игра",
        "mode.pve.subtitle" to "Игра с ботом на общем матчевом движке",
        "mode.pvp.title" to "PvP игра",
        "mode.pvp.subtitle" to "Локальная дуэль как отдельный opponent provider",
        "home.title" to "Главный экран",
        "home.subtitle" to "Вход сразу в игровые режимы через центральный конфиг",
        "social.title" to "Игры с друзьями / Онлайн",
        "social.friends" to "С друзьями",
        "social.online" to "Онлайн",
        "shop.title" to "Магазин",
        "shop.hints" to "Подсказки",
        "shop.premium" to "Премиум",
        "company.title" to "Компания",
        "company.placeholder" to "Экран-заглушка под одиночное прохождение, прогресс и набор сценариев.",
        "company.list" to "Уровни компании",
        "settings.title" to "Настройки",
        "settings.description" to "Здесь настраиваются язык, звук и системные параметры оболочки.",
        "settings.language" to "Язык",
        "settings.language.ru" to "Русский",
        "settings.language.en" to "Английский",
        "settings.language.selector" to "Выбор языка",
    )

    private val enPack = mapOf(
        "top.back" to "Back",
        "top.settings" to "Settings",
        "top.energy" to "Energy",
        "top.coins" to "Coins",
        "section.home.title" to "Home",
        "section.home.short" to "Home",
        "section.home.reserve" to "Bottom reserve: banner / promo / quick entry",
        "section.social.title" to "Friends / Online",
        "section.social.short" to "Friends",
        "section.social.reserve" to "Bottom reserve: ad / room / online event",
        "section.company.title" to "Company",
        "section.company.short" to "Company",
        "section.company.reserve" to "Bottom reserve: company banner / progress / goals",
        "section.shop.title" to "Shop",
        "section.shop.short" to "Shop",
        "section.shop.reserve" to "Bottom reserve: offer / premium / shop promo",
        "section.profile.title" to "Profile",
        "section.profile.short" to "Profile",
        "section.profile.reserve" to "Bottom reserve: premium / status / service zone",
        "mode.pve.title" to "PvE Game",
        "mode.pve.subtitle" to "Bot match on the shared match engine",
        "mode.pvp.title" to "PvP Game",
        "mode.pvp.subtitle" to "Local duel as a separate opponent provider",
        "home.title" to "Home Screen",
        "home.subtitle" to "Jump straight into game modes through the central config",
        "social.title" to "Friends / Online",
        "social.friends" to "Friends",
        "social.online" to "Online",
        "shop.title" to "Shop",
        "shop.hints" to "Hints",
        "shop.premium" to "Premium",
        "company.title" to "Company",
        "company.placeholder" to "Placeholder screen for solo progression, milestones and curated scenarios.",
        "company.list" to "Company Levels",
        "settings.title" to "Settings",
        "settings.description" to "Use this panel for language, sound and shell system settings.",
        "settings.language" to "Language",
        "settings.language.ru" to "Russian",
        "settings.language.en" to "English",
        "settings.language.selector" to "Select language",
    )
}
