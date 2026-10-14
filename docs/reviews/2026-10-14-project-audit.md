# Ревизия InplaceX — 14 октября 2026

Статус: ревизия завершена, записано **19 замечаний**. Для каждого указано, что воспроизведено на телефоне, а что подтверждено по коду. После ревизии выполнен A01; исправления A02, A03 и связанного теста A15 добавлены в общий [PR #113](https://github.com/coindmit-cmyk/InplaceX/pull/113), ещё не слитый в `develop`. Незавершённый инструментальный UI-прогон Redmi отражён отдельно в A19.

## Проверенная версия

Ревизия относится к **последнему опубликованному `develop`**, проверенному непосредственно в Git: `2263510f158c36fc9fe982628f4c98bfcd5f9f6f`. Дата коммита по времени Бангкока: **12 сентября 2026, 00:06**. Повторная проверка удалённого `develop` во время ревизии дала тот же SHA.

| Источник | Состояние при проверке |
| --- | --- |
| GitHub, `coindmit-cmyk/InplaceX`, `develop` | `2263510f`, актуальная опубликованная основа |
| Локальный рабочий `develop` | `97d3c2f1`, 23 собственных коммита и 257 отсутствующих коммитов относительно опубликованного `develop` |
| Основа сегодняшней локальной интеграции | `71d17028`, 2 августа; свежая дата результата не означает свежую основу |
| Основной checkout на удалённом ПК | `b8680d65`, 30 июля; после fetch — 0 своих / 263 отсутствующих коммита |
| Другой найденный исходный checkout удалённого ПК | `58077179`, 27 июля |

Первичный запуск был ошибочно выполнен из устаревшего локального checkout. Его сообщения об ошибках компиляции, старые экраны, отсутствие интеграций и старый дефект горизонтального режима **не включены в замечания к актуальному `develop`**. Все пункты ниже заново проверены на актуальных исходниках или на APK из них.

Актуальные исходники для проверки сохранены отдельно, без изменения пользовательского рабочего дерева: [снимок develop](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f).

APK: `globalDebug`, пакет `com.mirkori.inplacex`, версия `1.0`, `versionCode=1`. SHA-256:

`6ff1d0cf54ece456e46ec44066ca8b2e96a673ccf6564595563229efdddc57d1`

Один и тот же APK установлен на **Redmi 23108RN04Y / Android 14 / API 34 / 720×1600** и **OnePlus 9 Pro LE2121 / Android 13 / API 33 / 1080×2412** через удалённый ПК и VPS. SHA установленного APK на OnePlus совпал с локальным файлом. Серийные номера устройств в отчёт не включены.

## Общая оценка дизайна и доступности

Главная, выбор режимов, компания, друзья, приглашения, магазин и профиль используют общий визуальный язык: тёплая комната, кремовые панели, иллюстрации, тёмно-синий HUD и нижняя навигация. На обоих телефонах основной shell выглядит согласованно. Перевод основных проверенных маршрутов на английский работает.

Полного единообразия пока нет: отличаются оболочка приватного матча и оформление итогов разных режимов; настройки и часть диалогов остаются в обычном Material-оформлении. На крупном шрифте игровое поле теряет читаемость. Функции «Рекорды» и музыка представлены в интерфейсе, но фактически недоступны в текущей реализации.

| Область | Что проверено | Результат |
| --- | --- | --- |
| Главная и навигация | Оба физических телефона, переходы между разделами | Работают, общий дизайн совпадает |
| Race | Выбор длины, тренировка, ход, поражение от бота, итог | Работает; есть замечания к восстановлению и размерам элементов |
| Duel | Выбор режима/длины, доступность тренировки и онлайна, UI-регрессии | Основные входы реализованы; «Рекорды» отключены |
| Компания | Карта, правила уровня, обучение, запуск, ввод, выход, история/награды в UI-тестах | Работает; состояние активной попытки сбрасывается при пересоздании Activity |
| Приватный онлайн | Код на OnePlus, вход с Redmi, два секрета, живое поле, ход, победа/поражение | Полный матч между двумя телефонами завершён; замечания к shell и статистике |
| Быстрый онлайн | Маршрутизация и recovery в UI/модульных тестах | Проверен код и тестовые сценарии; отдельный живой матч случайного подбора в этой ревизии не завершался |
| Магазин | Категории, карточки, остатки/цены, покупка энергии за монеты | Подтверждена потеря монет при полном запасе энергии |
| Профиль | Гостевой профиль платформы, счётчики, статус покупок, RU/EN | Работает; онлайн-победа не отражается в локальной статистике |
| Настройки | Язык, звук/вибрация/музыка, реклама, ссылки поддержки | Язык не сохраняется после нового запуска; музыка не подключена |
| Масштаб текста | 1.0, 1.3 и 2.0 на Redmi | При 2.0 обрезаются критические игровые значения |
| Поворот | Контракт portrait и manifest | Текущая версия закрепляет portrait; старый landscape-дефект к ней не отнесён |
| Backend, core, SDK, auth, ads, logging, identity | Проектная проверка и выбранные исходники интеграционных границ | Сборочные проверки прошли; подробности ниже |

## Замечания

Приоритеты: **P1** — сначала устранить потерю состояния/неверную основу работы; **P2** — функциональные ошибки, недоступность и существенные UX-проблемы; **P3** — согласование оформления и удобства. «По коду» означает, что вывод подтверждён исходниками, но отдельный аппаратный сценарий для него не выполнялся.

### A01 — P1. Рабочие копии `develop` отстали от опубликованного результата

**Подтверждено Git.** Локальный `develop` и основной checkout удалённого ПК не содержат значительную часть уже слитой разработки. Октябрьские коммиты локальной интеграции построены на августовской основе. Это уже привело к установке старого интерфейса и повторному обнаружению исправленных ранее проблем.

Нужна единая актуальная основа работы и обязательное сравнение `develop` с удалённой веткой перед сборкой/ревизией. При последующей синхронизации необходимо сохранить 23 локальных коммита, текущие незакоммиченные изменения и паузу контроллеров. В этой ревизии рабочая ветка не перебазировалась и не объединялась.

**Исправление A01, 15 октября (задача от 14 октября).** После ревизии оба основных рабочих `develop` приведены к опубликованному `2263510f`; расхождение с соответствующим удалённым `develop` — `0/0` на обоих ПК. Все 23 локальных коммита сохранены в `codex/a01-local-preserved-20261014` (`97d3c2f1`). Тринадцать изменённых файлов приложения/ядра дополнительно сохранены отдельным коммитом `eb6e3ca3` в `codex/a01-preserved-owner-result-20261014`, поверх тех же 23 коммитов. Исходные байты всех локальных изменений, незакоммиченные документы и состояния сохранены в снимках, Git bundle и удерживаемых stash; очереди и локальные инструкции восстановлены побайтно. На удалённом ПК таким же образом сохранены восемь файлов состояния, а исходники обновлены через `--ff-only`.

Общий черновик исправлений начинается от свежего `develop` в `codex/audit-fixes-20261014`. Перенос сохранённого локального кода в актуальный интерфейс будет выполняться по отдельным пунктам с проверками: пробное объединение выявило 13 конфликтов, его снимок сохранён, а сам пробный merge отменён только в новой изолированной копии. Сохранённые локальные изменения пока не вошли в опубликованный `develop`. [Порядок синхронизации и дальнейшей работы](../automation/develop-sync-and-audit.md).

Первый запуск задачи `INPX-AUDIT-A01-20261014` через script/Luna остановился на недействующем пути в системном `codex.cmd`, до получения модельного ответа. После явного указания владельца 15 октября выполнение возобновлено через рабочий CLI в той же задаче. Luna реализовала Git-preflight, девять интеграционных проверок прошли; свежие checkout принимаются, старый сохранённый результат отклоняется. Исходная неудачная попытка, квитанция с неизвестным расходом (`null`) и последующие результаты сохранены. Существующие очереди и паузы не изменялись.

### A02 — P1. Активная локальная игра теряет попытки и таймер при пересоздании Activity

**Воспроизведено на Redmi.** В компании была принята попытка `1234`, счётчик показывал `1/20`, остаток времени — `04:44`. После изменения системного размера шрифта на 1.3 поле стало `0/20`, история очистилась, таймер вернулся к `05:59`. При переходе к 2.0 запуск повторился ещё раз. Это также вызывает повторный учёт старта матча.

Отображаемый экран создаёт `GameFieldViewModel` через `remember` с новым `SavedStateHandle()`. Сохранение самого ViewModel-класса не обеспечивает восстановление экземпляра, которым пользуется этот экран. [GameFieldScreen.kt:82](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/game/GameFieldScreen.kt:82).

Свидетельства: [до](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/campaign-with-attempt.png), [после](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/campaign-after-font130.png).

В сегодняшнем локальном интеграционном результате `97d3c2f1` есть отдельная правка восстановления Activity, но этот результат построен на старой основе и в опубликованный `develop` не вошёл. Её нужно сохранить и перепроверить при последующей интеграции с актуальным интерфейсом; A02 относится именно к проверенному опубликованному `develop`.

**Исправление A02, 15 октября, общий PR #113.** `SavedStateHandle` отображаемого экрана и флаг учёта старта сохраняются через `rememberSaveable`; используется текущая сериализация состояния. После пересоздания восстанавливаются история, частичный ввод, отметки матрицы, секрет и таймер. Новый маршрут с такими же параметрами начинает новую игру. Прежний локальный результат не перезаписывался.

27 выбранных JVM-тестов и сборка app/test APK прошли на AiStudio. Новый UI-тест на OnePlus проверяет реальный экран, пересоздание и новый матч. В обычной компании на обоих телефонах смена шрифта сохранила ход `0123` и `1/20`; таймер Redmi продолжился `03:26 → 01:11`, OnePlus — `04:17 → 02:08`. Шрифт восстановлен к 1.0, данные не очищались. UI-прогон Redmi остановлен по timeout 150 секунд и остаётся ограничением A19. [Redmi до](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A02-20261015/hardware/a02-redmi-before-font.png), [Redmi после](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A02-20261015/hardware/a02-redmi-font-stable.png), [OnePlus до](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A02-20261015/hardware/a02-oneplus-real-before-restore.png), [OnePlus после](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A02-20261015/hardware/a02-oneplus-real-after-restore.png). [Задача и приёмка A02](../automation/tasks/INPX-AUDIT-A02-20261015.md).

### A03 — P2. Покупка энергии при 5/5 списывает монеты без прибавки энергии

**Воспроизведено на Redmi.** При энергии `5/5` покупка `+1` за 25 монет уменьшила баланс с `10000` до `9975`, энергия осталась `5/5`. Магазин показал успешное применение бонуса. Кнопка доступна и при полном запасе.

`buyCampaignEnergy()` сначала списывает монеты, затем ограничивает энергию максимумом. Проверки, что покупка действительно увеличит запас, нет. [GameProgressRepository.kt:317](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/data/local/GameProgressRepository.kt:317).

Свидетельства: [до покупки](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/shop-energy-before-confirmed.png), [после покупки](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/shop-energy-after-purchase.png).

**Исправление A03, 15 октября, PR #113.** Хранилище проверяет свободное место и баланс после восстановления энергии в одной транзакции со списанием. Полный запас возвращает отказ без списания; две покупки последней единицы не могут обе пройти. В магазине действие отключено при полном запасе с причиной «Энергия уже полная» / «Energy is full», в обоих вариантах карточки.

Прошли семь существующих JVM-тестов магазина, сборка app/test APK и по семь новых SQLite-тестов на Redmi и OnePlus. В обычном магазине при `5/5` физическое нажатие отключённой кнопки оставило баланс неизменным: Redmi `9975`, OnePlus `10000`. Данные приложения не очищались. [Redmi после](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A03-20261015/hardware/a03-redmi-verified-after.png), [OnePlus после](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A03-20261015/hardware/a03-oneplus-verified-after.png), [задача и приёмка A03](../automation/tasks/INPX-AUDIT-A03-20261015.md).

### A04 — P2. Завершённый онлайн-PvP не учитывается в статистике профиля

**Воспроизведено между телефонами.** Redmi выиграл приватный матч одним ходом, OnePlus показал поражение. После победы профиль Redmi продолжил показывать `Матчи сыграно: 3` и `PvP: W 0 / L 0` — значения до онлайн-матча.

Онлайн-экран сохраняет указатель сессии и отображает результат, но не связан с `recordMatchStarted()` / `recordModeResult()`. Профиль берёт PvP-счётчики из локального `progressState`; эти счётчики обновляются в локальной дуэли с ботом. [OnlineDuelScreen.kt:402](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/OnlineDuelScreen.kt:402), [ProfileRootScreen.kt:543](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/profile/ProfileRootScreen.kt:543).

Свидетельства: [победа](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/private-result-local.png), [счётчики после победы](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/profile-stats-after-online-win.png).

### A05 — P2. Доступ к авто-таблице зависит от маршрута, а не только от Pro

**По коду и интерфейсу гостевых профилей.** В локальной компании авто-режим показывает заблокированный `PRO`. В приватном онлайн-матче того же гостевого типа он доступен как `Авто`. В локальные режимы передаётся проверка entitlement; в онлайн-вызове этот параметр отсутствует и используется значение `autoModeAvailable=true` по умолчанию.

Нужна единая политика доступа либо явное продуктовое исключение для онлайна. [OnlineDuelScreen.kt:406](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/OnlineDuelScreen.kt:406), [OnlineDuelGameField.kt](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/OnlineDuelGameField.kt).

### A06 — P2. Клавиатура перекрывает кнопку подключения по коду

**Воспроизведено на Redmi, шрифт 1.0.** При вводе кода клавиатура закрывает кнопку `Join game`. Нажатие в её прежнюю область попадает по клавише клавиатуры и может изменить код. Для подключения пришлось вручную закрыть клавиатуру системной кнопкой.

Форма не учитывает IME-вставку; в UI нет `imePadding`/аналогичного механизма для этого маршрута. [форма ввода](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/SocialReferenceNestedScreens.kt:541). [Снимок с перекрытием](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/current-invite-editor-filled.png).

### A07 — P2. Крупный шрифт обрезает критические игровые данные

**Воспроизведено на Redmi, масштаб 2.0.** Обрезаются последние цифры таймеров, заголовки попыток/матрицы, цифры в ячейках, подписи/счётчики бустеров и части цифровой клавиатуры. Поле формально остаётся на экране, но значения трудно различить.

Ряд панелей и ячеек сохраняет фиксированную высоту при увеличении текста. [GamePresentationComponents.kt:1085](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/game/presentation/GamePresentationComponents.kt:1085). [Снимок](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/campaign-font200.png).

### A08 — P2. Выбранный язык не сохраняется после нового запуска

**Воспроизведено на Redmi.** Выбран английский, настройки и игровые маршруты перевелись. После закрытия процесса и нового запуска интерфейс снова русский. Изменения размера шрифта язык сохраняли, новый процесс — нет.

Язык хранится только в `rememberSaveable`, с исходным значением `RU`, и не записывается в постоянные настройки. [MainActivity.kt:265](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/MainActivity.kt:265). [до запуска](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/english-before-relaunch.png), [после](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/language-after-relaunch.png).

### A09 — P2. Переключатель музыки есть, воспроизводимый ресурс не подключён

**По коду.** `AndroidAppFeedbackRuntime` создаётся без музыкального ресурса; его значение по умолчанию — `null`. `synchronizeMusic()` при этом сразу возвращается. Аудиофайл музыки в основном наборе ресурсов не найден. Настройка «Музыка» сохраняется, но подключённого воспроизведения за ней нет.

[AndroidAppFeedbackRuntime.kt:42](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/feedback/AndroidAppFeedbackRuntime.kt:42), [создание в MainActivity.kt:149](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/MainActivity.kt:149). Физическое прослушивание звука/вибрации не было отдельной проверкой.

### A10 — P2. Приватный матч использует оболочку обычной страницы

**По коду и на двух телефонах.** В активной игре через приглашение остаются нижние вкладки и верхний магазин; нормальная игровая кнопка «Назад» в HUD отсутствует. В локальной игре нижняя область занята игровым рекламным слотом, а HUD показывает выход из матча.

`SocialRootScreen` устанавливает `isInGame` только для `FRIEND_MATCH` и `ONLINE_MATCH`, а игра, начатая из `INVITES`, продолжает считаться обычной страницей. Это меняет доступную высоту поля и поведение навигации для одной и той же игровой формы. [SocialRootScreen.kt:140](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/SocialRootScreen.kt:140). [Приватный матч](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/remote-private-now.png).

### A11 — P2. «Рекорды» недоступны; в дуэли причина не объясняется

**По коду и UI-проверкам.** Карточки рекордов Race и Duel отключены и не имеют маршрута. Race честно пишет «Раздел скоро появится». Duel пишет «Ваши лучшие результаты и статистика», хотя открыть их нельзя; причина недоступности не указана, карточка лишь слегка приглушена.

Это незавершённая функция, а не подтверждение, что все пункты меню работают. [ReferenceHomeScreens.kt:346](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/ReferenceHomeScreens.kt:346), [карточка Duel:382](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/ReferenceHomeScreens.kt:382).

### A12 — P2. Часть игровых целей нажатия существенно меньше 48 dp

**Измерено по дереву UI Redmi.** При 720×1600 и плотности 320 dpi область цифровой клавиши имеет ширину около 60 px, то есть **30 dp**, при высоте 48 dp. Ячейки матрицы для четырёх цифр имеют приблизительно **48×25.5 dp**. Одиннадцать клавиш в одной строке и десять строк матрицы создают плотную зону управления; при длине 9–10 цифр матрица ещё мельче.

Высота клавиатуры ограничена 48 dp, а минимальная ширина не обеспечена. Общий токен `FinalUiDimens.MinimumTouchTarget` равен 44 dp, тогда как UX-документы требуют 48 dp для основных действий. [GamePresentationComponents.kt:1491](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/game/presentation/GamePresentationComponents.kt:1491), [FinalUiTokens.kt:80](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/theme/FinalUiTokens.kt:80). Измерения сохранены в [verification-summary.json](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/verification-summary.json).

### A13 — P3. Введённый код приглашения нельзя визуально проверить

**Воспроизведено.** Все восемь символов заменены точками, кнопки показа нет. Пользователь не может увидеть, где опечатался в буквенно-цифровом коде. На клавиатуре при этом сам код может отображаться в подсказке, то есть маскировка в форме не даёт последовательного поведения.

[SocialReferenceNestedScreens.kt:965](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/SocialReferenceNestedScreens.kt:965). Для обсуждения: показывать код обычным текстом либо добавить явный переключатель видимости.

### A14 — P3. Итоги разных режимов оформлены и наполнены по-разному

**Воспроизведено.** Локальная гонка открывает крупный итоговый диалог с попытками, временем и действиями «На главную / Ещё раз». Онлайн-матч возвращает пользователя в страницу «Приглашения», где результат — небольшая карточка с числом ходов и кнопкой «Новый матч». Время завершённого онлайн-матча в этой карточке не показано. Диалоги настроек/обучения также заметно отличаются от иллюстрированных основных страниц.

Нужен согласованный набор итоговых состояний и общих диалогов; разные режимы могут сохранять свои данные, но основные действия и иерархия результата должны быть узнаваемыми. [локальный результат](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/current-race-now.png), [онлайн-результат](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/private-result-local.png), [OnlineDuelScreen.kt:840](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/social/OnlineDuelScreen.kt:840).

### A15 — P2. Тест восстановления проверяет другой экземпляр ViewModel

**По коду и результатам тестов.** `activityRecreationPreservesSecretAttemptsPartialInputAndAnalysis` создаёт отдельный ViewModel через `ViewModelProvider` с ключом `game-field-recreation-test`. Отображаемое игровое поле создаёт свой ViewModel через `remember`. Поэтому существующий тест может пройти одновременно с реальной потерей истории из A02; на OnePlus он прошёл в составе 65 тестов.

Проверка должна охватывать реально запущенный игровой маршрут, введённый через UI ход и состояние после пересоздания. [GameFieldActivityRecreationTest.kt:28](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/androidTest/java/com/mirkori/inplacex/ui/screens/game/state/GameFieldActivityRecreationTest.kt:28).

**Исправление A15 вместе с A02, PR #113.** Тест заменён: debug Activity отображает production `GameFieldScreen`, отдельный ViewModel не создаётся. Проверяются введённый через UI ход, частичный ввод, отметка, таймер, секрет и единственный старт после `ActivityScenario.recreate()`, затем свежая сессия. Один тест прошёл на OnePlus; автоматизированный результат Redmi остаётся незавершённым по A19.

### A16 — P3. В активной компании не видно, какой уровень запущен

**Воспроизведено.** После входа из уровня 1 заголовок игрового поля — просто `Race / Гонка`. Номер миссии, глава и принадлежность к компании не показаны. Во время игры она визуально теряет контекст карты уровней.

Компания передаёт пустой `title` и не задаёт отдельный `modeLabel`. [CompanyRootScreen.kt:123](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/company/CompanyRootScreen.kt:123). [Снимок поля компании](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/campaign-active-before-recreation.png).

### A17 — P3. По номеру версии трудно отличить старую сборку от новой

**Подтверждено метаданными APK и кодом.** Проверенная старая и актуальная сборки обе обозначены `1.0 / versionCode=1`; интерфейс настроек не показывает commit/build ID. Пункт «О приложении» открывает веб-страницу, а не локальную идентичность установленной сборки.

Для debug/QA нужен видимый идентификатор источника сборки. Это позволит быстро сверять телефон с проверяемым `develop`. [version.properties](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/version.properties:1), [SettingsRootScreen.kt:180](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/settings/SettingsRootScreen.kt:180).

### A18 — P2. Определение Race не совпадает с механикой онлайн-игры на время

**Подтверждено кодом, документацией и матчем между телефонами.** Канонический документ описывает Race как разгадывание одного общего секрета. Локальная тренировка действительно использует общий код для игрока и бота. В онлайн-формате `race` оба телефона задали разные секреты, после чего каждый разгадывал секрет соперника; фактически это одновременная дуэль.

Это вопрос согласования продукта и публичных правил: уточнить название/описание этого варианта либо привести механику к определению общей гонки. Не следует автоматически менять транспортное значение `race`. [Game Modes and Rules.md:50](<D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-docs/Game/Human/Game Modes and Rules.md:50>), [DuelMatch.kt:99](D:/Work/DevOps/MobileGame/agent-runtime/project-audit-20261014/develop-2263510f/InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/domain/duel/DuelMatch.kt:99).

### A19 — P2. UI-прогон на Redmi не даёт результата и не ограничен по времени

**Подтверждено тремя запусками.** Набор из 65 тестов остановился на первом `homeCompanyCardChangesGlobalSection`; отдельно повторённый этот тест также не завершился. Отключение анимаций и дополнительный вывод тестовой Activity не дали результата. При диагностике передний план оставался у launcher. Причина запуска/фокуса Activity на этом устройстве требует отдельной проверки; ручные сценарии приложения на Redmi работали.

Зависшие процессы остановлены мной после отсутствия прогресса. `Process crashed` в конце этих логов — следствие этой остановки, а не свидетельство самостоятельного падения игры. Этот прогон **не является PASS**. Для аппаратного runner нужен ограниченный timeout и контроль запуска/состояния тестовой Activity. [первый прогон](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/redmi-instrumentation.log), [отдельный тест](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/redmi-ui-single-test.log), [проверка с выводом Activity](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/redmi-ui-foreground-test.log).

**Повторная проверка 15 октября при A02.** Host-runner уже ограничивает запуск 150 секундами. На Redmi новая тестовая Activity также не вышла на передний план; assertions не завершились. Причина ещё не установлена. Остановлены только собственные тестовые процессы, права безопасности устройства не изменялись. Обычный экран и ручная проверка A02 работают. A19 остаётся открытым. [Результат timeout](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A02-20261015/hardware/redmi-recreation-result.json).

**Уточнение при A03.** Семь инструментальных тестов хранилища прошли на Redmi без запуска UI Activity, те же семь — на OnePlus. Общая instrumentation на Redmi работает; причина зависания запуска/фокуса UI Activity по-прежнему требует отдельной диагностики. [Прогон хранилища Redmi](D:/Work/DevOps/MobileGame/agent-runtime/develop-sync-20261014/INPX-AUDIT-A03-20261015/hardware/redmi-energy-instrumentation.log).

## Проверки и границы покрытия

Проверки выполнялись на рабочей станции. VPS использовался как SSH-маршрут к удалённому ПК; сборка на VPS не выполнялась.

| Проверка актуального develop | Результат |
| --- | --- |
| `verifyProject` + `assembleGlobalDebug` + `lintGlobalDebug` | PASS |
| Модульные тесты debug-проверки | 606 случаев, из них 3 PostgreSQL-теста пропущены; остальные 603 прошли |
| `testGlobalReleaseUnitTest` | 286 / 286 PASS |
| `testRfReleaseUnitTest` | 286 / 286 PASS |
| `assembleGlobalDebugAndroidTest` | PASS |
| Выбранные UI-тесты OnePlus | 65 / 65 PASS |
| Выбранные UI-тесты Redmi | Не завершены: остановка после отсутствия прогресса на первом тесте; подробности A19 |
| Lint | 0 ошибок; 56 warning и 3 hint |
| Два телефона: приватный матч | Создание/вход, секреты, ход, противоположные терминальные результаты — PASS |

Полный машинный список замечаний lint: [develop-lint-findings.json](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/develop-lint-findings.json). В нём сохранены все сообщения, файлы и строки. Среди предупреждений — синхронные записи preferences, ориентация/backup-конфигурация, неиспользуемые ресурсы, сигнатуры composable-компонентов и API, требующие обновления. Эти предупреждения не заменяют приведённые выше пользовательские дефекты.

Сводка проверок: [verification-summary.json](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/verification-summary.json). Логи: [основной прогон](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/develop-verification.log), [release и test APK](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/develop-release-and-ui-test-build.log), [OnePlus](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/oneplus-instrumentation.log), [Redmi](D:/Work/DevOps/MobileGame/InplaceX/build/reports/project-audit-20261014/redmi-instrumentation.log).

Реальные денежные платежи, привязка личного аккаунта владельца, доставка email/Telegram-сообщений, миграция живой PostgreSQL и длительное нагрузочное тестирование в этот аппаратный обход не входили. Их доступность до окончательной приёмки требует отдельного подтверждения. Покупки за игровые монеты проверялись на созданном для ревизии пакете; существующий изолированный пакет прежней QA и его данные не очищались.

Пользовательские изменения в исходном checkout и paused-задачи сохранены. Размер шрифта и временно изменённые масштабы анимаций Redmi возвращены к исходным значениям; тестовые процессы остановлены. Таблица выше описывает исходную ревизию `2263510f`. После неё на оба телефона установлена проверенная сборка A03 из общего PR с SHA-256 `f65a4d339281c064d60d781699a5ae6de3bade0e972d97e0c74f36509acc6fab`; она включает A02. Дополнительные проверки приведены в A02 и A03. Публикация релиза, merge PR и запуск контроллеров не выполнялись.
