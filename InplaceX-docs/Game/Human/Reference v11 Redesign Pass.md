# Reference v11: девять экранов общего shell

## Статус документа

Документ фиксирует реализованный и локально проверенный v11 reference pass по
состоянию на 2026-08-30. Это не означает визуальное утверждение владельцем,
прохождение remote CI или merge.

- Рабочая ветка: `feature/reference-pages-v9`; проверенный code candidate —
  `066dc32e1857662bc0111d20c5c0d00c4f530eb6`.
- Реализация девяти surfaces и 16 artwork resources находится в `1901173d`,
  auth/profile integration — в `173e323f`, финальная геометрия, callbacks и
  truthful-state fixes — в `7122ab32`, а адаптированные online input/recovery
  semantics из PR #96 — в `066dc32e`.
- Общий reference PR: [coindmit-cmyk/InplaceX#99](https://github.com/coindmit-cmyk/InplaceX/pull/99),
  draft, `feature/reference-pages-v9` -> `develop`; base retarget подтверждён
  через GitHub после восстановления соединения.
- До публикации combined chain проверенный remote HEAD PR #99 был `1a07a4b1`.
  Актуальный remote HEAD и CI всегда проверяются в самом PR, а не выводятся из
  этого исторического значения.
- Target crops и provenance находятся в `build/visual-qa/reference-v11-targets/`;
  реальные device captures, normalized target/current/overlay/diff и manifests —
  в `build/visual-qa/reference-v11-device-captures/`.
- Локальная сборка, полный instrumentation на двух телефонах и независимый
  findings-first review завершены. Визуальное утверждение владельца и merge
  остаются отдельными gates.

Скриншоты используются как визуальное свидетельство. Их значения, пользователи,
статусы, покупки и доступность функций не заменяют реальные контракты приложения.

## Duel v12 follow-up (2026-08-31)

Вкладка «Дуэль» повторно сверстана по отдельному owner reference
`D:\tmp\Dmit\codex-clipboard-b45dffb3-4ccb-4b4a-89c2-a61d7b3c82cb.png`
размером `941x1670` (SHA-256
`6FF301324F28B9026CCF752B0C6C28DB9301102C635E80EC5FF5DFEC0E46E891`).
Его зафиксированная цель `duel-target-374x877.png` имеет SHA-256
`6DD99393EC6C57CB2C543F67E8A5B429C025CF4905B38F558204F940ED068489`.

На каноническом холсте измерены HUD `20-75`, hero `100-300`, setup
`310-781`, quick duel `462-570`, training `579-672`, records `681-769` и
navigation `794-872`. Финальные device captures и нормализованные
target/current/side-by-side/overlay/diff находятся в
`build/visual-qa/reference-duel-v12/`; это локальное evidence, а не часть
production APK.

Контролы не подменены макетом: «Быстрая дуэль» вызывает реальный online route
с уже выбранными mode и code length, «Тренировка» вызывает существующий local
bot flow, а «Рекорды» сохраняют disabled semantics, пока у приложения нет
соответствующего destination. Для карточек добавлены отдельные прозрачные
`art_training_target_v12.png` и `art_records_trophy_v12.png`; их происхождение,
alpha-проверка и hashes записаны в локальном `art-provenance.md`.

Финальный APK установлен без очистки данных на Galaxy S24+ и OnePlus 9 Pro.
На каждом устройстве прошли четыре сфокусированных duel regression test и весь
`ShellSectionsSmokeTest`: `OK (52 tests)`. Визуальное утверждение владельцем,
remote CI и merge остаются отдельными gates.

## Локальная проверка реализации

На code candidate `066dc32e` выполнены:

`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`

Результат: `BUILD SUCCESSFUL`. Дополнительно четыре regression instrumentation
test проверили позднюю hydration профиля, exact retry входящего приглашения,
продолжение delayed session read после recomposition и приоритет нового quick
match над старым pending code. App и test APK установлены replacement-командами
без очистки данных `com.mirkori.inplacex`.

| Artifact | SHA-256 |
| --- | --- |
| `app-debug.apk` | `0A2D513140C295037765E6B0D7CD209E91B5BF79D828BB6BA7D2F377F63603ED` |
| `app-debug-androidTest.apk` | `2964B40C3130463B94506A86CCEE53DEDAEDEEB0359E8FAAB7987BFDE39AAF56` |

| Device | API | Screenshot | Verified app crop | Canvas | Instrumentation |
| --- | ---: | --- | --- | --- | --- |
| Galaxy S24+ | 35 | `1080x2340` | `[41,0,1039,2340]` | `374x877` | `OK (94 tests)` |
| OnePlus 9 Pro | 33 | `1080x2412` | `[57,96,1023,2364]` | `374x877` | `OK (94 tests)` |

Normalized visual captures относятся к финальной геометрии `7122ab32`; commit
`066dc32e` меняет online input/recovery и не подменяет эти target comparisons.
Полный shell и реальные маршруты повторно прошли instrumentation на combined
APK. Для игрового экрана используются полные app bounds
(`[0,0,1080,2340]` на S24+ и `[0,96,1080,2364]` на OnePlus), потому что active
game не оборачивается в
фиксированный reference shell. После финального review повторно сняты «Гонка» и
«Приглашения»; системный performance overlay на OnePlus исключён повторным
settled capture.

## Исходники и канонический canvas

Все три исходных montage имеют размер `1491x1055`. Подписи под телефонами и
светлая рамка не входят в crops.

| Montage | SHA-256 |
| --- | --- |
| `D:\tmp\Dmit\codex-clipboard-e5c5e90e-f76e-498e-bdf4-a3e84c3d2384.png` | `ef5aee5f13566e993319e262d21ad57970ab16f8866d10a3115442787af07597` |
| `D:\tmp\Dmit\codex-clipboard-7f4a463a-51c6-4480-9f83-c28a3a9d98f9.png` | `84562a18cefd8532f140acb9137f8d5f5f16be8897ce4ca94aca551e3b4be90e` |
| `D:\tmp\Dmit\codex-clipboard-b751203f-e64e-4249-860e-4ffe946586a8.png` | `b7f00954229b53cbd35273c892fbb5aae2250a5e9c923a40ff5217b938a6ec68` |

Канонический canvas для всех девяти экранов — **374x877**. Координаты crop
заданы как `[left, top, right, bottom]`, правая и нижняя границы не включаются.

| Экран | Montage | Source crop | Crop size | Crop SHA-256 | Target SHA-256 |
| --- | --- | --- | --- | --- | --- |
| Главная | `e5c5...2384.png` | `[19,19,487,972]` | `468x953` | `dc2548238657885c597590ae44ee331727700be8e71f99bb2cf45cb3e9ddac2e` | `57bfa69fa94432014fde3ac85b3c74ccfb6cbaa4cf36583dae21448d5c863f4f` |
| Гонка | `e5c5...2384.png` | `[514,19,975,972]` | `461x953` | `76586649130115e40d1554fc7c4c6a343d0fea6afa7f43ac7ca69de495666a19` | `51eca3355c730ffbe7b207e8d31ff49f74ba34eb7b9d8e3aa58bfc617b0a8161` |
| Дуэль | `e5c5...2384.png` | `[1001,19,1470,972]` | `469x953` | `df0fb0743ae5c52c4b2d531287c53b115464261e410c4075ae7f9beac434de23` | `379df338eb1e7f6296bfac4c9d03750be802f54ba65da29f681964e10563b476` |
| Друзья | `7f4a...98f9.png` | `[38,25,493,972]` | `455x947` | `9e62aea399f435e38c9ed1191da0f7266b46f9286eaa515dd1d8e77f003002f4` | `98028b79bbb2a7d3c20bc464aa7f7b7f1735ef08d47fb28d61c384c605376e15` |
| Приглашения | `7f4a...98f9.png` | `[518,25,976,972]` | `458x947` | `a1c2a14903b03a472277c789766ffac8424fed0284bb858dfa69717f583d9d42` | `8ed0fbcff92b5d6a0bd6bd03191972a3d3c1328096569a35bf513d4beadb59cc` |
| Ввод кода | `7f4a...98f9.png` | `[1000,25,1455,972]` | `455x947` | `d80befd3e01317822e0630c7254d7becf2995d6c5fc282cafcde828021663de4` | `444a61f6c891ca9be81bdb67c145dc34c950534357da14a5355b84002bda0417` |
| Игра | `b751...586a8.png` | `[34,28,506,984]` | `472x956` | `def4b34351aba9cf831a42913001495076974a14ff72a54890cd0674eebe0523` | `0581435b4a24e1ac1daf255a0edcec5a789c3bc7fea4a8bda8ff3cc1cc995320` |
| Premium | `b751...586a8.png` | `[523,27,985,984]` | `462x957` | `f0dd7a29c5d33d849f6e340cdf3d8ac3c0faf86b2e31f06f847881a50c4bb83f` | `aeef161377245eb270ca925f58f77fdbc7d3be646f0c3417856c677b9e49ec93` |
| Premium-товары | `b751...586a8.png` | `[1003,25,1466,984]` | `463x959` | `5220b4d570614b76eba05940c5a7df6a394f15c711633bb9f8cd084046a70ec0` | `722cdcd932ecc7ebcaf601fdc20e0ab42db5a80cb934396c60567bbae97620d7` |

Оба Premium-crop показывают разные вертикальные участки одной прокручиваемой
вкладки. `Premium-товары` не является отдельным destination: после обзора в том
же scroll-контейнере идут notice и реальные billing-карточки. Back из Premium
возвращает на `Запасы`.

Raw crops сохранены как `*-crop.png`, а канонические цели — как
`*-target-374x877.png`. Полные машинные записи находятся в
`targets-manifest.json`. Pixel-level read-only проверка подтвердила, что все
девять raw crops совпадают с указанными прямоугольниками исходных montage, а
crop/target hashes совпадают с manifest.

Исходные phone crops имеют отношение сторон `0.481-0.494`, а установленный
app canvas — около `0.426`. Поэтому текущие v11 targets нормализованы в
`374x877` неравномерным resize. Это явно записанное решение для данного montage,
а не доказательство исходной геометрии физического телефона или pixel identity.
Runtime Compose canvas при этом должен fit-иться равномерно; неравномерное
масштабирование относится только к зафиксированным target images.

## Измеренная геометрия цели

Координаты ниже относятся к каноническому canvas `374x877`.

| Экран | Основные блоки по Y |
| --- | --- |
| Главная | HUD `24-78`; logo `112-202`; cards `226-628`; nav `776-861` |
| Гонка | HUD `24-78`; hero `93-285`; setup `292-748`; nav `773-861` |
| Дуэль | HUD `24-78`; hero `94-292`; setup `300-748`; nav `773-861` |
| Друзья | HUD `24-76`; hero `83-212`; add `221-272`; list `283-754`; nav `773-852` |
| Приглашения | HUD `24-76`; hero `83-214`; setup `221-765`; nav `773-852` |
| Ввод кода | HUD `24-76`; hero `83-224`; form `238-766`; nav `773-852` |
| Игра | score `79-158`; attempts/matrix `167-481`; boosts `487-528`; confidence `534-568`; combination `575-741`; provider banner `752-849` |
| Premium | HUD `24-78`; header `85-180`; reward `193-372`; tabs `382-426`; overview `436-759`; nav `768-851` |
| Premium-товары | HUD `24-78`; products `83-817`; nav overlay `798-858` |

Общие ориентиры: page inset `15-22dp`, радиусы cards `16-20dp`, gaps
`8-13dp`, bottom bar `78-86dp`, headers `28-32sp`, card titles `18-26sp`, body
`14-16sp`. Основные материалы: chrome `#253A52 -> #17283D`, orange
`#FCAF18 -> #D87D04`, purple `#8448B7 -> #4A2991`, green
`#8CB32F -> #447313`, cream около `#FEF1DA`, blue
`#1C6FD2 -> #044795`.

## Truthful deviations

| Область референса | Реальный контракт InplaceX |
| --- | --- |
| HUD `5/5`, `10186` и кнопки пополнения | Показывать фактические energy/wallet values и только реальные store actions. |
| Sample friends, `Онлайн`, `В игре`, `Оффлайн` | Список и relationship берутся из текущего аккаунта. Production call site не передаёт realtime presence, поэтому вымышленные статусы не показываются. Fixture/debug bot не переносится как живой пользователь. |
| Гонка с `6 цифр` | Использовать выбранную длину секрета в разрешённом диапазоне `4..10`; расход энергии и доступность online берутся из runtime. |
| `Тренировка` и `Рекорды` | «Тренировка» запускает существующую локальную Race против `BotSolver`; «Рекорды» disabled и явно помечены как будущий раздел. |
| Дуэль с ботом и online | Использовать существующие bot/online routes; online отключается, если transport недоступен. Отдельная причина сейчас не выводится на duel tile. |
| Экран приглашения с `4 цифры` | Это длина игрового секрета, которую выбирают до активной сессии; она не определяет длину invite code. |
| Ввод шестисимвольного кода | Реальный friend invite содержит ровно `8` нормализованных символов. Поле, ошибка и busy state должны следовать этому контракту. |
| `0/20`, sample timer и пустая таблица игры | Attempts, timer, turn ownership, матрица и доступность submit берутся из `GameField` state. Число `20` из картинки не создаёт новый лимит ходов. |
| Sample boost counts и `+31` | Остатки, entitlement и consumption берутся из inventory/provider state; нулевой запас не превращается в бесплатную подсказку. |
| Встроенный demo ad creative | В production допустимы только loading/error/реальный provider creative; картинка не является рекламным контрактом. |
| Premium cards и `Недоступно` | Product list, provider availability, pending/owned state, цена и entitlement берутся из `BillingState`. |
| `PRO на 1 час` за `60 монет` | Это существующий временный PRO contract: `60` монет за `1` час; remaining time остаётся реальным. |
| Слишком компактные controls игрового экрана | Нажимаемые элементы сохраняют минимум `48dp`, даже если из-за этого несколько рядов выше, чем на montage. |
| Только Google в Profile reference | Реализация сохраняет объединённый раздел «Подключения» с независимыми Mirkori Games и device-local Google actions. |

## Callback matrix

Это обязательная карта привязок для реализации и проверки, а не отметка об уже
пройденных callbacks.

| Surface/control | Real state source | Compose owner | Callback/route | Disabled/variant rule |
| --- | --- | --- | --- | --- |
| HUD energy/add | progress и energy state | shared top bar | существующее energy/store action | product/runtime availability |
| HUD coins/add | wallet balance | shared top bar | существующее shop action | никакого fake balance |
| HUD cart | `AppSection` | shared top bar | `Shop` | nested/game chrome policy |
| HUD settings | `AppSection` | shared top bar | `Settings` | всегда реальный route |
| HUD back | nested route | shared top bar | route-owned back | только на nested screen, target не меньше `48dp` |
| Bottom nav | `AppSection` | shared bottom bar | Home/Friends/Company/Shop/Profile | выбран только активный section |
| Home Race | `HomeScreenState` | `HomeRootScreen` | `RACE_MODES` | enabled |
| Home Duel | `HomeScreenState` | `HomeRootScreen` | `PVP_MODES` | enabled |
| Home Company | `AppSection` | `HomeRootScreen` | `onOpenCompany` | enabled |
| Code length `-`/`+` | selected code length | setup screen | `onCodeLengthChange` | `4..10` |
| Race local | game mode config | `HomeRootScreen` | `PVE_GAME` | только valid config |
| Race online | `OnlineRuntime` | `HomeRootScreen` | online `RACE` | disabled при unavailable runtime |
| Training | local Race state | `HomeRootScreen` | `PVE_GAME` с `BotSolver` | enabled при valid config |
| Records | production route отсутствует | setup screen | none | disabled с явным `coming soon` |
| Duel bot | pre-match state | `HomeRootScreen` | secret dialog -> `PVP_GAME` | только valid config |
| Duel online | `OnlineRuntime` | `HomeRootScreen` | online `TURN_BASED` | disabled при unavailable runtime |
| Add friend | current player/search runtime | `SocialRootScreen` | real search/add dialog | disabled без identity; busy/error |
| Friend play | relationship/runtime | `SocialRootScreen` | `FRIEND_MATCH` | disabled при unavailable runtime |
| Create invite | `OnlineRuntime` | `OnlineDuelScreen` | `createFriendInvite` | busy/error блокирует duplicates |
| Match format | play style | `OnlineDuelScreen` | `RACE`/`TURN_BASED` state | только до active session |
| Invite length | actual invite contract | `OnlineDuelScreen` | фиксированные `8` символов | reference `4/6` не авторитетен |
| Join friend code | input state | `OnlineDuelScreen` | `acceptFriendInvite` | ровно `8` normalized symbols; error/busy |
| Keypad/reset/submit | `GameField` state | `GamePresentationLayout` | существующие input callbacks | rules и turn ownership |
| Hints/boosts | inventory/entitlement | `GameField` | consume/watch-ad callbacks | stock/provider state |
| Confidence | current guess state | `GameField` | существующий confidence callback | доступность по mode |
| Ad banner | provider state | `GameField` | provider callback | никакого demo ad в production |
| Shop tabs | selected category | `ShopRootScreen` | `BOOSTS`/`PREMIUM` | enabled |
| Premium Back | selected category | `ShopRootScreen` | Premium -> Supplies | обзор и товары остаются одним scroll-контейнером |
| Rewarded bonus | ad provider | `ShopRootScreen` | watch rewarded ad | availability/busy/result |
| Premium purchases | `BillingState` | `ShopRootScreen` | существующие buy callbacks | availability/pending/owned |
| Temporary PRO | wallet/entitlement | `ShopRootScreen` | `buyTemporaryPro` | реальные `60` монет / `1` час |

## Asset provenance

V11 artwork получено из одного `4x4` atlas без текста и runtime state:

- source: `C:\Users\Dmit\.codex\generated_images\01a03f06-8070-79a0-89dd-73b45de3e4ae\exec-aa2c84ba-5bcd-4d39-92af-f32b52faf535.png`;
- source size: `1254x1254`, `RGBA`, alpha extrema `[0,255]`;
- tool: built-in `image_gen` edit;
- prompt family: `reference v11 4x4 transparent game UI atlas; text/state excluded`;
- source SHA-256: `38ffb1d9a8d129d40b92a5f631706045c0186327b910fb350a778d5ab52c8e3f`;
- каждый output — `256x256`, `RGBA`, alpha extrema `[0,255]`;
- фактические hashes всех 16 файлов совпали с
  `build/visual-qa/reference-v11-targets/art-provenance-v11.json` во время этой
  документирующей проверки.

| Cell | Resource | SHA-256 |
| ---: | --- | --- |
| 0 | `art_stopwatch_v11` | `2abb1a7972c73556b49700a1f7498702b05644b8db915c1b8845028fd56c7c44` |
| 1 | `art_duel_crest_v11` | `768e028f541b96af1c5c60958f09a18300a5d44514facb8c2aa28b0b3354e1b1` |
| 2 | `art_company_shield_v11` | `1fa812b097134192ef8ba0fcb30b548ee9d7d2ac593761ffbe2e71bca5d86f75` |
| 3 | `art_race_flag_v11` | `52d467ac3e813ec7c58c805fe4ecbe728ce53b6959289fc7bcca774bcf72bafe` |
| 4 | `art_training_target_v11` | `1e175378730bfafbe83d898a1788bda59257fc07474cf92f907ac8c69183cfe0` |
| 5 | `art_records_podium_v11` | `5a817d07e5fae79eb3a001bf0829d04e4070214095bc19a2ebd12318c5262ca0` |
| 6 | `art_friend_bot_v11` | `d997427edba125a2970b8b0409f1c178f039046db8b4dac077cec4299a51b9a8` |
| 7 | `art_online_globe_v11` | `4692b3be1bcf3253cafd6197523b4bb79dd4cb25ad29bd4b3c9fb324eef89a56` |
| 8 | `art_friends_hero_v11` | `6484563cc16f810e9350148b89f49b5b0d1266ed263133e813ceb975609d0fc6` |
| 9 | `art_invite_envelope_v11` | `4b89f41fb3c410961cf75f160dd703abf72418b987b4aed033b9e5f2819d5d77` |
| 10 | `art_join_shield_v11` | `b5171f18c77d45d5093ab8a9ae3a124ef0d33decd69892058d7510d928e4063e` |
| 11 | `art_remove_ads_v11` | `26f8c5e063944fb4f8cc820ba555cfd1ddb2bc6e1336775ec915eb70be8e3dbe` |
| 12 | `art_pro_hour_badge_v11` | `575736890e63df612bdb99949a65bdadb407e8d7e6e2eaf58a6d2898b7f78c03` |
| 13 | `art_pro_badge_v11` | `f86f0735b39a66ae0d9ec32826ee3d585e27734cab336e6a560819fda340fd3c` |
| 14 | `art_pro_plus_badge_v11` | `667a48220c4b8cc3d7b14fb77f68f99c5d8f7bd51922a26ff3abd78c1581108d` |
| 15 | `art_coin_gems_v11` | `4c140eb083960770d3cdbe3651f8dd81d18b0119bad31039a40f1c8a2a9d30f0` |

Ресурсы находятся в `InplaceX-android/app/src/main/res/drawable-nodpi/`. Их
наличие и хеши не означают, что UI уже собран или принят визуально.

Ограничения provenance на текущем checkpoint:

- JSON сохраняет только `prompt_family`, но не полный prompt/edit instruction;
- `art-atlas-v11-cream-preview.png` не описан в provenance JSON;
- `art_coin_gems_v11` существует и проходит hash check, но пока не имеет ссылок
  из `src/main/java`;
- source atlas находится вне репозитория, provenance/preview лежат в
  игнорируемом `build/`; все 16 output assets отслеживаются Git с `1901173d`.

## Политика combined PR #99

Reference scope продолжает существующий PR #99. Новый отдельный reference PR для
v11 не создаётся.

Исходный auth fix `e4f91b7f9593b4126dc4faab7ce0a9c5c96e91c6` из PR #100 не является
предком reference branch. Его поведение вручную интегрировано в combined state
коммитом `173e323f` с разрешением пересечений в `MainActivity.kt`,
`SecondaryCatalog.kt`, `ShellSectionsSmokeTest.kt` и `ProfileRootScreen.kt`.
Это эквивалентная интеграция semantics, а не утверждение, что был cherry-pick
исходного commit.

Friends pilot из PR #98 уже является предком branch chain. Уникальные части PR
#96 адаптированы в `066dc32e`: profile-scoped pending invite, notification route,
confirmed-position input и безопасное восстановление после process recreation.
Это также не verbatim cherry-pick: более новая auth policy из PR #100 сохранена,
а старое разрешение linked LOCAL/TELEGRAM -> Google из PR #96 намеренно не
перенесено.

Проверенный combined candidate сохраняет:

- v11 hierarchy девяти новых surfaces и illustrated Profile composition,
  предназначенную для продолжения PR #99, с truthful state presentation;
- отдельные Mirkori Games и device-local Google actions из auth fix;
- отсутствие ложного server logout при локальном Google sign-out;
- разделённые recovery intents, которые не позволяют старому pending invite
  перехватить новый quick match или вызов другого друга;
- существующую session/refresh semantics и явные ошибки auth;
- реальные callbacks, RU/EN copy и тестовые контракты обеих веток.

При дальнейшем обновлении base повторно применять `e4f91b7f` нельзя: сначала
нужно проверить, не содержит ли base уже `e4f91b7f` или эквивалент `173e323f`,
затем разрешать конфликты по поведению и повторять проверки combined tree.

PR #99 переведён с прежней stacked base `feature/friends-reference-v8` напрямую
на `develop`, при этом Friends commit остаётся в истории branch. Merge и release
требуют существующей owner/integrator authority; этот документ её не
предоставляет.

## Оставшиеся gates

Локальная реализация, сборка, device installation, полный instrumentation,
normalized visual evidence и P0/P1 review завершены. Перед merge остаются
внешние и owner gates:

1. Публиковать combined chain только в существующую ветку PR #99; новый
   reference PR не создавать.
2. Проверить remote CI именно на опубликованном combined HEAD и устранять только
   воспроизводимые findings. Не обходить
   owner/review, protected-path, secret или release gates.
3. Закрывать PR #96, #98 и #100 как superseded только после зелёного CI PR #99 и
   проверки, что их уникальное поведение присутствует в combined history.
4. Получить визуальное утверждение владельца по девяти normalized comparisons.
   Текущие captures доказывают выполненную проверку, но не заменяют acceptance.
5. Не выполнять merge или release без явной owner/integrator authority.

Известные честные отклонения от montage: invite code остаётся восьмисимвольным,
покупки показывают реальную offline/provider error, Profile сохраняет оба вида
подключений, а игровые controls не уменьшаются ниже `48dp`. Presence друзей не
изображается realtime без соответствующего runtime contract.

Остаточные test gaps: System Back не проверен отдельным сценарием внутри уже
активного online-матча; для game layout нет отдельной матрицы `<340dp` плюс
`fontScale > 1.3` на длинах кода `7..10`. Эти gaps не являются визуальным
утверждением и должны учитываться перед release.
