---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/ads/01_ads_module_overview.md
version: v1
date: 2026-04-16
---

# Ads module overview

## Goal

Сделать рекламный блок отдельным от UI и от reward business logic.

## Canonical package shape

```text
app/src/main/java/com/mirkori/inplacex/ads/
  api/
    RewardedPlacement.kt
    RewardKind.kt
    RewardedAdResult.kt
    RewardedAdsGateway.kt
  core/
    AdsRouter.kt
    RewardedAdUseCase.kt
    RewardSessionRepository.kt
  providers/
    admob/
    yandex/
    noop/
```

## Required abstractions

### RewardedPlacement
Enum-like type:
- `hintPosition`
- `hintDigitCount`
- `extraCurrency`
- `continueLevel`

### RewardedAdResult
Не использовать просто `Boolean`.

Canonical shape:
```kotlin
sealed interface RewardedAdResult {
    data object Completed : RewardedAdResult
    data object Cancelled : RewardedAdResult
    data class Failed(val reason: String) : RewardedAdResult
}
```

### RewardedAdsGateway
SDK-facing interface:
```kotlin
interface RewardedAdsGateway {
    suspend fun preload(placement: RewardedPlacement)
    suspend fun isAvailable(placement: RewardedPlacement): Boolean
    suspend fun show(
        placement: RewardedPlacement,
        rewardSessionId: String
    ): RewardedAdResult
}
```

### AdsRouter
Выбирает provider по:
- app config
- region
- store
- availability

### RewardedAdUseCase
Бизнес-оркестратор:
1. create reward session on backend
2. choose provider
3. show ad
4. notify backend about client completion
5. poll reward session status
6. return final grant result

## Hard rules

- UI не работает напрямую с AdMob/Yandex SDK
- ad callback не меняет баланс подсказок напрямую
- награда выдаётся только после backend result
- provider selection не должен быть hardcoded в screen

## Fallback policy

Если рекламы нет:
- вернуть controlled failure
- не ломать экран
- не выдавать награду
