# PoiskKino как третий источник + кэш — план реализации

> Актуальные правила работы: [AGENTS.md](../../../AGENTS.md). Обязательной зависимости от внешних skills нет.

## Статус на 2026-11-01

Задачи 1–3 реализованы на базе `d3ee3ef` тремя субагентами. Настройки/UI, клиент PoiskKino и кэш/подключение имеют отдельные границы файлов. Основной агент выполняет ревью, общий Docker-прогон и обновление документации. Отслеживание: G-04 в [BACKLOG](../../BACKLOG.md). Задачи 1–3 реализованы и прошли взаимное ревью. Docker-прогон: 349 тестов в 42 наборах без ошибок/пропусков; APK собран и установлен на Redmi. Запуск, ограниченный smoke настроек и сценарий без ключа проверены. Живой API и ТВ остаются открытыми; [протокол](../../verification/2026-11-01-poiskkino-cache.md).

Контракт сверен 2026-11-01 с [официальным OpenAPI](https://api.poiskkino.dev/documentation-json) и [сайтом сервиса](https://poiskkino.dev/). Текущий API — v1.5: универсальный список использует курсор next/hasNext, поиск по названию сохраняет page, справочники перенесены в /v1.5/dictionary/{type}. Ранние примеры v1.4 и позиционные id жанров ниже заменяются уточнениями в этом разделе.

- Клиент использует /v1.5/movie, /v1.5/movie/{id}, /v1.5/movie/search и /v1.5/dictionary/genres. Ответ словаря — объект items; жанры имеют числовые id. Используются положительные серверные id, записи без id исключаются; перестановка словаря не меняет смысл выбора жанра.
- CatalogRepository сохраняет page:Int. Адаптер получает cursor предыдущего ответа, при прямом переходе к странице N проходит недостающие страницы, после hasNext=false возвращает пустой список. Страница 1 начинает новую цепочку. Цепочки и словарь изолированы по ключу; отмена не превращается в ошибку каталога.
- Кэш изолирован по источнику и учётным данным. Секреты не должны записываться в URL/заголовки кэш-файлов; проверить это тестом содержимого диска. Ответы 401/403/5xx не кэшируются даже с разрешающими заголовками сервера. Карточки v1.5 имеют TTL сутки, остальные успешные ответы — час.
- Сохранённые Kinopoisk/Tmdb остаются выбранными; PoiskKino становится значением по умолчанию только для отсутствующего/неизвестного выбора. Временного делегирования PoiskKino другому провайдеру в итоговой реализации нет.

**Goal:** Третий источник каталога PoiskKino (api.poiskkino.dev), он же по умолчанию; HTTP-кэш ответов для всех источников; таймаут чтения Kinopoisk Unofficial 45 с.

**Architecture:** Пакет `catalog/poiskkino` реализует `CatalogRepository` по образцу `catalog/kinopoisk`. `CatalogSource` получает `PoiskKino`; `SwitchingCatalogRepository` — третий делегат. Общий `OkHttpClient` с дисковым кэшем (Hilt, `@ApplicationContext`) передаётся во все `create(...)`; сетевой интерцептор проставляет `Cache-Control: max-age`.

**Tech Stack:** как раньше; новых зависимостей нет.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модуль `catalog`, «Настройки»). Предыдущий план: `docs/superpowers/plans/2026-10-29-catalog-source-kinopoisk.md`. Причина: kinopoiskapiunofficial.tech отвечает 19–24 с.

## Global Constraints

- Пакет `dev.gamspi.videogallery`; новых зависимостей нет; ключи и выбор источника — только через `SecretStore`.
- PoiskKino: base `https://api.poiskkino.dev/`, ключ в заголовке `X-API-KEY`, таймауты 15 с.
- Kinopoisk Unofficial: read timeout 45 с (connect/write 15 с). Таймауты TMDB без изменений.
- Источник по умолчанию — PoiskKino. Только фильмы (`type == "movie"`, если поле есть).
- Кэш: каталог `cacheDir/http`, 20 МБ; `max-age` 3600 для списков/поиска/жанров, 86400 для карточки фильма; кэшируются только ответы 200.
- Весь текст UI — на русском. Файлы — UTF-8 без BOM; проверки описаны в [TESTING](../../TESTING.md), временный внешний `u8.py` не требуется.
- Сборка: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`.

## Review Focus

1. PoiskKino вернул 403 (дневной лимит 200) → «Лимит запросов на сегодня исчерпан». — Task 2.
2. Повторное открытие галереи в течение часа не делает сетевых запросов (берётся из кэша). — Task 3.
3. Жанры PoiskKino v1.5 имеют серверные числовые id → `byGenre(id)` использует тот же id независимо от порядка словаря, в том числе если `genres()` ещё не вызывался. — Task 2.
4. Ответ с ошибкой (401/403/5xx) не попадает в кэш и не «залипает». — Task 3.
5. Сохранённый ранее источник `Kinopoisk` остаётся выбранным после обновления; по умолчанию (ничего не сохранено) — PoiskKino. — Task 1.

---

### Task 1: Настройки — источник PoiskKino и его ключ

**Files:**
- Modify: `settings/CatalogSource.kt`, `settings/SettingsRepository.kt`, `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`, `ui/components/LocalCatalogSource.kt` (дефолт), `ui/components/ErrorView.kt` (тексты), спека (модуль `catalog`, «Настройки»)
- Test: `SettingsRepositoryTest.kt`, `SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `enum class CatalogSource { PoiskKino, Kinopoisk, Tmdb }`; `SettingsRepository.poiskkinoApiKey: StateFlow<String?>`, `setPoiskkinoApiKey(key)` (trim, пустое → null), ключ store `poiskkino_api_key`; неизвестное/пустое `catalog_source` → `PoiskKino`; `activeCatalog` учитывает третий источник. `SettingsViewModel.save(source, poiskkinoKey: String, kinopoiskKey: String, tmdbKey: String)`, `poiskkinoApiKey` flow. `LocalCatalogSource` дефолт `PoiskKino`.
- Тексты (точно): ErrorView MissingKey — «Нужен ключ PoiskKino», BadKey — «Ключ PoiskKino неверный» для PoiskKino (остальные без изменений). Экран настроек: три кнопки источника «PoiskKino» / «Кинопоиск» / «TMDB» (в этом порядке); первое поле «Ключ PoiskKino» с подсказкой «Получить: poiskkino.dev → Telegram-бот»; затем существующие поля.

- [x] **Step 1:** Тесты: `source_defaultsToPoiskKino` (заменяет `source_defaultsToKinopoisk`); `storedKinopoisk_staysKinopoisk`; `poiskkinoKey_trimsAndBlankClears`; `activeCatalog_poiskkino`; `save_persistsSourceAndThreeKeys`.
- [ ] **Step 2:** Run `--tests '*Settings*'` → FAIL.
- [x] **Step 3:** Реализовать; Подключение третьего делегата выполняется вместе с Task 3; итоговый код не перенаправляет PoiskKino другому источнику.
- [x] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: PoiskKino source setting and key`.

### Task 2: PoiskKino-клиент

**Files:**
- Create: `catalog/poiskkino/PoiskKinoApi.kt`, `PoiskKinoDto.kt`, `PoiskKinoMapper.kt`, `PoiskKinoCatalogRepository.kt`
- Test: `catalog/poiskkino/PoiskKinoCatalogRepositoryTest.kt`, фикстуры `app/src/test/resources/poiskkino/{popular.json, genres.json, by_genre.json, search.json, search_empty.json, details.json}`

**Interfaces:**
- Consumes: `SettingsRepository.poiskkinoApiKey` (Task 1).
- Produces: `class PoiskKinoCatalogRepository : CatalogRepository` + `companion { const val BASE_URL = "https://api.poiskkino.dev/"; fun create(baseUrl: String, settings: SettingsRepository, client: OkHttpClient = OkHttpClient()): PoiskKinoCatalogRepository }` — `client` базовый (Task 3 передаст клиент с кэшем), репозиторий строит свой через `client.newBuilder()` (интерцептор ключа, таймауты).
- Эндпоинты (`X-API-KEY`):
  - `popular` → `GET v1.5/movie?lists=popular-films&type=movie&next=&limit=20` (первый запрос без next) → `docs`
  - `genres` → `GET v1.5/dictionary/genres` → `{items:[{id,name,slug}]}`; отбросить пустые и `для взрослых, новости, реальное ТВ, ток-шоу, церемония, концерт, игра`; имя с заглавной; `id` = положительный серверный id, записи без id исключаются.
  - `byGenre(id)` → `GET v1.5/movie?genres.id=<серверный id>&type=movie&sortField=votes.kp&sortType=-1&next=&limit=20`; проверка id по закэшированному в памяти результату `genres()`, если его нет — сначала вызвать `genres()`; неизвестный id → пустой список.
  - `search` → `GET v1.5/movie/search?query=&page=1&limit=20` → `docs`, только `type == "movie"`.
  - `details` → `GET v1.5/movie/{id}`.
- Маппинг: `id`; `title = name ?: alternativeName ?: enName ?: ""` (пустые как null); `originalTitle = alternativeName ?: enName ?: title`; `year: Int?` (толерантный, как у Kinopoisk); `posterUrl = poster.previewUrl ?: poster.url`; `overview = description ?: shortDescription ?: ""`; details: `imdbId = externalId.imdb`, `runtimeMin = movieLength`, `genres = genres[].name` с заглавной, `backdropUrl = backdrop.url`.
- Ошибки: 401 → `Unauthorized`; 403 → `QuotaExceeded`; прочие HTTP → `Http`; IO → `Network`; Serialization/IAE → `Parse`; ключ проверяется до запроса.

- [x] **Step 1:** Тесты (MockWebServer, `FakeSecretStore` с `poiskkino_api_key=KEY`): `popular_mapsFields_sendsHeaderAndList`; `popular_dropsSeries` (`tv-series` выкинут); `movie_withoutName_usesAlternative_nullPosterAndYear`; `genres_filtersCapitalizesAndNumbers`; `byGenre_sendsGenreName` (после `genres()`); `byGenre_withoutPriorGenres_fetchesGenresFirst`; `byGenre_unknownId_empty`; `search_sendsQuery_filtersMovies_emptyReturnsEmpty`; `details_mapsImdbRuntimeBackdropGenres` (фикстура 301 «Матрица», `tt0133093`, 136); `missingKey_noRequest`; `http401_unauthorized`; `http403_quotaExceeded`; `ioError_network` (две CloseSocket); `garbageBody_parse`.
- [ ] **Step 2:** Run `--tests '*PoiskKinoCatalogRepositoryTest'` → FAIL.
- [x] **Step 3:** Реализовать.
- [x] **Step 4:** Run → PASS; полный `testDebugUnitTest` → PASS.
- [ ] **Step 5:** Commit `feat: PoiskKino catalog repository`.

### Task 3: Подключение, кэш, таймаут

**Files:**
- Create: `catalog/HttpCache.kt`
- Modify: `catalog/CatalogModule.kt`, `catalog/SwitchingCatalogRepository.kt`, `catalog/kinopoisk/KinopoiskCatalogRepository.kt`, `catalog/tmdb/TmdbCatalogRepository.kt` (параметр `client: OkHttpClient = OkHttpClient()` в `create`, как у PoiskKino)
- Test: `catalog/HttpCacheTest.kt`, `SwitchingCatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1, Task 2.
- Produces:
  - `fun cachingClient(cacheDir: File, maxBytes: Long = 20L * 1024 * 1024): OkHttpClient` — `Cache(File(cacheDir, "http"), maxBytes)` + сетевой интерцептор: для ответа 200 ставит `Cache-Control: public, max-age=N` (N = 86400, если путь соответствует карточке фильма: `/3/movie/{число}` TMDB, `/api/v2.2/films/{число}` Kinopoisk, `/v1.5/movie/{число}` PoiskKino; иначе 3600), убирает `Pragma`; для не-200 принудительно задаёт no-store; учётные данные изолируются и удаляются из данных дискового кэша.
  - `CatalogModule`: один `cachingClient(context.cacheDir)` (`@ApplicationContext`), передаётся во все три `create(...)`; `SwitchingCatalogRepository(settings, tmdb, kinopoisk, poiskkino)`.
  - Kinopoisk: `readTimeout(45, SECONDS)`.

- [x] **Step 1:** Тесты: `HttpCacheTest` (MockWebServer + `cachingClient(tempDir)`): `list_secondRequestServedFromCache` (одинаковый URL дважды → сервер получил 1 запрос); `details_cachedForDay` (проверка заголовка `Cache-Control` в ответе: `max-age=86400` для `/v1.4/movie/301`); `errorResponse_notCached` (401 затем 200 → сервер получил 2 запроса). `SwitchingCatalogRepositoryTest.delegatesPoiskKino`.
- [ ] **Step 2:** Run → FAIL.
- [x] **Step 3:** Реализовать.
- [x] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: wire PoiskKino, HTTP cache, Kinopoisk timeout`.
- [ ] **Step 6 (контроллер):** APK на ТВ, ввести ключ PoiskKino, проверить галерею, поиск «матрица», карточку, повторное открытие (быстро), переключение на Кинопоиск.

## Дополнительные решения реализации

HTTP-клиенты каталога не следуют редиректам автоматически: ответ 3xx проходит обычную обработку HTTP-ошибки. Это исключает повторное появление ключа в URL кэша через Location. Регрессионные тесты проверяют междоменный редирект и Location с api_key. Кэш хранит обезличенный отпечаток источника/ключа вместо самого ключа; ключ добавляется только к сетевому запросу.

Черновики ключей на экране настроек хранятся только в памяти Compose, без rememberSaveable. После пересоздания Activity несохранённый ввод сбрасывается, сохранённые значения читаются через SecretStore.
