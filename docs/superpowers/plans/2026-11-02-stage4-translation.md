# Этап 4: перевод субтитров en ↔ ru — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Статус:** задачи 1–7 выполнены 2026-11-02 (`c6891c8`..`92bf3db` + документы). Gate пройден условно; приёмка на ТВ — по отчёту пользователя; офлайн-перевод не проверен. Чекбоксы шагов ниже не отмечались по отдельности; авторитетный статус — этот абзац, STATUS и протоколы.

**Goal:** ручной перевод текста одного слоя субтитров в другой (en ↔ ru) на устройстве через ML Kit, с кэшем Room для скачанных субтитров.

**Architecture:** новый пакет `translate`: интерфейс `SubtitleTranslator` над ML Kit, менеджер моделей, Room-кэш и два планировщика — фоновый для полного `SubtitleTrack` и on-the-fly для встроенных cue. `PlayerViewModel` получает новый источник слоя `LayerSource.Translated` и отдаёт его текст через существующий `textAt`/оверлей.

**Tech Stack:** Kotlin, Hilt, coroutines, ML Kit Translate (`com.google.mlkit:translate`), Room + KSP, Media3 (без изменений), JUnit4/Robolectric, Docker Gradle.

**Spec:** [дизайн этапа 4](../specs/2026-11-02-stage4-translation-design.md); общая [спека](../specs/2026-10-29-android-tv-torrent-gallery-design.md).

## Global Constraints

- Только пары en → ru и ru → en. Модели не входят в APK.
- ABI `armeabi-v7a` не менять; ML Kit должен иметь `.so` для него.
- Модель качается только по кнопке в настройках или при выборе перевода в меню.
- Сеть, ML Kit, Room — не на UI-потоке. `CancellationException` не поглощать; отмена отменяет `Task`/запись.
- Кэш Room: ключ `(fileId, targetLang, modelVersion)`, предел 50 МБ, LRU. Секреты не хранить. Миграций `SecretStore`/`PlaybackStore` нет.
- Ошибки не останавливают воспроизведение; слой пуст с причиной (тексты из таблицы ошибок спеки).
- Ручной выбор перевода не сохраняется между просмотрами. Цепочки «перевод из перевода» запрещены.
- Пользовательские строки — на русском, комментарии — кратко на английском, UTF-8 без BOM.
- Тесты: `./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest --tests '<pattern>'`; перед завершением — `./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest assembleDebug` и проверка XML в `app/build/test-results/testDebugUnitTest/`.
- Версии ML Kit и Room проверить по официальной документации (context7/maven) на дату задачи; выбрать стабильные, совместимые с AGP 9.5.0, Kotlin 2.4.21, KSP 2.3.12.

## Review Focus

1. Seek во время фонового перевода: очередь переставляется от новой позиции, уже переведённые cue не переводятся заново. → тест в задаче 4.
2. Смена источника у слоя-источника во время перевода: поздний результат старого источника не попадает в слой. → тест в задаче 5.
3. Выход из плеера при скачивании модели: загрузка ML Kit продолжается в фоне (общая для приложения), но VM не пишет состояние после `onCleared`. → тест в задаче 5.
4. Пустые/многострочные/с тегами cue: пустой текст не отправляется в переводчик, переносы строк сохраняются. → тест в задаче 4.
5. Повреждённая/недоступная БД кэша: перевод идёт без кэша, фильм играет. → тест в задаче 3.

---

### Task 1: Gate — ML Kit на ТВ (блокирующий)

Решение пользователя: если gate не пройден, этап 4 откладывается; запасной путь (ручной импорт модели, VPN) не делается.

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` — зависимость `com.google.mlkit:translate`.
- Create: `app/src/debug/java/dev/gamspi/videogallery/translate/TranslateProbeActivity.kt` (+ регистрация в `app/src/debug/AndroidManifest.xml` по образцу `CodecProbeActivity`).
- Create: `docs/verification/2026-11-02-translate-gate.md`.

- [ ] **Step 1:** добавить зависимость; `./scripts/gradle-docker.ps1 ./gradlew assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** проверить APK: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep lib/` — библиотеки ML Kit есть только в `lib/armeabi-v7a/`; записать прирост размера APK.
- [ ] **Step 3:** probe-activity: intent `--es command download|translate|delete|status`; `download` качает en и ru (`RemoteModelManager`, без условия Wi-Fi), `translate` переводит 20 фиксированных фраз en→ru и ru→en и пишет в `Log.d("TranslateProbe", …)` время каждой и среднее, `status` — список скачанных моделей. Ключи/секреты не нужны.
- [ ] **Step 4:** на ТВ (MiTV, ByeDPI не трогать, приложение исключено из VPN): `download` → `status` показывает обе модели; выключить сеть → `translate` работает; параллельно в плеере идёт 1080p-видео — записать время перевода фразы и наличие рывков.
- [ ] **Step 5:** проверить, даёт ли API ML Kit прогресс загрузки. Если нет — в UI будет «Загрузка модели…» без процента; записать в протокол и спеку.
- [ ] **Step 6:** протокол (commit, модель/ABI/API ТВ, сценарии, время, итог). Gate пройден, если модели скачались без смены VPN, перевод офлайн работает, среднее время фразы ≤ 300 мс и видео без рывков. Иначе — остановить план, отметить в STATUS/BACKLOG «этап 4 отложен», зависимость откатить.
- [ ] **Step 7:** commit `chore(translate): add ML Kit gate probe`.

### Task 2: Переводчик и модели

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/translate/SubtitleTranslator.kt`, `MlKitTranslator.kt`, `TranslationModels.kt`, `TranslateModule.kt`.
- Test: `app/src/test/java/dev/gamspi/videogallery/translate/TranslationModelsTest.kt`, `FakeSubtitleTranslator.kt`.

**Interfaces:**
- Produces:
  - `enum class TranslateLang(val tag: String) { EN("en"), RU("ru") }`; `fun TranslateLang.other(): TranslateLang`; `fun translateLang(language: String?): TranslateLang?` (через `subtitleLanguage`, `eng`→EN, `rus`→RU).
  - `interface SubtitleTranslator { suspend fun translate(text: String, from: TranslateLang, to: TranslateLang): String; val modelVersion: String }`.
  - `sealed interface ModelState { NotDownloaded; Downloading(progress: Int?); Ready; data class Failed(val reason: ModelFailure) }`, `enum class ModelFailure { NoNetwork, NoSpace, Other }`.
  - `interface TranslationModels { val state: StateFlow<ModelState>; suspend fun ensureDownloaded(); suspend fun delete() }` — общее состояние обеих моделей (Ready, только если скачаны обе). Singleton, загрузка в собственном application-scope, повторный вызов во время загрузки не запускает вторую.
  - `fun ModelFailure.message(): String` — «Нет модели перевода: нужна сеть для загрузки», «Недостаточно места для модели перевода», «Не удалось скачать модель перевода».

- [ ] **Step 1:** тесты `TranslationModelsTest` на фейковом бэкенде (`ModelBackend` — тонкий интерфейс над `RemoteModelManager`, внутри модуля): `ensureDownloaded` дважды параллельно → один вызов бэкенда; ошибка сети → `Failed(NoNetwork)`, следующий вызов повторяет загрузку; `delete` → `NotDownloaded`; отмена вызывающего не отменяет общую загрузку; `translateLang("eng") == EN`, `translateLang("und") == null`.
- [ ] **Step 2:** запуск `--tests 'dev.gamspi.videogallery.translate.*'` → FAIL (нет классов).
- [ ] **Step 3:** реализация; `MlKitTranslator` кэширует `Translator` на направление, закрывает их при `delete`, `Task` ждёт через `kotlinx-coroutines-play-services` `await()` или `suspendCancellableCoroutine`. Маппинг исключений ML Kit → `ModelFailure` по коду ошибки/`IOException`/нехватке места.
- [ ] **Step 4:** тесты → PASS.
- [ ] **Step 5:** commit `feat(translate): add translator and model manager`.

### Task 3: Room-кэш переводов

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` — Room runtime/ktx + `ksp(room-compiler)`, schema export в `app/schemas`.
- Create: `translate/TranslationDatabase.kt`, `TranslationDao.kt`, `TranslationCache.kt`.
- Test: `app/src/test/java/dev/gamspi/videogallery/translate/TranslationCacheTest.kt` (Robolectric, in-memory Room).

**Interfaces:**
- Produces: `class TranslationCache { suspend fun get(key: CacheKey): Map<Int, String>; suspend fun put(key: CacheKey, cues: Map<Int, String>); suspend fun clear() }`, `data class CacheKey(val fileId: Long, val target: TranslateLang, val modelVersion: String)`, константа `TRANSLATION_CACHE_LIMIT_BYTES = 50L * 1024 * 1024`.
- Таблицы: `translated_cue(fileId, target, modelVersion, cueIndex, text, PK всех кроме text)`, `cache_entry(fileId, target, modelVersion, bytes, lastUsedMs)`.

- [ ] **Step 1:** тесты: put/get возвращает те же индексы; get обновляет `lastUsedMs`; при превышении 50 МБ (в тесте — лимит через конструктор, например 1 КБ) удаляется самая давняя запись, текущая — нет; другая `modelVersion` — промах; исключение DAO в `get` → пустая map, в `put` → проглатывается и логируется, но `CancellationException` пробрасывается.
- [ ] **Step 2:** запуск → FAIL.
- [ ] **Step 3:** реализация; все операции через `withContext(Dispatchers.IO)`, вытеснение в одной транзакции с `put`. `clear()` вызывается из `TranslationModels.delete()`.
- [ ] **Step 4:** тесты → PASS; `assembleDebug` → BUILD SUCCESSFUL (KSP/Room).
- [ ] **Step 5:** commit `feat(translate): add Room translation cache`.

### Task 4: Фоновый перевод полного трека

**Files:**
- Create: `translate/TrackTranslation.kt`.
- Test: `translate/TrackTranslationTest.kt`.

**Interfaces:**
- Consumes: `SubtitleTranslator`, `TranslationCache`, `SubtitleTrack`/`SubtitleCue`.
- Produces: `class TrackTranslation(track: SubtitleTrack, key: CacheKey, from: TranslateLang, translator, cache, scope)` с `val translated: StateFlow<SubtitleTrack>` (только переведённые cue с исходными таймингами), `val failed: StateFlow<Boolean>`, `fun seek(positionMs: Long)`, `fun cancel()`. Батч — `TRANSLATION_BATCH = 20` cue.

- [ ] **Step 1:** тесты на `FakeSubtitleTranslator` и `StandardTestDispatcher`:
  - старт с позиции 60 с переводит сначала cue от 60 с, затем хвост, затем начало;
  - `seek(10 с)` после первого батча — следующий батч от 10 с, уже переведённые не отправляются повторно (счётчик вызовов фейка);
  - кэш содержит часть cue → они сразу в `translated`, переводчик их не получает; новые результаты пишутся в кэш батчами;
  - пустой/пробельный текст не отправляется, многострочный текст переводится построчно и склеивается через `\n`;
  - ошибка одного cue — cue пропускается; 5 ошибок подряд → `failed = true`, работа останавливается;
  - `cancel()` во время перевода — больше вызовов нет, `CancellationException` не превращается в `failed`.
- [ ] **Step 2:** запуск → FAIL.
- [ ] **Step 3:** реализация: очередь индексов, пересобираемая по позиции; worker-корутина на `Dispatchers.Default`.
- [ ] **Step 4:** тесты → PASS.
- [ ] **Step 5:** commit `feat(translate): translate downloaded tracks from position`.

### Task 5: Источник «перевод» в PlayerViewModel

**Files:**
- Modify: `subs/SubtitleSourcing.kt` — `data class Translated(val from: Layer, val sourceLang: TranslateLang) : LayerSource`.
- Create: `translate/EmbeddedTranslation.kt`.
- Modify: `player/PlayerViewModel.kt` — конструкторы (новые параметры с дефолтами для тестов), `select`, `applySource`, `textAt`, `onEmbeddedText(layer: Layer, text: String?)`, `translationOptions(layer): List<LayerSource.Translated>`.
- Test: `player/PlayerTranslationViewModelTest.kt`, `translate/EmbeddedTranslationTest.kt`.

**Interfaces:**
- Consumes: задачи 2–4.
- Produces для UI: `fun translationOptions(layer: Layer): List<LayerSource.Translated>` (пусто — пункт недоступен), `val translatedEmbedded: StateFlow<Map<Layer, String?>>`, `LayerState.note` для состояний «Загрузка модели…», «Перевод…», «Нет субтитров для перевода», причин `ModelFailure`, «Перевод недоступен».
- `EmbeddedTranslation`: `suspend fun translate(text: String): String?` — LRU в памяти на 500 фраз; возвращает `null`, если за время перевода пришёл новый cue (сравнение по поколению).

- [ ] **Step 1:** тесты VM (по образцу `PlayerSubtitleViewModelTest`):
  - `translationOptions(B)` при A = Remote(en) и languageB = ru → `[Translated(A, EN)]`; при A = Translated → пусто (нет цепочек); при A = Off → пусто;
  - A = Embedded с языком `und` → `[Translated(A, EN), Translated(A, RU)]`;
  - languageB = `de` и A = Remote(en) → пусто;
  - выбор Translated при `ModelState.NotDownloaded` вызывает `ensureDownloaded`, note «Загрузка модели…»; после Ready — перевод стартует; `Failed(NoNetwork)` → note из `ModelFailure.message()`;
  - `textAt(B, pos)` для Remote-источника использует `offsetMs` слоя A;
  - смена источника A во время перевода → старая `TrackTranslation` отменена, её поздние результаты не попадают в B; A → Off → B note «Нет субтитров для перевода»;
  - после `onCleared` загрузка модели и перевод не меняют `layers`;
  - embedded: `onEmbeddedText(A, "Hello")` → `translatedEmbedded[B]` = перевод; новый cue до окончания перевода → старый результат отброшен; `null` → пусто.
- [ ] **Step 2:** запуск `--tests 'dev.gamspi.videogallery.player.PlayerTranslationViewModelTest' --tests 'dev.gamspi.videogallery.translate.*'` → FAIL.
- [ ] **Step 3:** реализация. Перевод — отдельный `layerJobs[layer]` с поколением, как у Remote. `seek` в `TrackTranslation` VM вызывает при скачке позиции больше 5 с между кадрами `textAt` (решение исполнителя; не требует новых колбэков экрана).
- [ ] **Step 4:** тесты → PASS, плюс прежние `PlayerSubtitleViewModelTest`, `PlayerViewModelTest`.
- [ ] **Step 5:** commit `feat(player): add translated subtitle layer source`.

### Task 6: Меню, экран плеера, настройки

**Files:**
- Modify: `player/TracksMenu.kt` (`layerOptions` + параметр `translations: List<LayerSource.Translated>`; блок сдвига — только для Remote, не для Translated), `player/PlayerScreen.kt` (передать embedded-текст в `vm.onEmbeddedText`, оверлей берёт `translatedEmbedded` для Translated-слоя), `settings/SettingsScreen.kt`, `settings/SettingsViewModel.kt` (кнопки «Скачать модели перевода» / «Удалить модели перевода», статус).
- Test: `player/TracksMenuTest.kt`, `settings/SettingsViewModelTest.kt`.

- [ ] **Step 1:** тесты: `layerOptions` добавляет «Перевод с en»/«Перевод с ru» после OpenSubtitles; при пустых `translations` и наличии другого слоя без текста — строка «Нет субтитров для перевода» (disabled); `SettingsViewModel.downloadModels()` вызывает `ensureDownloaded`, статус отражает `ModelState` («Модели не скачаны», «Загрузка…», «Модели готовы», причина ошибки); `deleteModels()` вызывает `delete` и очищает кэш.
- [ ] **Step 2:** запуск → FAIL.
- [ ] **Step 3:** реализация. Фокус и Back меню не менять; корневой обработчик не перехватывает навигацию меню.
- [ ] **Step 4:** тесты → PASS; `./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL, XML без failures/errors.
- [ ] **Step 5:** commit `feat(player): translation menu and model settings`.

### Task 7: Приёмка на ТВ и документы

**Files:**
- Create: `docs/verification/2026-11-XX-translation.md`.
- Modify: `docs/STATUS.md`, `docs/BACKLOG.md` (G-08), `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (раздел translate, стек хранения), статус этого плана.

- [ ] **Step 1:** перед запуском спросить пользователя, свободен ли ТВ; при занятом ТВ — телефон, с пометкой, что ТВ не проверен.
- [ ] **Step 2:** сценарии: скачивание моделей из настроек; выбор перевода без моделей (автозагрузка, слой показывает состояние, фильм играет); перевод скачанных en→ru и встроенных; seek вперёд/назад; пауза; смена/выключение источника; повторный просмотр — перевод из кэша без вызовов модели (лог); офлайн после загрузки; удаление моделей; пульт: фокус, Back, удержание стрелок; нагрузка — без рывков видео.
- [ ] **Step 3:** протокол без секретов (commit, устройство/ABI/API, итог каждого сценария, ограничения).
- [ ] **Step 4:** синхронизировать STATUS, BACKLOG G-08, спеку, статус плана; `git diff --check`.
- [ ] **Step 5:** commit `docs: record stage 4 translation acceptance`.
