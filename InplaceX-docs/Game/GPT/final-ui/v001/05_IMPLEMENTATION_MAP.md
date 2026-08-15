# Implementation Map

Проверено против `develop@5f37f4e138f96cdf70c489237b28137a351b3892`.

## 1. Архитектурная граница

`GameFieldScreen` и `OnlineDuelGameField` уже адаптируют состояние к общему stateless `GameScreen`. Финальный UI реализуется на presentation boundary, без переноса логики.

## 2. Новые файлы

Рекомендуемые пути:

```text
InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/theme/FinalUiTokens.kt
InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/common/FinalUiPrimitives.kt
```

Исходники находятся в `code/` этого пакета.

## 3. Gameplay vertical slice

### Основной файл

`ui/screens/game/presentation/GamePresentationComponents.kt`

Изменения по функциям:

- `GamePresentationLayout`: использовать breakpoint `codeLength > 6` и единый 4dp spacing; 4–6 остаются side-by-side, 7–10 становятся stacked.
- `PresentationCard`: заменить реализацией `WarmPanel` или удалить wrapper, где он создаёт лишнюю вложенность.
- `GameTopPanel`: заменить nested `GameInfoChip` surfaces на info strip + dividers.
- `GameAttemptsPanel`: передавать structured attempts; формат `guess → score`.
- `GameAttemptList`: использовать `CompactAttemptRow`, сохранить auto-scroll и tags.
- `GameAnalysisPanel`: во всех режимах считать ширину и высоту cell независимо, ограничивать высоту токеном по длине кода; digit остаётся в cell.
- `GameHelpersPanel`: compact counters.
- `GameToolsPanel`: segmented control.
- `GameInputPanel`: adaptive slot/keypad metrics и final buttons.
- `analysisVisualFor` и domain mapping не менять по смыслу; меняется только visual mapping.

### Не трогать

```text
ui/screens/game/GameFieldScreen.kt
ui/screens/game/state/**
ui/viewmodel/GameFieldViewModel.kt
core/**
data/**
platform/online/**
```

## 4. Shell

- `ui/shell/AppTopBar.kt`: токены chrome, одна тень, менее агрессивная обводка.
- `ui/shell/AppBottomMenu.kt`: те же tokens, compact selected state.
- `ui/shell/AppBottomAd.kt`: убрать фиолетовый production frame; debug placeholder отделить.
- `ui/shell/AppShell.kt`: геометрию менять только при доказанном clipping; transparent center и layer modes сохранить.
- `ui/layout/UiLayoutConfig.kt`: при необходимости только tokenized values, без изменения slot semantics.

## 5. Shared scene layer

`ui/screens/shared/SceneChrome.kt`

- `SceneCard` → warm panel system.
- `SceneActionTile` → `PolishedActionTile`.
- `SceneBadge` → compact warm badge.
- Сохранить signatures, semantics и call sites, где возможно.

## 6. Home

`ui/screens/home/HomeRootScreen.kt`

- `HomeSelectionScreen` не менять по flow.
- Обновить logo readability, spacing и action tile calls.
- Не создавать четвёртую карточку.
- `PveModesScreen.kt` и `PvpModesScreen.kt` приводятся к тем же primitives без изменения выбора режимов.

## 7. Company

Файлы:

```text
ui/screens/company/CompanyHeaderComponents.kt
ui/screens/company/CompanyMissionTimeline.kt
ui/screens/company/CompanyActionBar.kt
ui/screens/company/CompanySceneScreen.kt
ui/screens/company/CampaignHistoryScreen.kt
ui/screens/company/CampaignTutorialDialog.kt
```

Изменять presentation only. `CompanyCampaignLogic.kt`, progression/domain правила и repository calls не менять.

## 8. Social

Файлы:

```text
ui/screens/social/SocialRootScreen.kt
ui/screens/social/OnlineDuelScreen.kt
```

Допустимо менять Compose markup внутри этих файлов, но:

- network calls, polling, session state, invite normalization и callbacks не менять;
- `OnlineDuelGameField.kt` трогать только при необходимости layout wrapper, общий renderer уже используется;
- `platform/online/**` запрещён.

## 9. Remaining screens

```text
ui/screens/shop/ShopRootScreen.kt
ui/screens/profile/ProfileRootScreen.kt
ui/screens/settings/SettingsRootScreen.kt
ui/screens/settings/AdPrivacyConsentDialog.kt
```

Только visual consistency pass.

## 10. Theme

- `Color.kt`: существующие публичные colors не удалять; новые final tokens держать отдельно.
- `Theme.kt`: глобальную light scheme менять минимально, чтобы не вызвать непредсказуемый cascade.
- `Type.kt`: не менять глобальные размеры радикально. Dense gameplay styles задавать локально через tokens.

## 11. Тесты

Обновить/добавить:

```text
src/test/.../ui/screens/game/presentation/GamePresentationComponentsTest.kt
src/androidTest/.../ui/screens/game/GameFieldValidationTest.kt
src/androidTest/.../ui/screens/game/GameLocalizationSmokeTest.kt
src/androidTest/.../ui/screens/shell/ShellSectionsSmokeTest.kt
src/test/.../ui/screens/home/**
src/test/.../ui/screens/company/**
src/test/.../ui/screens/social/**
```

Проверять contract/semantics и bounds, а не private composable implementation.

## 12. Commit strategy

1. Tokens + primitives + tests.
2. Gameplay 4/6/8/10.
3. Shell + Home.
4. Company + Social.
5. Shop/Profile/Settings + final QA.

После commit 2 нужен owner screenshot review до массового переноса стиля.
