# Mirkori Games Telegram Bot

Бот выдаёт проверенные APK из общего каталога Mirkori Games. Он не собирает
приложения и не принимает произвольные пути от пользователя.

## Безопасность

- токен хранится только в `/etc/mirkori-games-bot.env`;
- по умолчанию скачивание закрыто списком разрешённых Telegram chat ID;
- публичный режим включается только явным
  `MIRKORI_GAMES_PUBLIC_DOWNLOADS=true`;
- перед отправкой бот повторно вычисляет SHA-256 APK и сравнивает его с
  `games.json`;
- путь APK обязан находиться внутри каталога `releases`;
- каталог не содержит токенов, chat ID или иных персональных данных.

## Переменные окружения

```text
MIRKORI_GAMES_TELEGRAM_BOT_TOKEN=<BotFather token>
MIRKORI_GAMES_ALLOWED_CHAT_IDS=<comma separated ids>
MIRKORI_GAMES_PUBLIC_DOWNLOADS=false
```

## Публикация сборки

Для обычного Telegram Bot API файл должен быть меньше 50 МБ. Поэтому для
внутренней раздачи используется минифицированный, подписанный тестовым ключом
вариант (он не является Play-релизом):

```powershell
.\gradlew.bat :app:assembleInternalDistribution
```

Сначала соберите, установите и проверьте APK. Затем на VPS:

```bash
python3 current/publish_release.py \
  --apk /path/to/app-internalDistribution.apk \
  --artifact-root /srv/agent-projects/mirkori-games-bot/releases \
  --catalog /srv/agent-projects/mirkori-games-bot/catalog/games.json \
  --game-id inplacex \
  --title InplaceX \
  --version 0.1.0-debug \
  --notes "Тестовая сборка"

python3 current/bot.py \
  --catalog /srv/agent-projects/mirkori-games-bot/catalog/games.json \
  --artifact-root /srv/agent-projects/mirkori-games-bot/releases \
  --validate-catalog
```

Только после успешной проверки каталога разрешается перезапускать сервис.

## Команды пользователя

- `/start` или `/games` — показать каталог;
- `/inplacex` — сразу скачать текущую сборку InplaceX;
- кнопка под названием игры — скачать выбранный APK.
