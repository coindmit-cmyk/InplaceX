# Mirkori Games Telegram Bot

Бот выдаёт HTTPS-ссылки на проверенные APK из общего каталога Mirkori Games.
Он не собирает приложения, не загружает APK через Telegram и не принимает
произвольные пути или URL от пользователя.

## Безопасность

- токен хранится только в `/etc/mirkori-games-bot.env`;
- по умолчанию скачивание закрыто списком разрешённых Telegram chat ID;
- публичный режим включается только явным
  `MIRKORI_GAMES_PUBLIC_DOWNLOADS=true`;
- перед выдачей ссылки бот повторно вычисляет SHA-256 APK и сравнивает его с
  `games.json`;
- путь APK обязан находиться внутри каталога `downloads`;
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
manifest должен совпадать с owner policy. Затем на VPS передайте APK именно из
этого immutable-каталога:

```bash
python3 current/publish_release.py \
  --apk /path/to/release-candidates/<releaseId>/InplaceX-<version>-<versionCode>.apk \
  --artifact-root /srv/agent-projects/mirkori-games-bot/downloads \
  --catalog /srv/agent-projects/mirkori-games-bot/catalog/games.json \
  --game-id inplacex \
  --title InplaceX \
  --version <version> \
  --notes "Ограниченный owner-signed rollout" \
  --download-url https://inplacex.dmit.life/downloads/InplaceX.apk

python3 current/bot.py \
  --catalog /srv/agent-projects/mirkori-games-bot/catalog/games.json \
  --artifact-root /srv/agent-projects/mirkori-games-bot/downloads \
  --validate-catalog
```

Только после успешной проверки каталога разрешается перезапускать сервис.

Nginx публикует только один стабильный путь
`https://inplacex.dmit.life/downloads/InplaceX.apk`. Конфигурация находится в
`inplacex-downloads.nginx.conf`; directory listing и произвольные пути не
включаются.

## Команды пользователя

- `/start` или `/games` — показать каталог;
- `/inplacex` — получить ссылку на текущую сборку InplaceX;
- кнопка под названием игры — открыть HTTPS-скачивание APK.
