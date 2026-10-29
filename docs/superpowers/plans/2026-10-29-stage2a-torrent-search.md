# Этап 2а: поиск раздач — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Кнопка «Смотреть» на карточке фильма открывает список раздач из RuTracker, YTS и apibay с фильтром качества и пометками о недоступных провайдерах.

**Architecture:** Пакет `torrents` (имя `search` уже занято поиском фильмов): модель `TorrentResult`, парсер качества, интерфейс `TorrentProvider`, три провайдера, агрегатор `TorrentSearch`. Экран `releases`. Настройки провайдеров (вкл/выкл, адрес, логин RuTracker) — в `SettingsRepository`/`SecretStore`. Воспроизведение — этап 2в; выбор раздачи пока показывает «Воспроизведение — скоро».

**Tech Stack:** как раньше + Jsoup (последняя стабильная, в `libs.versions.toml`) для HTML RuTracker.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модуль `search`, «Ошибки», «Настройки», этап 2). Решения пользователя: этап 2 = 3 плана (2а поиск, 2б плеер, 2в стример); Prowlarr и ffmpeg отложены; VPN на ТВ нет; адреса провайдеров редактируемые; фильтр качества нужен.

## Global Constraints

- Пакет `dev.gamspi.videogallery`; секреты (логин/пароль RuTracker) и настройки провайдеров — только через `SecretStore`.
- Провайдеры опрашиваются параллельно, таймаут 15 с на каждый (OkHttp-таймауты 15 с + `withTimeout(15_000)`); упавший провайдер не ломает выдачу.
- Дедуп по info-hash (без учёта регистра; остаётся запись с бо́льшими сидами); результаты без hash не дедупятся. Сортировка по сидам по убыванию.
- Адреса по умолчанию: RuTracker `https://rutracker.org/`, YTS `https://yts.lt/`, apibay `https://apibay.org/`; все включены. Адрес нормализуется: trim, добавить завершающий `/`.
- Тексты (точно): «Ищем раздачи…», «Раздачи не найдены», «<Провайдер> недоступен», «Неверный логин RuTracker» (с кнопкой «Настройки»), «Нет логина RuTracker» (с кнопкой «Настройки»), «Воспроизведение — скоро», фильтр «Все» / «2160p» / «1080p» / «720p». Имена провайдеров: «RuTracker», «YTS», «apibay».
- Размер: `< 1 ГБ` → «700 МБ» (целые), иначе «1,9 ГБ» (одна цифра после запятой, разделитель запятая); 1 ГБ = 1024³.
- Файлы — только Edit/Write (UTF-8; фикстуры RuTracker хранить в UTF-8, в тесте отдавать как windows-1251 с `Content-Type: text/html; charset=windows-1251`). Перед коммитом `git ls-files '*.kt' '*.xml' '*.kts' '*.json' '*.html' | py -I "$TEMP/u8.py"` → только `done`.
- Сборка: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`. Контейнер `tvadb` не трогать.

## Review Focus

1. Все провайдеры упали/выключены → понятный экран («Раздачи не найдены» + пометки), не пустота и не падение. — Task 1, Task 5.
2. У фильма нет `imdbId` → YTS молча пропускается (пусто, не ошибка), остальные ищут. — Task 2.
3. RuTracker: неверный логин / капча / нет логина → пометка с «Настройки», а не «недоступен». — Task 3.
4. Страница RuTracker в windows-1251 → кириллица в названиях не битая. — Task 3.
5. apibay «нет результатов» (служебная запись `id "0"`) → пустой список, а не фиктивная раздача. — Task 2.

---

### Task 1: Модель, качество, агрегатор

**Files:**
- Create: `torrents/TorrentResult.kt`, `torrents/Quality.kt`, `torrents/TorrentProvider.kt`, `torrents/TorrentSearch.kt`, `torrents/SizeFormat.kt`
- Test: `torrents/QualityTest.kt`, `torrents/TorrentSearchTest.kt`, `torrents/SizeFormatTest.kt`

**Interfaces:**
- Produces:
  - `enum class Quality(val label: String) { Uhd("2160p"), FullHd("1080p"), Hd("720p") }`; `fun parseQuality(title: String): Quality?` — `2160p|4k|uhd` → Uhd, `1080p|1080i` → FullHd, `720p` → Hd (без учёта регистра, по границам слова), иначе null.
  - `enum class ProviderId(val label: String) { RuTracker("RuTracker"), Yts("YTS"), Apibay("apibay") }`
  - `data class TorrentResult(val title: String, val provider: ProviderId, val sizeBytes: Long, val seeders: Int, val leechers: Int, val quality: Quality?, val infoHash: String?, val magnet: String?, val torrentUrl: String?)`
  - `fun magnetOf(infoHash: String, name: String): String` — `magnet:?xt=urn:btih:<HASH>&dn=<url-encoded name>` + `&tr=` для трекеров `udp://tracker.opentrackr.org:1337/announce`, `udp://open.demonii.com:1337/announce`, `udp://tracker.openbittorrent.com:80/announce`.
  - `interface TorrentProvider { val id: ProviderId; suspend fun search(movie: MovieDetails): List<TorrentResult> }` — бросает `ProviderException`.
  - `sealed class ProviderException : Exception() { object Unavailable; object NotLoggedIn; object BadLogin }` (data object)
  - `enum class FailureKind { Unavailable, NotLoggedIn, BadLogin }`; `data class ProviderFailure(val provider: ProviderId, val kind: FailureKind)`; `data class SearchOutcome(val results: List<TorrentResult>, val failures: List<ProviderFailure>)`
  - `class TorrentSearch(private val providers: () -> List<TorrentProvider>, private val timeoutMs: Long = 15_000) { suspend fun search(movie: MovieDetails): SearchOutcome }` — `providers()` возвращает только включённые; любое исключение провайдера кроме `CancellationException` и `ProviderException.NotLoggedIn/BadLogin` → `Unavailable`; таймаут → `Unavailable`.
  - `fun formatSize(bytes: Long): String`

- [ ] **Step 1:** Тесты: `QualityTest` (`"The.Matrix.1999.2160p.UHD"`→Uhd, `"Матрица 1999 BDRip 1080p"`→FullHd, `"720p WEB"`→Hd, `"DVDRip"`→null, `"x1080p5"`→null); `SizeFormatTest` (`734003200`→«700 МБ», `1992277407`→«1,9 ГБ»); `TorrentSearchTest` с фейковыми провайдерами: `merges_dedupsByHash_keepsMaxSeeds_sortsBySeeds` (hash в разном регистре), `failingProvider_reportedUnavailable_othersReturned`, `slowProvider_timesOut_unavailable` (виртуальное время, `timeoutMs` 15_000), `badLogin_reportedAsBadLogin`, `noProviders_emptyOutcome`, `resultsWithoutHash_notDeduped`.
- [ ] **Step 2:** Run `--tests '*torrents*'` → FAIL.
- [ ] **Step 3:** Реализовать (`coroutineScope` + `async` на провайдера + `withTimeout`).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: torrent result model and aggregated search`.

### Task 2: Провайдеры apibay и YTS

**Files:**
- Create: `torrents/apibay/ApibayProvider.kt`, `torrents/yts/YtsProvider.kt` (DTO/API внутри или рядом)
- Test: `torrents/apibay/ApibayProviderTest.kt`, `torrents/yts/YtsProviderTest.kt`, фикстуры `app/src/test/resources/torrents/{apibay.json, apibay_empty.json, yts.json, yts_empty.json}`

**Interfaces:**
- Consumes: Task 1. Base URL передаётся функцией `baseUrl: () -> String` (читается на каждый поиск — смена в настройках без перезапуска); `OkHttpClient` — параметром (таймауты 15 с).
- apibay: `GET {base}q.php?q=<originalTitle> <year>&cat=200` (если `year == null` — без года). Ответ — массив `{id,name,info_hash,leechers,seeders,size,num_files,username,added,status,category,imdb}` (все строки). Элемент с `id == "0"` → пусто. Оставить `category` ∈ {201, 202, 207, 211}. Если у фильма есть `imdbId` и у элемента непустой `imdb` ≠ ему — отбросить. `magnet = magnetOf(info_hash, name)`, `quality = parseQuality(name)`.
- YTS: `imdbId == null` → пустой список без запроса. `GET {base}api/v2/list_movies.json?query_term=<imdbId>` → `data.movies[].torrents[] {hash, quality, type, size_bytes, seeds, peers}`; `title = "<title_long> <quality> <type>"`; `quality` из поля `quality`; `magnet = magnetOf(hash, title)`; `data.movie_count == 0`/нет `movies` → пусто.
- Ошибки: HTTP ≠ 200, IO, неразборчивый JSON → `ProviderException.Unavailable`.

- [ ] **Step 1:** Тесты (MockWebServer): apibay — `parsesResults_buildsMagnet_quality`, `sendsTitleYearAndCategory`, `noResultsSentinel_empty`, `filtersForeignImdbAndCategory`, `http500_unavailable`; YTS — `parsesTorrents`, `sendsImdbQuery`, `noImdb_noRequest_empty`, `noMovies_empty`, `garbage_unavailable`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: apibay and YTS torrent providers`.

### Task 3: Провайдер RuTracker

**Files:**
- Create: `torrents/rutracker/RuTrackerProvider.kt`, `torrents/rutracker/RuTrackerParser.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (Jsoup)
- Test: `torrents/rutracker/RuTrackerParserTest.kt`, `torrents/rutracker/RuTrackerProviderTest.kt`, фикстуры `app/src/test/resources/torrents/{rutracker_search.html, rutracker_login_failed.html}`

**Interfaces:**
- Consumes: Task 1; `baseUrl: () -> String`, `credentials: () -> Pair<String, String>?` (null — нет логина), `OkHttpClient` с `CookieJar` в памяти.
- Поток: нет логина → `NotLoggedIn` без запросов. Если нет cookie `bb_session` → `POST {base}forum/login.php` форма `login_username`, `login_password`, `login=вход` (тело в windows-1251); успех = после ответа есть cookie `bb_session`; иначе (в т.ч. страница с капчей) → `BadLogin`. Затем `GET {base}forum/tracker.php?nm=<title> <year>`; если ответ — форма логина (сессия протухла) → один повторный логин, затем поиск.
- Парсинг (`RuTrackerParser.parse(html: String, base: String): List<TorrentResult>`): строки `#tor-tbl tr.hl-tr`; название — текст `a.tLink`; topic id — атрибут `data-topic_id` у `a.tLink` (или `t=` из href); размер — атрибут `data-ts_text` у `td.tor-size` (байты); сиды — `b.seedmed` (нет → 0); личи — `td.leechmed` (нет → 0). `torrentUrl = "{base}forum/dl.php?t=<id>"`, `magnet = null`, `infoHash = null`, `quality = parseQuality(title)`. Оставить строки, в названии которых есть год фильма (если год известен).
- Кодировка: тело ответа декодировать по charset из `Content-Type` (по умолчанию windows-1251).
- IO/HTTP ≠ 200 (кроме редиректа логина) → `Unavailable`.

- [ ] **Step 1:** Тесты: парсер — `parsesRows_titleSizeSeedsLeechTopic`, `keepsOnlyRowsWithYear`, `missingSeeds_zero`; провайдер (MockWebServer) — `noCredentials_notLoggedIn_noRequest`, `login_thenSearch_cyrillicIntact` (ответ в windows-1251), `loginFailed_badLogin`, `expiredSession_reloginOnce`, `http503_unavailable`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: RuTracker torrent provider`.

### Task 4: Настройки провайдеров

**Files:**
- Create: `settings/TrackerSettings.kt`
- Modify: `settings/SettingsRepository.kt`, `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`
- Test: `settings/SettingsRepositoryTest.kt`, `settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `data class TrackerConfig(val enabled: Boolean, val baseUrl: String)`; в `SettingsRepository`: `fun tracker(id: ProviderId): StateFlow<TrackerConfig>`, `fun setTracker(id: ProviderId, config: TrackerConfig)` (URL нормализуется; пустой → дефолт), `val rutrackerCredentials: StateFlow<Pair<String, String>?>` (null, если логин или пароль пуст), `fun setRutrackerCredentials(login: String, password: String)`. Ключи store: `tracker_<id.name>_enabled` (`"true"/"false"`), `tracker_<id.name>_url`, `rutracker_login`, `rutracker_password`.
- UI: под существующими полями заголовок «Трекеры»; для каждого провайдера строка: кнопка-переключатель «✓ RuTracker» / «RuTracker» (как кнопки источника), поле «Адрес RuTracker» (и т.п.); для RuTracker ещё поля «Логин RuTracker» и «Пароль RuTracker» (пароль — `PasswordVisualTransformation`). «Сохранить» сохраняет всё одним вызовом VM.

- [ ] **Step 1:** Тесты: `trackerDefaults_enabledWithDefaultUrls`, `setTracker_normalizesUrl_blankFallsBack`, `credentials_nullUnlessBoth`, VM `save_persistsTrackers`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: tracker settings`.

### Task 5: Экран раздач и подключение

**Files:**
- Create: `releases/ReleasesViewModel.kt`, `releases/ReleasesScreen.kt`, `torrents/TorrentsModule.kt` (Hilt)
- Modify: `details/DetailsScreen.kt` («Смотреть» активна, без «Скоро»), `ui/nav/AppNavHost.kt` (маршрут `"releases/{id}"`)
- Test: `releases/ReleasesViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4; `CatalogRepository.details(id)`.
- Produces: `sealed interface ReleasesUiState { object Loading; data class Content(val movieTitle: String, val all: List<TorrentResult>, val filter: Quality?, val failures: List<ProviderFailure>); data class Error(val kind: ErrorKind) }`; `ReleasesViewModel.setFilter(q: Quality?)`, `retry()`; `val visible` в `Content` = `all` с `quality == filter` (или все).
- Hilt: `TorrentSearch` с провайдерами, отфильтрованными по `tracker(id).value.enabled`; один `OkHttpClient` (15 с) на провайдеры, у RuTracker свой `CookieJar`.
- UI: заголовок — название фильма; ряд фильтров «Все / 2160p / 1080p / 720p» (выбранный с «✓ »); пометки провайдеров над списком («RuTracker недоступен», «Неверный логин RuTracker» + «Настройки», «Нет логина RuTracker» + «Настройки»); строки списка (фокусируемые tv `Card`/`ListItem`): название (до 2 строк), под ним «1080p · 1,9 ГБ · ↑987 ↓162 · apibay» (качество опускается, если null); пусто → «Раздачи не найдены»; загрузка «Ищем раздачи…»; клик по раздаче → сообщение «Воспроизведение — скоро». Начальный фокус — первая строка (или фильтр «Все», если список пуст). Ошибка `details()` → `ErrorView`.

- [ ] **Step 1:** Тесты (фейки `CatalogRepository` и провайдеров): `loads_detailsThenSearch_content`, `filter_showsOnlyQuality`, `failuresPassedThrough`, `detailsError_errorKind`, `retry_researches`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: releases screen`.
- [ ] **Step 6 (контроллер):** APK на ТВ; «Матрица» → «Смотреть» → список раздач, фильтр 1080p, пометки недоступных провайдеров; ввести логин RuTracker (если пользователь даст) и проверить.
