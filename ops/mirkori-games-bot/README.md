# Mirkori Games Telegram Bot

Бот выдаёт HTTPS-ссылки на проверенные APK из общего каталога Mirkori Games.
Он не собирает приложения, не загружает APK через Telegram и не принимает
произвольные пути или URL от пользователя.

Production-бот читает тот же активный каталог Mirkori Games Platform, что сайт
и Android update API. Для каждой игры он предпочитает последний `stable`
Android-релиз, а при его отсутствии показывает последний `beta`. Поэтому сайт
и Telegram всегда ведут на один release ID, APK и SHA-256.

## Безопасность

- токен хранится только в `/etc/mirkori-games-bot.env`;
- по умолчанию скачивание закрыто списком разрешённых Telegram chat ID;
- публичный режим включается только явным
  `MIRKORI_GAMES_PUBLIC_DOWNLOADS=true`;
- перед выдачей ссылки бот повторно вычисляет SHA-256 APK и сравнивает его с
  активным Platform `catalog.json`;
- путь APK обязан находиться внутри Platform `artifacts`;
- URL обязан вести на разрешённый HTTPS-домен и путь `/downloads/*.apk`;
- каталог не содержит токенов, chat ID или иных персональных данных.

## Переменные окружения

```text
MIRKORI_GAMES_TELEGRAM_BOT_TOKEN=<BotFather token>
MIRKORI_GAMES_ALLOWED_CHAT_IDS=<comma separated ids>
MIRKORI_GAMES_PUBLIC_DOWNLOADS=false
```

## Публикация сборки

`internalDistribution` является намеренно unsigned-проверкой и не публикуется:

```powershell
.\gradlew.bat :app:assembleInternalDistribution
```

Для установки на локальные тестовые устройства используйте debug APK. Для
каталога и Telegram-бота допускается только owner-signed production candidate,
созданный из чистого checkout отдельной задачей:

```powershell
.\gradlew.bat :app:releaseCandidate
```

Перед публикацией проверьте точный каталог
`build/release-candidates/<releaseId>`: в нём должны быть ровно APK, identity
manifest, SHA-256, отчёт `apksigner` и APK metadata. Отпечаток сертификата в
manifest должен совпадать с owner policy. После активации Platform catalog
отдельная публикация для Telegram не нужна. Проверьте тот же активный каталог:

```bash
python3 current/bot.py \
  --platform-catalog /srv/mirkori-games-platform/catalog/current/catalog.json \
  --artifact-root /srv/mirkori-games-platform/catalog/current/artifacts \
  --validate-catalog
```

Только после успешной проверки каталога разрешается перезапускать сервис.

Кнопка использует immutable HTTPS URL, сформированный из Platform release ID и
точного имени APK. Legacy `publish_release.py`, `games.example.json` и старый
путь `inplacex.dmit.life/downloads/InplaceX.apk` сохраняются только для
локальной совместимости и не являются production-источником.

## Команды пользователя

- `/start` или `/games` — показать каталог;
- `/inplacex` — получить ссылку на текущую сборку InplaceX;
- кнопка под названием игры — открыть HTTPS-скачивание APK.
