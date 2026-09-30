---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/ads/02_provider_strategy_and_regions.md
version: v1
date: 2026-04-16
---

# Provider strategy and regions

## Canonical policy

Нужна multi-provider архитектура.

## Providers

### admob
Используется как основной global provider.

### yandex
Используется как:
- RU primary
- CIS fallback
- optional alternative if global config says so

### noop
Используется когда:
- реклама отключена
- provider недоступен
- страна не поддерживается
- сеть плохая

## Remote config contract

Backend должен отдавать ad policy.

Example:
```json
{
  "adsPolicy": {
    "placements": {
      "hintPosition": {
        "providers": [
          { "id": "admob", "regions": ["GLOBAL"], "stores": ["google_play"] },
          { "id": "yandex", "regions": ["RU","CIS"], "stores": ["google_play","rustore"] },
          { "id": "noop", "regions": ["*"], "stores": ["*"] }
        ]
      }
    }
  }
}
```

## Region strategy

### Global
- first try `admob`
- fallback `yandex` if configured
- final fallback `noop`

### RU
- first try `yandex`
- optional store-specific behavior
- if cloud/account policy limited, ads still should work independently

## Build strategy

### MVP
Один APK/AAB с несколькими provider implementations внутри.
Выбор провайдера — только через config.

### Later
Можно добавить product flavors:
- `globalPlay`
- `ruStore`

Но не делать это раньше необходимости.

## Network failure strategy

Ads module должен различать:
- no fill
- no network
- timeout
- provider internal error
- user cancelled

Эти причины нужны для аналитики и UX.
