# Этап 2в: торрент-стример — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Клик по раздаче в списке → экран загрузки (метаданные, выбор файла, буфер) → плеер играет фильм прямо из торрента через локальный HTTP-сервер; со статусом торрента в оверлее; после выхода раздача останавливается и удаляется.

**Architecture:**
- Пакет `stream`. Чистая математика кусков и форматирование вынесены отдельно и покрыты тестами.
- `StreamServer` — свой минимальный HTTP-сервер на `127.0.0.1` (ServerSocket + корутины, без новых библиотек) поверх интерфейса `PieceSource`; тестируется с фейковым источником.
- `TorrentEngine` — интерфейс над libtorrent4j; реализация `Libtorrent4jEngine` проверяется только на ТВ.
- `StreamManager` (Hilt `@Singleton`) — машина состояний одной сессии: метаданные → выбор файла → буфер → готово. Её читают экран загрузки и плеер; тесты идут с фейковым движком.
- Плеер получает URL `http://127.0.0.1:<port>/stream` и ключ `bt:<hash>:<fileIndex>`. При уходе с плеера сессия останавливается, файлы удаляются.

**Tech Stack:** как раньше + libtorrent4j: `org.libtorrent4j:libtorrent4j` и `libtorrent4j-android-arm`, одна версия, последняя стабильная 2.x. Нативная библиотека только для `armeabi-v7a` (у ТВ `abilist = armeabi-v7a,armeabi`); `abiFilters` = `armeabi-v7a`.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модули `torrent`, `player`). Решения пользователя 2026-10-30:
- движок libtorrent4j;
- несколько видеофайлов → экран выбора, один → играть сразу;
- после просмотра удалять, настройку «хранить» и лимит кэша сделать позже;
- экран загрузки: статус + отмена «Назад»;
- статус торрента — в оверлее плеера;
- отдача ограничена 1 МБ/с, после выхода не сидируем;
- таймаут метаданных 60 с.

Требования к плееру со стороны стримера — из финального ревью 2б (память `stage2v-player-notes`).

## Global Constraints

- Пакет `dev.gamspi.videogallery`, новый код — в `stream/`.
- Файлы раздачи лежат в `context.filesDir/torrents/<infoHash>/`. При остановке сессии раздача удаляется из движка вместе с файлами. При старте приложения папка `torrents/` очищается (остатки после падения).
- Движок:
  - DHT и LSD включены;
  - upload limit 1 МБ/с;
  - выбранному файлу — приоритет обычный, остальным — 0 (не качать);
  - последовательная загрузка выключена: управляем дедлайнами кусков (`setPieceDeadline`), а не `sequential_download`.
- Готовность к воспроизведению:
  - скачаны первые `min(5 % файла, 20 МБ)` байт файла, это «голова»;
  - скачаны последние `min(5 МБ, файл)` байт, это «хвост» (cues MKV / moov MP4);
  - процент буфера = скачанные куски головы и хвоста / все куски головы и хвоста.
- `StreamServer`:
  - слушает `127.0.0.1`, порт выбирает система;
  - обслуживает `GET`/`HEAD /stream`, отвечает `Accept-Ranges: bytes`;
  - `Range: bytes=a-b` и `a-` → `206` + `Content-Range`, без Range → `200`, вне файла → `416`;
  - отдаёт данные кусками по мере готовности: на каждый нужный кусок ставит дедлайн и ждёт его;
  - обрыв клиента отменяет ожидание;
  - `Content-Type` берётся по расширению (`mkv` → `video/x-matroska`, `mp4`/`m4v` → `video/mp4`, `avi` → `video/x-msvideo`, `ts` → `video/mp2t`, `webm` → `video/webm`, иначе `application/octet-stream`).
- Видеофайлы — расширения `mkv mp4 m4v avi mov ts m2ts webm wmv mpg mpeg` без учёта регистра. Сортировка по размеру по убыванию.
- Плеер для `bt:`-сессий:
  - `DefaultHttpDataSource` с connect 30 с и read 60 с;
  - `DefaultLoadErrorHandlingPolicy(10)`;
  - `DefaultLoadControl.setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)`;
  - на экране ошибки кнопка «Повторить»: сбросить ошибку, `prepare()`, `seekTo(последняя позиция)`.
  - Для тестового URL — то же самое: одна конфигурация на всё.
- Ключ позиции — `bt:<infoHash в нижнем регистре>:<fileIndex>`, передаётся в маршрут плеера через `Uri.encode`.
- Тексты (точно):
  - «Получаем данные раздачи…»;
  - «Буферизация 45 %»;
  - «↓ 2,1 МБ/с · 34 пира»;
  - «Выберите файл»;
  - «Видеофайлы не найдены»;
  - «Нет пиров — выберите другую раздачу»;
  - «Не удалось запустить раздачу»;
  - «Назад»;
  - «Повторить».
- Строка статуса в оверлее плеера: «↓ 2,1 МБ/с · 34 пира · загружено 37 %».
- Скорость: < 1 МБ/с → «850 КБ/с» (целые), иначе «2,1 МБ/с»; 1 МБ = 1024². Пиры склоняются: «1 пир», «2 пира», «5 пиров», «21 пир».
- Файлы — только Edit/Write (UTF-8). Перед коммитом `git ls-files '*.kt' '*.xml' '*.kts' '*.json' '*.toml' '*.md' | py -I "$TEMP/u8.py"` → только `done`.
- Сборка: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`, на переднем плане; после — проверить XML результатов. Контейнер `tvadb` не трогать.

## Review Focus

1. Плеер перематывает в непрогруженное место (Range с середины файла) → сервер ставит дедлайны на эти куски и отдаёт данные, когда они пришли, а не ошибку и не нули. — Task 2.
2. ExoPlayer рвёт соединение при перемотке (частые обрывы и новые Range) → ожидание старого запроса отменяется, потоки и сокеты не копятся. — Task 2.
3. Пользователь нажал «Назад» на экране загрузки, пока не пришли метаданные → сессия остановлена, папка удалена, повторный клик по другой раздаче стартует чисто. — Task 3.
4. Раздача без видеофайлов (только `.rar`/`.iso`) → «Видеофайлы не найдены», а не падение и не бесконечная загрузка. — Task 3.
5. Файл меньше 20 МБ или меньше 5 МБ → голова и хвост пересекаются / не выходят за файл, буфер доходит до 100 %. — Task 1.

---

### Task 1: Чистая логика стримера

**Files:**
- Create: `stream/PieceMath.kt`, `stream/VideoFiles.kt`, `stream/StreamFormat.kt`
- Test: `stream/PieceMathTest.kt`, `stream/VideoFilesTest.kt`, `stream/StreamFormatTest.kt`

**Interfaces:**
- Produces:
  - `data class FileSpan(val offset: Long, val length: Long, val pieceLength: Int)`, где `offset` — смещение файла в торренте.
  - `fun FileSpan.piecesFor(start: Long, endInclusive: Long): IntRange` — индексы кусков торрента для байтового диапазона файла.
  - `fun FileSpan.readinessPieces(): Set<Int>` — голова `min(5 %, 20 МБ)` и хвост `min(5 МБ, length)`.
  - `fun readinessPercent(needed: Set<Int>, have: (Int) -> Boolean): Int` — 0..100; пустое множество → 100.
  - `data class TorrentFile(val index: Int, val path: String, val size: Long)`.
  - `fun videoFiles(files: List<TorrentFile>): List<TorrentFile>` — фильтр по расширению, сортировка по размеру по убыванию.
  - `fun contentTypeOf(path: String): String`.
  - `fun formatSpeed(bytesPerSec: Long): String`, `fun peersText(n: Int): String`.
  - `fun statusLine(speed: Long, peers: Int, progressPercent: Int?): String` — без процента: «↓ 2,1 МБ/с · 34 пира», с процентом: «… · загружено 37 %».

- [ ] **Step 1: Тесты**
  - `PieceMathTest`:
    - `piecesFor_withinFile_offsetAware` — файл со смещением 1000, кусок 256: байты 0..255 → куски 3..4;
    - `readiness_headAndTail` — файл 100 МБ: голова 5 МБ, хвост 5 МБ;
    - `readiness_capsHeadAt20MB` — файл 1 ГБ;
    - `readiness_smallFile_noOverflow` — файл 3 МБ: голова и хвост пересекаются, все индексы внутри файла (Review Focus 5);
    - `percent_emptyIs100`;
    - `percent_counts`.
  - `VideoFilesTest`:
    - `filtersByExtension_caseInsensitive`;
    - `sortsBySizeDesc`;
    - `noVideo_empty`.
  - `StreamFormatTest`:
    - `speed_kb` — 870400 → «850 КБ/с»;
    - `speed_mb` — 2202010 → «2,1 МБ/с»;
    - `peers_plural` — 1/2/5/11/21/22/25 → «1 пир», «2 пира», «5 пиров», «11 пиров», «21 пир», «22 пира», «25 пиров»;
    - `statusLine_withAndWithoutPercent`;
    - `contentType_known_and_default`.
- [ ] **Step 2:** Run `--tests '*stream*'` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: stream piece math, video file filter, status formatting`.

### Task 2: StreamServer (локальный HTTP с Range)

**Files:**
- Create: `stream/PieceSource.kt`, `stream/StreamServer.kt`
- Test: `stream/StreamServerTest.kt` (настоящий сокет + OkHttp-клиент)

**Interfaces:**
- Consumes: Task 1 (`contentTypeOf`).
- Produces:
  - `interface PieceSource`:
    - `val length: Long`
    - `val fileName: String`
    - `suspend fun awaitRange(start: Long, endInclusive: Long)` — поднимает приоритет (дедлайны) и ждёт, пока байты будут на диске; отменяемый;
    - `fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int`
  - `class StreamServer(private val scope: CoroutineScope)`:
    - `fun start(source: PieceSource): String` — запускается, возвращает `http://127.0.0.1:<port>/stream`;
    - `fun stop()`.
  - Каждое соединение обслуживается в своей корутине на `Dispatchers.IO`. Ответ пишется блоками по 256 КБ: перед каждым блоком `awaitRange` на него. Ошибка записи (клиент ушёл) завершает корутину и отменяет ожидание. Keep-alive не нужен (`Connection: close`).

- [ ] **Step 1: Тесты**
  - `get_full_200_withLengthAndType`;
  - `range_206_contentRange`;
  - `openRange_toEnd`;
  - `head_noBody`;
  - `outOfRange_416`;
  - `waitsForData` — фейк отдаёт диапазон через 300 мс, ответ приходит с данными (Review Focus 1);
  - `clientDisconnect_cancelsAwait` — клиент закрыл соединение, `awaitRange` фейка получает отмену (Review Focus 2);
  - `stop_closesSocket`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: local HTTP stream server with range support`.

### Task 3: TorrentEngine (libtorrent4j) и StreamManager

**Files:**
- Create: `stream/TorrentEngine.kt` (интерфейс), `stream/Libtorrent4jEngine.kt`, `stream/StreamManager.kt`, `stream/StreamModule.kt` (Hilt)
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (libtorrent4j, `ndk { abiFilters += "armeabi-v7a" }`), `App.kt` (очистка `filesDir/torrents` при старте)
- Test: `stream/StreamManagerTest.kt` (фейковый `TorrentEngine`)

**Interfaces:**
- Consumes: Task 1, Task 2.
- Produces:
  - `interface TorrentEngine`:
    - `suspend fun add(magnet: String, saveDir: File): String` — возвращает infoHash в нижнем регистре;
    - `suspend fun awaitMetadata(hash: String): List<TorrentFile>`;
    - `fun fileSpan(hash: String, fileIndex: Int): FileSpan`;
    - `fun selectFile(hash: String, fileIndex: Int)` — остальные с приоритетом 0;
    - `fun havePiece(hash: String, piece: Int): Boolean`;
    - `fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int)`;
    - `val pieceFinished: SharedFlow<Pair<String, Int>>`;
    - `fun status(hash: String): EngineStatus` (`downloadRate: Long`, `peers: Int`, `progress: Float`);
    - `fun filePath(hash: String, fileIndex: Int): File`;
    - `fun remove(hash: String, deleteFiles: Boolean)`.
  - `Libtorrent4jEngine(context)` — `SessionManager` стартует лениво при первом `add`: DHT/LSD, upload 1 МБ/с, `alert`-слушатель для `PieceFinishedAlert` / `MetadataReceivedAlert`.
  - `sealed interface StreamState`:
    - `Idle`;
    - `FetchingMetadata`;
    - `ChooseFile(files: List<TorrentFile>)`;
    - `Buffering(percent: Int, status: String)`;
    - `Ready(url: String, key: String, title: String)`;
    - `Failed(message: String)`.
  - `@Singleton class StreamManager(engine: TorrentEngine, server: StreamServer, filesDir: File, clock/timeouts)`:
    - `val state: StateFlow<StreamState>`;
    - `val statusLine: StateFlow<String?>` — для оверлея, обновляется раз в секунду, пока сессия жива;
    - `fun start(magnet: String, title: String)` — останавливает предыдущую сессию;
    - `fun chooseFile(index: Int)`;
    - `fun stop()` — remove + deleteFiles, сервер stop, `Idle`.
  - Поведение:
    - метаданные не пришли за 60 с → `Failed("Нет пиров — выберите другую раздачу")`;
    - нет видеофайлов → `Failed("Видеофайлы не найдены")`;
    - исключение движка → `Failed("Не удалось запустить раздачу")`;
    - один видеофайл → сразу буфер;
    - когда процент готовности дошёл до 100 → поднимается `StreamServer` с `PieceSource` над `filePath` + `fileSpan` (`awaitRange` = `prioritize` + ожидание `havePiece` через `pieceFinished`) → `Ready("http://…", "bt:$hash:$index", title)`.

- [ ] **Step 1: Тесты** (`StreamManagerTest`, фейковый движок, виртуальное время)
  - `metadataTimeout_failsNoPeers`;
  - `noVideo_failsNotFound` (Review Focus 4);
  - `singleVideo_buffersThenReady` — фейк «докачивает» куски, состояние идёт `FetchingMetadata` → `Buffering(…)` → `Ready`, а ключ равен `bt:<hash>:<idx>`;
  - `multipleVideos_chooseFile_thenBuffers`;
  - `stop_removesAndDeletes_backToIdle` (Review Focus 3);
  - `start_whileActive_stopsPrevious`;
  - `engineThrows_failsGeneric`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Реализовать (сначала интерфейс и менеджер, затем `Libtorrent4jEngine`, Hilt, очистку при старте).
- [ ] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS; в отчёт — размер APK до и после.
- [ ] **Step 5:** Commit `feat: libtorrent4j engine and stream manager`.

### Task 4: Экран загрузки, маршруты, плеер для торрентов

**Files:**
- Create: `stream/StreamScreen.kt`, `stream/StreamViewModel.kt`
- Modify:
  - `releases/ReleasesScreen.kt` — клик по раздаче вызывает `onPlay(result)`, текст «Воспроизведение — скоро» удаляется;
  - `ui/nav/AppNavHost.kt` — маршрут `stream?magnet={magnet}&title={title}`; при `Ready` переход на `player?url=…&title=…&key=…` с `popUpTo("stream…") { inclusive = true }`, так что «Назад» из плеера возвращает к раздачам;
  - `player/PlayerScreen.kt`, `player/PlayerOverlay.kt`, `player/PlayerViewModel.kt` — data source, load control, retry, строка статуса, остановка сессии.
- Test: `stream/StreamViewModelTest.kt`, `player/PlayerViewModelTest.kt` (дополнить)

**Interfaces:**
- Consumes: Task 3 (`StreamManager`).
- Поведение:
  - `StreamViewModel(savedState, manager)` в `init` вызывает `manager.start(magnet, title)`, `fun cancel()` вызывает `manager.stop()`. Если пользователь уходит с экрана, не дождавшись `Ready`, в `onCleared` тоже вызывается `stop()`; после `Ready` — нет.
  - `StreamScreen` по состоянию:
    - `FetchingMetadata` → «Получаем данные раздачи…»;
    - `Buffering` → «Буферизация N %» + строка статуса;
    - `ChooseFile` → заголовок «Выберите файл», фокусируемый список «имя файла · 1,9 ГБ» (размер через `formatSize`), фокус на первом;
    - `Failed` → текст + кнопка «Назад» в фокусе;
    - `Ready` → однократный `onReady(url, key, title)`.
  - «Назад» в любом состоянии вызывает `cancel()` и уходит назад.
  - `PlayerViewModel` получает `StreamManager` и отдаёт `statusLine` в оверлей (только для ключей `bt:`). В `onCleared` для `bt:`-ключа вызывается `manager.stop()`. Добавить `fun clearError()`.
  - Плеер: `MediaSource` через `DefaultMediaSourceFactory` с `DefaultHttpDataSource.Factory().setConnectTimeoutMs(30_000).setReadTimeoutMs(60_000)` и `DefaultLoadErrorHandlingPolicy(10)`; `DefaultLoadControl` из Global Constraints. На экране ошибки — кнопки «Повторить» (фокус) и «Назад».

- [ ] **Step 1: Тесты**
  - `StreamViewModelTest`:
    - `init_startsSession`;
    - `cancel_stops`;
    - `cleared_beforeReady_stops`;
    - `cleared_afterReady_keepsSession`.
  - `PlayerViewModelTest`:
    - `btKey_exposesStatusLine`;
    - `btKey_clearedStopsSession`;
    - `urlKey_noStatus_noStop`;
    - `clearError_resetsError`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: stream loading screen, torrent playback wiring`.
- [ ] **Step 6 (контроллер, ТВ):** раздача фильма из общественного достояния (например, «Ночь живых мертвецов» 1968) с apibay. Проверить:
  - метаданные → буфер → плеер;
  - статус в оверлее;
  - перемотка вперёд в непрогруженное место → «Загрузка…» → продолжение;
  - «Назад» из плеера → список раздач, папка `files/torrents` пустая;
  - «Назад» на экране загрузки;
  - раздача с несколькими файлами → «Выберите файл»;
  - Home во время буферизации → нет звука в фоне.
