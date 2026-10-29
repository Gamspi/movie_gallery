# Этап 2а: поиск раздач — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Кнопка «Смотреть» на карточке фильма открывает список раздач из rutor, apibay и YTS с фильтром качества и пометками о недоступных провайдерах.

**Architecture:** Пакет `torrents` (имя `search` уже занято поиском фильмов): модель `TorrentResult`, парсер качества, интерфейс `TorrentProvider`, три провайдера, агрегатор `TorrentSearch`. Экран `releases`. Настройки провайдеров (вкл/выкл, адрес) — в `SettingsRepository`/`SecretStore`. Воспроизведение — этап 2в; выбор раздачи пока показывает «Воспроизведение — скоро».

**Tech Stack:** как раньше + Jsoup (последняя стабильная, в `libs.versions.toml`) для HTML rutor.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модуль `search`, «Ошибки», «Настройки», этап 2). Решения пользователя: этап 2 = 3 плана (2а поиск, 2б плеер, 2в стример); Prowlarr и ffmpeg отложены; RuTracker заменён на rutor (RuTracker в РФ без VPN недоступен, VPN на ТВ нет); YTS оставлен, но выключен по умолчанию (сейчас недоступен); адреса провайдеров редактируемые; фильтр качества нужен.

## Global Constraints

- Пакет `dev.gamspi.videogallery`; настройки провайдеров — только через `SecretStore`.
- Провайдеры опрашиваются параллельно, таймаут 15 с на каждый (OkHttp-таймауты 15 с + `withTimeout(15_000)`); упавший провайдер не ломает выдачу.
- Дедуп по info-hash без учёта регистра (остаётся запись с бо́льшими сидами). Сортировка по сидам по убыванию.
- Адреса по умолчанию: rutor `https://rutor.info/` (вкл), apibay `https://apibay.org/` (вкл), YTS `https://yts.lt/` (выкл). Адрес нормализуется: trim, завершающий `/`; пустой → дефолт.
- Тексты (точно): «Ищем раздачи…», «Раздачи не найдены», «<Провайдер> недоступен», «Воспроизведение — скоро», фильтр «Все» / «2160p» / «1080p» / «720p», заголовок настроек «Трекеры», поля «Адрес rutor» / «Адрес apibay» / «Адрес YTS». Имена провайдеров: «rutor», «apibay», «YTS».
- Размер: `< 1 ГБ` → «700 МБ» (целые), иначе «1,9 ГБ» (одна цифра после запятой); 1 ГБ = 1024³.
- Файлы — только Edit/Write (UTF-8). Перед коммитом `git ls-files '*.kt' '*.xml' '*.kts' '*.json' '*.html' '*.md' | py -I "$TEMP/u8.py"` → только `done`.
- Сборка: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`. Контейнер `tvadb` не трогать.

## Review Focus

1. Все провайдеры упали/выключены → «Раздачи не найдены» + пометки, не пустота и не падение. — Task 1, Task 5.
2. У фильма нет `imdbId` → YTS молча пропускается (пусто, не ошибка), остальные ищут. — Task 2.
3. rutor по русскому названию ничего не нашёл → повторный поиск по оригинальному названию. — Task 3.
4. Размер rutor в разных единицах («700.5 MB», «1.46 GB», «265.94 GB», `&nbsp;`) → корректные байты. — Task 3.
5. apibay «нет результатов» (служебная запись `id "0"`) → пустой список, а не фиктивная раздача. — Task 2.

---

### Task 1: Модель, качество, агрегатор (+ спека)

**Files:**
- Create: `torrents/TorrentResult.kt`, `torrents/Quality.kt`, `torrents/TorrentProvider.kt`, `torrents/TorrentSearch.kt`, `torrents/SizeFormat.kt`
- Modify: спека — в модуле `search` провайдеры: rutor (HTML, без логина, поиск по русскому названию + год), YTS, apibay; RuTracker и Prowlarr — «отложены»; в «Настройки» и «Ошибки» убрать логин RuTracker.
- Test: `torrents/QualityTest.kt`, `torrents/TorrentSearchTest.kt`, `torrents/SizeFormatTest.kt`

**Interfaces:**
- Produces:
  - `enum class Quality(val label: String) { Uhd("2160p"), FullHd("1080p"), Hd("720p") }`; `fun parseQuality(title: String): Quality?` — `2160p|4k|uhd` → Uhd, `1080p|1080i` → FullHd, `720p` → Hd (без учёта регистра, по границам слова), иначе null.
  - `enum class ProviderId(val label: String) { Rutor("rutor"), Apibay("apibay"), Yts("YTS") }`
  - `data class TorrentResult(val title: String, val provider: ProviderId, val sizeBytes: Long, val seeders: Int, val leechers: Int, val quality: Quality?, val infoHash: String, val magnet: String)`
  - `fun magnetOf(infoHash: String, name: String): String` — `magnet:?xt=urn:btih:<HASH>&dn=<url-encoded name>` + `&tr=` для `udp://tracker.opentrackr.org:1337/announce`, `udp://open.demonii.com:1337/announce`, `udp://tracker.openbittorrent.com:80/announce`.
  - `interface TorrentProvider { val id: ProviderId; suspend fun search(movie: MovieDetails): List<TorrentResult> }` — при сбое бросает `ProviderUnavailableException : Exception()`.
  - `data class SearchOutcome(val results: List<TorrentResult>, val unavailable: List<ProviderId>)`
  - `class TorrentSearch(private val providers: () -> List<TorrentProvider>, private val timeoutMs: Long = 15_000) { suspend fun search(movie: MovieDetails): SearchOutcome }` — `providers()` отдаёт только включённые; любое исключение провайдера кроме `CancellationException`, а также таймаут → провайдер в `unavailable`.
  - `fun formatSize(bytes: Long): String`

- [ ] **Step 1:** Тесты: `QualityTest` (`"The.Matrix.1999.2160p.UHD"`→Uhd, `"Матрица 1999 BDRip 1080p"`→FullHd, `"720p WEB"`→Hd, `"DVDRip"`→null, `"x1080p5"`→null); `SizeFormatTest` (`734003200`→«700 МБ», `1992277407`→«1,9 ГБ»); `TorrentSearchTest` с фейками: `merges_dedupsByHash_keepsMaxSeeds_sortsBySeeds` (hash в разном регистре), `failingProvider_unavailable_othersReturned`, `slowProvider_timesOut_unavailable` (виртуальное время), `noProviders_emptyOutcome`.
- [ ] **Step 2:** Run `--tests '*torrents*'` → FAIL.
- [ ] **Step 3:** Реализовать (`coroutineScope` + `async` на провайдера + `withTimeout`). Обновить спеку.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: torrent result model and aggregated search`.

### Task 2: Провайдеры apibay и YTS

**Files:**
- Create: `torrents/apibay/ApibayProvider.kt`, `torrents/yts/YtsProvider.kt` (DTO/API рядом)
- Test: `torrents/apibay/ApibayProviderTest.kt`, `torrents/yts/YtsProviderTest.kt`, фикстуры `app/src/test/resources/torrents/{apibay.json, apibay_empty.json, yts.json, yts_empty.json}`

**Interfaces:**
- Consumes: Task 1. `baseUrl: () -> String` (читается на каждый поиск), `OkHttpClient` параметром (таймауты 15 с).
- apibay: `GET {base}q.php?q=<originalTitle> <year>&cat=200` (без года, если `year == null`). Ответ — массив `{id,name,info_hash,leechers,seeders,size,num_files,username,added,status,category,imdb}` (все значения строки). Элемент с `id == "0"` → пусто. Оставить `category` ∈ {201, 202, 207, 211}. Если у фильма есть `imdbId`, а у элемента непустой `imdb` ≠ ему — отбросить. `magnet = magnetOf(info_hash, name)`, `quality = parseQuality(name)`.
- YTS: `imdbId == null` → пусто без запроса. `GET {base}api/v2/list_movies.json?query_term=<imdbId>` → `data.movies[].torrents[] {hash, quality, type, size_bytes, seeds, peers}`; `title = "<title_long> <quality> <type>"`; `quality` из поля `quality`; `magnet = magnetOf(hash, title)`; нет `movies` → пусто.
- HTTP ≠ 200, IO, неразборчивый JSON → `ProviderUnavailableException`.

- [ ] **Step 1:** Тесты (MockWebServer): apibay — `parsesResults_buildsMagnet_quality`, `sendsTitleYearAndCategory`, `noResultsSentinel_empty`, `filtersForeignImdbAndCategory`, `http500_unavailable`; YTS — `parsesTorrents`, `sendsImdbQuery`, `noImdb_noRequest_empty`, `noMovies_empty`, `garbage_unavailable`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: apibay and YTS torrent providers`.

### Task 3: Провайдер rutor

**Files:**
- Create: `torrents/rutor/RutorProvider.kt`, `torrents/rutor/RutorParser.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (Jsoup)
- Test: `torrents/rutor/RutorParserTest.kt`, `torrents/rutor/RutorProviderTest.kt`, фикстура `app/src/test/resources/torrents/rutor_search.html` (реальная структура, см. ниже)

**Interfaces:**
- Consumes: Task 1; `baseUrl: () -> String`, `OkHttpClient`.
- Запрос: `GET {base}search/0/0/000/0/<title> <year>` (сегмент URL-кодирован как путь). Если после фильтра пусто и `originalTitle != title` — второй запрос с `originalTitle`.
- Разметка строки (реальная): `<tr class="gai">` или `<tr class="tum">`; ячейка названия содержит `<a href="magnet:?xt=urn:btih:<hash>&...">` и `<a href="/torrent/<id>/<slug>">Матрица / The Matrix (1999) BDRip 1080p | D</a>`; затем необязательная ячейка комментариев; предпоследняя ячейка — размер `265.94&nbsp;GB` (единицы B/kB/KB/MB/GB/TB, точка — десятичный разделитель, множитель 1024); последняя — `<span class="green">…&nbsp;14</span> … <span class="red">&nbsp;9</span>` (сиды / личи).
- `RutorParser.parse(html: String): List<TorrentResult>`: hash из magnet (нижний регистр), `magnet` — тот, что на странице, `title` — текст ссылки `/torrent/` (trim), `quality = parseQuality(title)`. Строки без magnet пропускаются. Провайдер оставляет строки, в названии которых есть год фильма (если год известен).
- HTTP ≠ 200 или IO → `ProviderUnavailableException`.

- [ ] **Step 1:** Тесты: парсер — `parsesRows_titleHashSizeSeedsLeech` (в фикстуре строки `gai` и `tum`, одна с ячейкой комментариев и одна без), `parsesSizeUnits` («700.5 MB», «1.46 GB», «265.94 GB»), `skipsRowsWithoutMagnet`; провайдер (MockWebServer) — `searchesRussianTitleAndYear`, `filtersByYear`, `emptyRussian_fallsBackToOriginalTitle`, `http503_unavailable`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: rutor torrent provider`.

### Task 4: Настройки трекеров

**Files:**
- Create: `settings/TrackerSettings.kt`
- Modify: `settings/SettingsRepository.kt`, `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`
- Test: `settings/SettingsRepositoryTest.kt`, `settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `data class TrackerConfig(val enabled: Boolean, val baseUrl: String)`; в `SettingsRepository`: `fun tracker(id: ProviderId): StateFlow<TrackerConfig>`, `fun setTrackers(configs: Map<ProviderId, TrackerConfig>)` (нормализация URL, пустой → дефолт; один вызов — одна запись). Ключи store: `tracker_<id.name>_enabled` (`"true"/"false"`), `tracker_<id.name>_url`.
- UI: под существующими полями заголовок «Трекеры»; для каждого провайдера: кнопка-переключатель (`«✓ rutor»` / `«rutor»`, стиль как у кнопок источника) и поле «Адрес rutor» (и т.д.). «Сохранить» сохраняет всё одним вызовом VM (существующий `save` расширить или добавить `saveTrackers` в тот же клик).

- [ ] **Step 1:** Тесты: `trackerDefaults` (rutor/apibay вкл, YTS выкл, дефолтные URL), `setTrackers_normalizesUrl_blankFallsBack`, `setTrackers_persists`, VM `save_persistsTrackers`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: tracker settings`.

### Task 5: Экран раздач и подключение

**Files:**
- Create: `releases/ReleasesViewModel.kt`, `releases/ReleasesScreen.kt`, `torrents/TorrentsModule.kt` (Hilt)
- Modify: `details/DetailsScreen.kt` («Смотреть» активна, подпись «Скоро» убрать), `ui/nav/AppNavHost.kt` (маршрут `"releases/{id}"`)
- Test: `releases/ReleasesViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4; `CatalogRepository.details(id)`.
- Produces: `sealed interface ReleasesUiState { object Loading; data class Content(val movieTitle: String, val all: List<TorrentResult>, val filter: Quality?, val unavailable: List<ProviderId>) { val visible: List<TorrentResult> }; data class Error(val kind: ErrorKind) }`; `ReleasesViewModel.setFilter(q: Quality?)`, `retry()`.
- Hilt: `TorrentSearch` с провайдерами, отфильтрованными по `tracker(id).value.enabled`; один `OkHttpClient` (15 с) на провайдеры.
- UI: заголовок — название фильма; ряд фильтров «Все / 2160p / 1080p / 720p» (выбранный с «✓ »); пометки «rutor недоступен» и т.п. над списком; строки (фокусируемые tv `ListItem`/`Card`): название (до 2 строк), ниже «1080p · 1,9 ГБ · ↑987 ↓162 · apibay» (качество опускается, если null); пусто → «Раздачи не найдены»; загрузка «Ищем раздачи…»; клик → «Воспроизведение — скоро» (Snackbar/текст). Начальный фокус — первая строка, если список пуст — фильтр «Все». Ошибка `details()` → `ErrorView`.

- [ ] **Step 1:** Тесты (фейки `CatalogRepository` и провайдеров): `loads_detailsThenSearch_content`, `filter_showsOnlyQuality`, `unavailablePassedThrough`, `detailsError_errorKind`, `retry_researches`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Реализовать. **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: releases screen`.
- [ ] **Step 6 (контроллер):** APK на ТВ; «Матрица» → «Смотреть» → список раздач rutor/apibay, фильтр 1080p, «Назад».
