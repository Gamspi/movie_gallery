# Этап 1: Каталог — план реализации

> Актуальные правила работы: [AGENTS.md](../../../AGENTS.md). План сохраняет историю этапа; обязательной зависимости от внешних skills нет.

## Статус на 2026-10-31

Задачи 1–6 реализованы; общий прогон базы `d1ea23e` — 242 успешных unit-теста, сборка успешна. Задача 0 в части локальной установки JDK/SDK заменена [Docker-сборкой](../../../docker/README.md). Текущий каталог поддерживает Кинопоиск и TMDB; Кинопоиск выбран по умолчанию.

Исторические шаги TDD и ручные проверки ниже не отмечаются задним числом без протокола. Приёмка на ТВ/эмуляторе в аудите не выполнялась; текущий APK только `armeabi-v7a`. Актуальная сводка: [STATUS](../../STATUS.md), проверки: [TESTING](../../TESTING.md).

**Goal:** Android TV приложение, которое показывает каталог фильмов TMDB (популярное, жанры, поиск) и карточку фильма; ключ TMDB вводится в настройках.

**Architecture:** Один Gradle-модуль `app`, пакеты по функциям (`catalog`, `settings`, `ui`). Данные: Retrofit → DTO → доменные модели в `CatalogRepository`. UI: Compose for TV, ViewModel на экран, `UiState` sealed-класс. DI — Hilt.

**Tech Stack:** Kotlin 2.x, AGP (последний стабильный), Compose for TV (`androidx.tv:tv-material`), Navigation Compose, Hilt, Retrofit + OkHttp + kotlinx.serialization, Coil 3, androidx.security-crypto, JUnit4, MockWebServer, kotlinx-coroutines-test, Turbine.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (раздел «catalog», «Настройки», этап 1)

## Global Constraints

- minSdk 26, targetSdk/compileSdk — последний стабильный API.
- JDK 17. Версии всех зависимостей — последние стабильные на момент реализации, в `gradle/libs.versions.toml`.
- Пакет: `dev.gamspi.videogallery`. Имя приложения: «Video Gallery».
- Только Android TV: `LEANBACK_LAUNCHER`, `android.software.leanback` required=true, `android.hardware.touchscreen` required=false, баннер 320×180.
- Ключи/пароли — только через `SecretStore` (EncryptedSharedPreferences), не в DataStore и не в коде.
- TMDB: base `https://api.themoviedb.org/3/`, параметр `api_key`, `language=ru-RU`. Постеры: `https://image.tmdb.org/t/p/w342{path}`, фон: `https://image.tmdb.org/t/p/w1280{path}`.
- Весь текст UI — на русском.
- Сериалы не входят.

## Review Focus

1. Ключ TMDB не задан или неверный (HTTP 401) → экран с текстом «Нужен ключ TMDB» / «Ключ TMDB неверный» и кнопкой «Настройки», не пустой экран и не падение. — Task 3, Task 4.
2. Фильм без `poster_path`, с пустым `release_date` или без `original_title` → карточка с заглушкой, `year = null`, без падения парсинга. — Task 3.
3. Нет сети / таймаут → «Нет сети» + «Повторить», повтор реально перезапрашивает. — Task 4.
4. Поиск: пустой/пробельный запрос не ходит в API; быстрый ввод — только последний запрос (debounce 400 мс, отмена прошлого); пустой ответ → «Ничего не найдено». — Task 5.
5. Пульт: возврат «Назад» с карточки фильма возвращает фокус на ту же карточку галереи. — Task 6 (ручная проверка).

---

### Task 0: Окружение (JDK, Android SDK, эмулятор TV)

Ничего не коммитится. Требует скачивания из сети (Google, Adoptium/пакеты ОС) — **получить согласие пользователя перед запуском**.

- [ ] **Step 1:** Установить JDK 17 (`sudo apt install openjdk-17-jdk` или аналог). Проверка: `java -version` → `17`.
- [ ] **Step 2:** Скачать Android command-line tools в `~/Android/Sdk/cmdline-tools/latest`, прописать `ANDROID_HOME=~/Android/Sdk` и `PATH` (`cmdline-tools/latest/bin`, `platform-tools`, `emulator`) в `~/.bashrc`.
- [ ] **Step 3:** `sdkmanager --licenses` и установить: `platform-tools`, `platforms;android-<последний>`, `build-tools;<последний>`, `emulator`, `system-images;android-<последний TV>;google_atv;x86_64` (или `android-tv`).
- [ ] **Step 4:** `avdmanager create avd -n tv -k "<system image>" -d tv_1080p`.
- [ ] **Step 5:** Проверка: `sdkmanager --list_installed` содержит всё из шага 3; `emulator -avd tv` загружается до главного экрана TV.

### Task 1: Каркас проекта

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, gradle wrapper (`gradle wrapper` или скопировать)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/drawable/banner.xml` (320×180, текст «Video Gallery»), `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/dev/gamspi/videogallery/App.kt` (`@HiltAndroidApp`), `MainActivity.kt` (`@AndroidEntryPoint`, `setContent { VideoGalleryTheme { Text("Video Gallery") } }`), `ui/theme/Theme.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/SmokeTest.kt`

**Interfaces:**
- Produces: `VideoGalleryTheme(content: @Composable () -> Unit)`; Hilt настроен; `MainActivity` — точка входа для навигации (Task 4).

- [ ] **Step 1:** Написать `SmokeTest` с `@Test fun buildConfigPackage() { assertEquals("dev.gamspi.videogallery", BuildConfig.APPLICATION_ID) }`.
- [ ] **Step 2:** Создать файлы каркаса (плагины: android application, kotlin android, kotlin compose, kotlin serialization, hilt, ksp; `buildFeatures { compose = true; buildConfig = true }`).
- [ ] **Step 3:** Run: `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL, тест PASS.
- [ ] **Step 4:** Установить на эмулятор: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; приложение видно в ряду приложений TV с баннером, открывается с текстом «Video Gallery».
- [ ] **Step 5:** Commit: `git add . && git commit -m "feat: project scaffold for Android TV"`

### Task 2: Настройки и ключ TMDB

**Files:**
- Create: `settings/SecretStore.kt`, `settings/EncryptedSecretStore.kt`, `settings/SettingsRepository.kt`, `settings/SettingsModule.kt` (Hilt), `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`
- Test: `app/src/test/.../settings/SettingsRepositoryTest.kt`, `app/src/test/.../settings/FakeSecretStore.kt`

**Interfaces:**
- Produces:
  - `interface SecretStore { fun get(key: String): String?; fun put(key: String, value: String?) }`
  - `class SettingsRepository @Inject constructor(store: SecretStore) { val tmdbApiKey: StateFlow<String?>; fun setTmdbApiKey(key: String) }` — `setTmdbApiKey` делает `trim()`, пустую строку сохраняет как `null`.
  - `SettingsScreen(onBack: () -> Unit)` — поле «Ключ TMDB API» (ввод с экранной клавиатуры TV), кнопка «Сохранить», подсказка «Получить: themoviedb.org → Settings → API».

- [ ] **Step 1:** Тесты:
  - `setKey_trimsAndStores`: `setTmdbApiKey("  abc ")` → `tmdbApiKey.value == "abc"`, `store.get("tmdb_api_key") == "abc"`
  - `setKey_blankClears`: после `"abc"` вызов `setTmdbApiKey("   ")` → `tmdbApiKey.value == null`
  - `initialValue_readFromStore`: store заранее с `"xyz"` → новый репозиторий отдаёт `"xyz"`
- [ ] **Step 2:** Run: `./gradlew testDebugUnitTest --tests '*SettingsRepositoryTest'` → FAIL (нет классов).
- [ ] **Step 3:** Реализовать `SecretStore`, `EncryptedSecretStore` (EncryptedSharedPreferences, файл `secrets`), `SettingsRepository`, экран и ViewModel.
- [ ] **Step 4:** Run тот же → PASS.
- [ ] **Step 5:** Commit: `git commit -am "feat: settings with encrypted TMDB key"` (с `git add` новых файлов).

### Task 3: TMDB-клиент и CatalogRepository

**Files:**
- Create: `catalog/model/Movie.kt` (`Movie`, `MovieDetails`, `Genre`), `catalog/CatalogException.kt`, `catalog/CatalogRepository.kt`, `catalog/tmdb/TmdbApi.kt`, `catalog/tmdb/TmdbDto.kt`, `catalog/tmdb/TmdbMapper.kt`, `catalog/tmdb/TmdbCatalogRepository.kt`, `catalog/CatalogModule.kt`
- Test: `app/src/test/.../catalog/tmdb/TmdbCatalogRepositoryTest.kt`, фикстуры `app/src/test/resources/tmdb/{popular.json, genres.json, discover.json, search.json, search_empty.json, details.json}`

**Interfaces:**
- Consumes: `SettingsRepository.tmdbApiKey` (Task 2).
- Produces:
  - `data class Movie(val id: Int, val title: String, val originalTitle: String, val year: Int?, val posterUrl: String?, val overview: String)`
  - `data class MovieDetails(val movie: Movie, val imdbId: String?, val runtimeMin: Int?, val genres: List<String>, val backdropUrl: String?)`
  - `data class Genre(val id: Int, val name: String)`
  - `sealed class CatalogException : Exception() { object MissingApiKey; object Unauthorized; object Network; data class Http(val code: Int) }`
  - `interface CatalogRepository { suspend fun popular(page: Int = 1): List<Movie>; suspend fun genres(): List<Genre>; suspend fun byGenre(genreId: Int, page: Int = 1): List<Movie>; suspend fun search(query: String): List<Movie>; suspend fun details(id: Int): MovieDetails }` — все ошибки как `CatalogException`.
- Эндпоинты: `movie/popular`, `genre/movie/list`, `discover/movie?with_genres=&sort_by=popularity.desc`, `search/movie?query=`, `movie/{id}?append_to_response=external_ids` (`imdb_id` из `external_ids`).
- Маппинг: `year` = первые 4 символа `release_date`, если это число, иначе `null`; `originalTitle` пустой → `title`; `posterUrl`/`backdropUrl` = `null` при отсутствии пути. JSON: `ignoreUnknownKeys = true`, все поля DTO кроме `id` — nullable.

- [ ] **Step 1:** Тесты (MockWebServer, base URL подменяется; ключ из `FakeSecretStore`):
  - `popular_mapsFields`: из `popular.json` первый фильм → ожидаемые `id`, `title`, `year`, `posterUrl == "https://image.tmdb.org/t/p/w342/abc.jpg"`; запрос содержит `api_key=KEY` и `language=ru-RU`
  - `popular_nullPosterAndEmptyDate`: фильм в фикстуре с `poster_path: null`, `release_date: ""` → `posterUrl == null`, `year == null`
  - `details_readsImdbId`: `details(550).imdbId == "tt0137523"`, `runtimeMin == 139`
  - `byGenre_sendsGenreId`: запрос содержит `with_genres=28`
  - `search_empty_returnsEmptyList`
  - `missingKey_throwsMissingApiKey`: ключ `null` → `CatalogException.MissingApiKey`, сервер не получил запросов
  - `http401_throwsUnauthorized`
  - `ioError_throwsNetwork`: `SocketPolicy.DISCONNECT_AT_START` → `CatalogException.Network`
- [ ] **Step 2:** Run: `./gradlew testDebugUnitTest --tests '*TmdbCatalogRepositoryTest'` → FAIL.
- [ ] **Step 3:** Реализовать. Ключ подставлять OkHttp-интерцептором, читающим `tmdbApiKey.value` на каждый запрос (смена ключа без перезапуска). Таймауты OkHttp 15 с.
- [ ] **Step 4:** Run тот же → PASS.
- [ ] **Step 5:** Commit: `feat: TMDB catalog repository`

### Task 4: Галерея и навигация

**Files:**
- Create: `ui/nav/AppNavHost.kt`, `gallery/GalleryViewModel.kt`, `gallery/GalleryScreen.kt`, `ui/components/MovieCard.kt`, `ui/components/ErrorView.kt`
- Modify: `MainActivity.kt` — `setContent { VideoGalleryTheme { AppNavHost() } }`
- Test: `app/src/test/.../gallery/GalleryViewModelTest.kt`, `app/src/test/.../catalog/FakeCatalogRepository.kt`

**Interfaces:**
- Consumes: `CatalogRepository`, `CatalogException` (Task 3); `SettingsScreen` (Task 2).
- Produces:
  - Маршруты: `"gallery"`, `"search"`, `"settings"`, `"movie/{id}"`.
  - `data class MovieRow(val title: String, val movies: List<Movie>)`
  - `sealed interface GalleryUiState { object Loading; data class Content(val rows: List<MovieRow>); data class Error(val kind: ErrorKind) }`
  - `enum class ErrorKind { MissingKey, BadKey, Network, Other }` + `fun CatalogException.toErrorKind(): ErrorKind` (общий для экранов, в `ui/components/ErrorView.kt`)
  - `ErrorView(kind: ErrorKind, onRetry: () -> Unit, onOpenSettings: () -> Unit)` — тексты: MissingKey «Нужен ключ TMDB», BadKey «Ключ TMDB неверный» (оба с кнопкой «Настройки»), Network «Нет сети» и Other «Ошибка загрузки» (с кнопкой «Повторить»).
  - `MovieCard(movie: Movie, onClick: () -> Unit)` — постер 2:3 (заглушка с названием, если `posterUrl == null`), название, год.
- Поведение: ряды = «Популярное» + по одному ряду на первые 8 жанров из `genres()`; ряды жанров грузятся параллельно, упавший ряд жанра пропускается (не роняет экран). Ошибка `popular()` или `genres()` → `Error`. Сверху — кнопки «Поиск» и «Настройки». Состояние ViewModel пересчитывается при смене ключа (`tmdbApiKey` flow).

- [ ] **Step 1:** Тесты (`runTest`, `StandardTestDispatcher`, Turbine):
  - `load_success_popularFirstThenGenres`: `Content.rows[0].title == "Популярное"`, всего 1 + min(8, жанров)
  - `load_genreRowFails_rowSkipped`
  - `load_missingKey_errorMissingKey`
  - `load_network_thenRetry_content`: первый вызов бросает `Network`, после `retry()` → `Content`
  - `keyChanged_reloads`: из `Error(MissingKey)` после установки ключа → `Content`
- [ ] **Step 2:** Run: `./gradlew testDebugUnitTest --tests '*GalleryViewModelTest'` → FAIL.
- [ ] **Step 3:** Реализовать ViewModel и UI (`TvLazyColumn`/`LazyColumn` рядов с `LazyRow` карточек; фокус первой карточки при открытии).
- [ ] **Step 4:** Run тот же → PASS; `./gradlew assembleDebug` → SUCCESS.
- [ ] **Step 5:** Ручная проверка на эмуляторе: без ключа — «Нужен ключ TMDB» → Настройки → ввести ключ → назад → ряды с постерами. Выключить сеть эмулятора → «Нет сети» → включить → «Повторить» работает.
- [ ] **Step 6:** Commit: `feat: gallery screen with genre rows`

### Task 5: Поиск

**Files:**
- Create: `search/SearchViewModel.kt`, `search/SearchScreen.kt`
- Modify: `ui/nav/AppNavHost.kt` — маршрут `"search"`
- Test: `app/src/test/.../search/SearchViewModelTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository.search`, `MovieCard`, `ErrorView` (Tasks 3–4).
- Produces: `sealed interface SearchUiState { object Idle; object Loading; data class Results(val movies: List<Movie>); object Empty; data class Error(val kind: ErrorKind) }`; `SearchViewModel.onQueryChange(q: String)`.
- Поведение: debounce 400 мс, `trim()`; пустой запрос → `Idle` без вызова API; новый запрос отменяет прошлый (`flatMapLatest`/`collectLatest`); пустой ответ → `Empty` («Ничего не найдено»). Результаты — сетка карточек.

- [ ] **Step 1:** Тесты:
  - `blankQuery_idle_noApiCall`: `onQueryChange("   ")` + `advanceTimeBy(500)` → `Idle`, фейк получил 0 вызовов
  - `fastTyping_onlyLastQuerySent`: `"м"`, `"ма"`, `"мат"` с шагом 100 мс, затем `advanceTimeBy(500)` → фейк получил ровно `["мат"]`
  - `noResults_empty`
  - `results_shown`
  - `network_error`
- [ ] **Step 2:** Run: `./gradlew testDebugUnitTest --tests '*SearchViewModelTest'` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run тот же → PASS.
- [ ] **Step 5:** Commit: `feat: movie search`

### Task 6: Карточка фильма

**Files:**
- Create: `details/DetailsViewModel.kt`, `details/DetailsScreen.kt`
- Modify: `ui/nav/AppNavHost.kt` — маршрут `"movie/{id}"`, клики из галереи и поиска
- Test: `app/src/test/.../details/DetailsViewModelTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository.details`, `ErrorView` (Tasks 3–4).
- Produces: `sealed interface DetailsUiState { object Loading; data class Content(val details: MovieDetails); data class Error(val kind: ErrorKind) }`; `DetailsViewModel` берёт `id` из `SavedStateHandle["id"]`.
- UI: фон `backdropUrl` с затемнением, постер, название, оригинальное название (если отличается), год, длительность «2 ч 19 мин», жанры, описание. Кнопка «Смотреть» — фокус по умолчанию, на этапе 1 неактивна с подписью «Скоро» (подключится на этапе 2).
- Формат длительности: `fun formatRuntime(min: Int?): String?` — `139 → "2 ч 19 мин"`, `45 → "45 мин"`, `120 → "2 ч"`, `null`/`0 → null`.

- [ ] **Step 1:** Тесты:
  - `loads_details_by_id`: `SavedStateHandle(mapOf("id" to 550))` → `Content`, фейк вызван с `550`
  - `error_then_retry`
  - `formatRuntime_cases`: все пять значений выше
- [ ] **Step 2:** Run: `./gradlew testDebugUnitTest --tests '*DetailsViewModelTest'` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run: `./gradlew testDebugUnitTest assembleDebug` → все тесты PASS, BUILD SUCCESSFUL.
- [ ] **Step 5:** Ручная проверка на эмуляторе (только пульт/стрелки): галерея → карточка → «Назад» → фокус на той же карточке (если нет — сохранять ключ фокуса через `rememberSaveable` + `FocusRequester`); поиск «матрица» → результат → карточка; фильм без постера показывает заглушку.
- [ ] **Step 6:** Commit: `feat: movie details screen`
