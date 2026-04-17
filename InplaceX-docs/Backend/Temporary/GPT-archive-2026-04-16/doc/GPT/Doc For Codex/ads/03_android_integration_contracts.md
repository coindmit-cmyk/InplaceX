---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/ads/03_android_integration_contracts.md
version: v1
date: 2026-04-16
---

# Android integration contracts

## App-layer dependencies

UI слой знает только про `RewardedAdUseCase`.

Пример:
```kotlin
class ShowHintByAdUseCase(
    private val rewardedAdUseCase: RewardedAdUseCase
) {
    suspend operator fun invoke(): RewardGrantResult {
        return rewardedAdUseCase.showForPlacement(RewardedPlacement.hintPosition)
    }
}
```

## Repository contracts

### RewardSessionRepository
```kotlin
interface RewardSessionRepository {
    suspend fun createSession(
        placement: RewardedPlacement,
        rewardKind: RewardKind,
        rewardAmount: Int
    ): RewardSession

    suspend fun markClientComplete(rewardSessionId: String)

    suspend fun getStatus(rewardSessionId: String): RewardSessionStatus
}
```

### RewardSession model
```kotlin
data class RewardSession(
    val rewardSessionId: String,
    val provider: String,
    val expiresAt: Instant,
    val providerPayload: Map<String, String> = emptyMap()
)
```

## Final UI result model

```kotlin
sealed interface RewardGrantResult {
    data class Granted(
        val rewardKind: RewardKind,
        val rewardAmount: Int
    ) : RewardGrantResult

    data object Cancelled : RewardGrantResult
    data class Failed(val reason: String) : RewardGrantResult
}
```

## Screen behavior

Если result:
- `Granted` -> обновить UI state
- `Cancelled` -> ничего не выдавать
- `Failed` -> показать controlled message

## Consent note

Если используется provider, требующий consent flow в отдельных регионах, это должно решаться до показа рекламы.
Но сам `RewardedAdUseCase` не должен знать деталей consent SDK.
