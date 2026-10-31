# Выбор источника каталога (Кинопоиск / TMDB) — план реализации

> Актуальные правила работы: [AGENTS.md](../../../AGENTS.md). План сохраняет историю этапа; обязательной зависимости от внешних skills нет.

## Статус на 2026-10-31

Задачи 1–4 реализованы: настройки, клиент Кинопоиска, переключение источников и экран настроек. Подтверждение: коммиты `2f5ce77`, `96f89f9`, `145b1a6`, `d734cb9`; общий прогон базы `d1ea23e` проходит. Read timeout сейчас 15 секунд; увеличение до 45 секунд относится к ещё не выполненному плану PoiskKino (G-04).

Исторические чекбоксы не подтверждают последовательность TDD или проверку на устройстве. Фактический статус — [STATUS](../../STATUS.md); свежая приёмка на ТВ не выполнялась.

**Goal:** Источник каталога выбирается в настройках: Кинопоиск (Kinopoisk API Unofficial, по умолчанию) или TMDB; у каждого свой ключ.

**Architecture:** Новый пакет `catalog/kinopoisk` реализует тот же `CatalogRepository`. `SwitchingCatalogRepository` делегирует в активный источник по `SettingsRepository.catalogSource`. ViewModel перезагружаются при смене `activeCatalog` (источник + его ключ). Тексты ошибок ключа зависят от источника через `LocalCatalogSource`.

**Tech Stack:** как в этапе 1 (Retrofit 3, OkHttp 5, kotlinx.serialization, Hilt, Compose for TV, mockwebserver3, Turbine).

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модуль `catalog`, «Настройки»). Причина: TMDB блокируется провайдером в РФ (DNS → 127.0.0.1).

## Global Constraints

- Пакет `dev.gamspi.videogallery`; версии — в `gradle/libs.versions.toml`; новых зависимостей не добавлять.
- Ключи и выбор источника — только через `SecretStore`.
- Кинопоиск: base `https://kinopoiskapiunofficial.tech/`, ключ в заголовке `X-API-KEY`, OkHttp-таймауты 15 с.
- Только фильмы: элементы с `type` ≠ `"FILM"` отбрасываются (если `type` есть).
- Весь текст UI — на русском. Источник по умолчанию — Кинопоиск.
- Файлы — UTF-8 без BOM; проверка документации и кодировки описана в [TESTING](../../TESTING.md), временный внешний `u8.py` не требуется.
- Сборка: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`.

## Review Focus

1. Кинопоиск вернул 402 (дневной лимит 500) → «Лимит запросов на сегодня исчерпан» + «Повторить», не «Ошибка загрузки». — Task 2, Task 3.
2. Смена источника в настройках → галерея/поиск/карточка перезагружаются из нового источника без перезапуска. — Task 1, Task 3.
3. Ключа выбранного источника нет → «Нужен ключ Кинопоиска» / «Нужен ключ TMDB» (по источнику), при этом ключ другого источника не используется. — Task 3.
4. Фильм без `nameRu` (только `nameOriginal`), без постера, без года/длительности → карточка без падения. — Task 2.
5. Сериалы в выдаче Кинопоиска (поиск, подборка) не попадают в каталог. — Task 2.

---

### Task 1: Настройки — источник и ключ Кинопоиска

**Files:**
- Create: `settings/CatalogSource.kt`
- Modify: `settings/SettingsRepository.kt`, `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модуль `catalog`: «TMDB или Кинопоиск (Kinopoisk API Unofficial), выбор в настройках»; «Настройки»: ключ Кинопоиска, выбор источника)
- Test: `app/src/test/.../settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `enum class CatalogSource { Kinopoisk, Tmdb }`
  - `data class ActiveCatalog(val source: CatalogSource, val apiKey: String?)`
  - В `SettingsRepository` (существующее не менять): `val kinopoiskApiKey: StateFlow<String?>`, `fun setKinopoiskApiKey(key: String)` (trim, пустое → null), `val catalogSource: StateFlow<CatalogSource>`, `fun setCatalogSource(source: CatalogSource)`, `val activeCatalog: StateFlow<ActiveCatalog>` (ключ активного источника).
  - Ключи SecretStore: `kinopoisk_api_key`, `catalog_source` (значение — `name` enum; неизвестное/нет → `Kinopoisk`).

- [ ] **Step 1:** Тесты: `source_defaultsToKinopoisk`; `setSource_persists` (новый репозиторий с тем же store → `Tmdb`); `kinopoiskKey_trimsAndBlankClears`; `activeCatalog_followsSourceAndKey` (ключи A для TMDB и B для КП; source=Kinopoisk → `ActiveCatalog(Kinopoisk,"B")`; после `setCatalogSource(Tmdb)` → `ActiveCatalog(Tmdb,"A")`); `unknownStoredSource_fallsBackToKinopoisk`.
- [ ] **Step 2:** Run `--tests '*SettingsRepositoryTest'` → FAIL.
- [ ] **Step 3:** Реализовать (`activeCatalog` — `MutableStateFlow`, обновляется во всех сеттерах; без scope).
- [ ] **Step 4:** Run → PASS. Обновить спеку.
- [ ] **Step 5:** Commit `feat: catalog source setting and Kinopoisk key`.

### Task 2: Кинопоиск-клиент

**Files:**
- Create: `catalog/kinopoisk/KinopoiskApi.kt`, `KinopoiskDto.kt`, `KinopoiskMapper.kt`, `KinopoiskCatalogRepository.kt`
- Modify: `catalog/CatalogException.kt` — `data object QuotaExceeded : CatalogException()`
- Test: `app/src/test/.../catalog/kinopoisk/KinopoiskCatalogRepositoryTest.kt`, фикстуры `app/src/test/resources/kinopoisk/{popular.json, filters.json, by_genre.json, search.json, search_empty.json, details.json}`

**Interfaces:**
- Consumes: `SettingsRepository.kinopoiskApiKey` (Task 1); модели `Movie`, `MovieDetails`, `Genre`.
- Produces: `class KinopoiskCatalogRepository(api, settings) : CatalogRepository` + `companion { const val BASE_URL = "https://kinopoiskapiunofficial.tech/"; fun create(baseUrl: String, settings: SettingsRepository): KinopoiskCatalogRepository }` — по образцу `TmdbCatalogRepository` (тот же `call {}`: ключ проверяется до запроса, интерцептор читает ключ на каждый запрос).
- Эндпоинты (все с `X-API-KEY`):
  - `popular` → `GET api/v2.2/films/collections?type=TOP_POPULAR_MOVIES&page=` → `items`
  - `genres` → `GET api/v2.2/films/filters` → `genres[{id, genre}]`; пустые имена и `для взрослых, новости, реальное ТВ, ток-шоу, церемония, концерт, игра` отбросить; имя с заглавной буквы.
  - `byGenre` → `GET api/v2.2/films?genres=&order=NUM_VOTE&type=FILM&page=` → `items`
  - `search` → `GET api/v2.2/films?keyword=&type=FILM&order=NUM_VOTE&page=1` → `items`
  - `details` → `GET api/v2.2/films/{id}`
- Маппинг: `id = kinopoiskId`; `title = nameRu ?: nameOriginal ?: nameEn ?: ""` (пустые строки — как null); `originalTitle = nameOriginal ?: title`; `year: Int?`; `posterUrl = posterUrlPreview ?: posterUrl`; `overview = description ?: ""`; details: `imdbId`, `runtimeMin = filmLength`, `genres = genres[].genre` (с заглавной), `backdropUrl = coverUrl`. DTO: все поля кроме id nullable, `ignoreUnknownKeys`.
- Ошибки: 401 → `Unauthorized`; 402 → `QuotaExceeded`; прочие HTTP → `Http(code)`; IO → `Network`; Serialization/IAE → `Parse`.

- [ ] **Step 1:** Тесты (MockWebServer, `FakeSecretStore` с `kinopoisk_api_key=KEY`): `popular_mapsFields_andSendsHeader` (заголовок `X-API-KEY: KEY`, путь и `type=TOP_POPULAR_MOVIES`); `popular_dropsSeries` (элемент `TV_SERIES` в фикстуре отсутствует в результате); `movie_withoutNameRu_usesOriginal_nullPosterAndYear`; `genres_filtersAndCapitalizes`; `byGenre_sendsGenreAndTypeFilm`; `search_sendsKeyword_emptyReturnsEmpty`; `details_mapsImdbRuntimeCoverGenres` (фикстура: фильм 301 «Матрица», `imdbId tt0133093`, `filmLength 136`); `missingKey_noRequest`; `http401_unauthorized`; `http402_quotaExceeded`; `ioError_network`; `garbageBody_parse`.
- [ ] **Step 2:** Run `--tests '*KinopoiskCatalogRepositoryTest'` → FAIL.
- [ ] **Step 3:** Реализовать; `CatalogException.QuotaExceeded` — `toErrorKind()` пока маппит в `Other` (Task 3 заменит).
- [ ] **Step 4:** Run → PASS; полный `testDebugUnitTest` → PASS.
- [ ] **Step 5:** Commit `feat: Kinopoisk catalog repository`.

### Task 3: Переключатель источника, перезагрузка, тексты ошибок

**Files:**
- Create: `catalog/SwitchingCatalogRepository.kt`, `ui/components/LocalCatalogSource.kt`
- Modify: `catalog/CatalogModule.kt`, `ui/components/ErrorView.kt`, `gallery/GalleryViewModel.kt`, `search/SearchViewModel.kt`, `details/DetailsViewModel.kt`, `ui/nav/AppNavHost.kt`
- Test: `app/src/test/.../catalog/SwitchingCatalogRepositoryTest.kt`; обновить `GalleryViewModelTest`, `SearchViewModelTest`, `DetailsViewModelTest`

**Interfaces:**
- Consumes: Task 1 (`catalogSource`, `activeCatalog`), Task 2 (`KinopoiskCatalogRepository.create`, `QuotaExceeded`).
- Produces:
  - `class SwitchingCatalogRepository(settings: SettingsRepository, tmdb: CatalogRepository, kinopoisk: CatalogRepository) : CatalogRepository` — каждый вызов идёт в репозиторий `settings.catalogSource.value`. Hilt: `CatalogRepository` → `SwitchingCatalogRepository` с обоими `create(...)`.
  - `ErrorKind.QuotaExceeded`; `CatalogException.QuotaExceeded.toErrorKind() == QuotaExceeded`.
  - `val LocalCatalogSource = staticCompositionLocalOf { CatalogSource.Kinopoisk }`; `AppNavHost` предоставляет значение из `SettingsRepository.catalogSource` (через `hiltViewModel<SettingsViewModel>()` уровня NavHost).
- Тексты `ErrorView` (точно): MissingKey — «Нужен ключ Кинопоиска» / «Нужен ключ TMDB»; BadKey — «Ключ Кинопоиска неверный» / «Ключ TMDB неверный» (кнопка «Настройки»); QuotaExceeded — «Лимит запросов на сегодня исчерпан» (кнопка «Повторить»); Network/Other — без изменений.
- ViewModel: вместо `settings.tmdbApiKey` в `combine(...)` использовать `settings.activeCatalog` — смена источника или его ключа перезагружает экран.

- [ ] **Step 1:** Тесты: `SwitchingCatalogRepositoryTest.delegatesBySource` (два `FakeCatalogRepository`; source=Kinopoisk → вызов ушёл в КП-фейк; `setCatalogSource(Tmdb)` → следующий вызов в TMDB-фейк); `quotaExceeded_mapsToKind`; в VM-тестах `sourceChanged_reloads` для галереи, поиска (тот же запрос перезапрашивается) и карточки; существующие тесты, полагающиеся на `tmdbApiKey`, перевести на активный источник (store с `catalog_source=Tmdb`, либо ключ КП).
- [ ] **Step 2:** Run `testDebugUnitTest` → FAIL на новых тестах.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS / SUCCESS.
- [ ] **Step 5:** Commit `feat: switch catalog source at runtime`.

### Task 4: Экран настроек

**Files:**
- Modify: `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`
- Test: `app/src/test/.../settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `SettingsViewModel.save(source: CatalogSource, kinopoiskKey: String, tmdbKey: String)` — сохраняет все три; `SettingsViewModel` отдаёт `catalogSource`, `kinopoiskApiKey`, `tmdbApiKey`.
- UI (сверху вниз, порядок фокуса D-pad): «Источник каталога» — две кнопки «Кинопоиск» / «TMDB» (выбранная отмечена «✓ » в начале текста и стилем), выбор меняет только состояние экрана; поле «Ключ Кинопоиска» + подсказка «Получить: kinopoiskapiunofficial.tech → Профиль»; поле «Ключ TMDB API» + подсказка «Получить: themoviedb.org → Settings → API»; кнопка «Сохранить» → `save(...)` и `onBack()`. Начальный фокус — выбранная кнопка источника. ImeAction.Done у полей → фокус вниз.

- [ ] **Step 1:** Тест `save_persistsSourceAndBothKeys` (через реальный `SettingsRepository(FakeSecretStore())`).
- [ ] **Step 2:** Run `--tests '*SettingsViewModelTest'` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS / SUCCESS.
- [ ] **Step 5:** Commit `feat: catalog source picker in settings`.
- [ ] **Step 6 (контроллер):** установить APK на ТВ через adb, ввести ключ Кинопоиска, проверить галерею, поиск «матрица», карточку, переключение на TMDB (ожидаемо «Нет сети» в РФ) и обратно.
