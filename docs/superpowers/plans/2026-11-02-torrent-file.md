# G-10: `.torrent` от провайдера — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** при выборе раздачи rutor/YTS скачивать её `.torrent` по HTTP и стартовать движок из файла, а при любом сбое тихо использовать magnet.

**Architecture:** провайдеры добавляют `torrentUrl` в `TorrentResult`; ссылка идёт маршрутом до `StreamManager.start`. Новый `TorrentFileFetcher` качает байты (OkHttp, отменяемо, лимиты). `StreamManager` до некэнселябельного блока получает байты (сохранённый файл → загрузка), проверяет info-hash через `TorrentEngine.torrentHashOf`, после `acquire` атомарно пишет `torrents/<hash>/meta.torrent` и вызывает `engine.add(magnet, saveDir, torrentFile)`.

**Tech Stack:** Kotlin, coroutines, OkHttp 5.5.1 + mockwebserver3, libtorrent4j 2.1.0-39, Hilt, Navigation Compose.

**Spec:** `docs/superpowers/specs/2026-11-02-torrent-file-design.md`

**Статус:** задачи 1–6 выполнены 2026-11-02 (`36adfca`..`feb2598` + документы); реализация задач 2–5 и финальных правок — Codex CLI, ревью, тесты и коммиты — Claude. Приёмка на ТВ: [протокол](../../verification/2026-11-02-torrent-file.md); сценарий `stored` и трекеры файла на ТВ не проверены. Чекбоксы шагов ниже по отдельности не отмечались.

## Global Constraints

- Сборка и тесты только через Docker: `./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest assembleDebug`; проверять XML в `app/build/test-results/testDebugUnitTest/`.
- ABI `armeabi-v7a` не меняется.
- Сеть и файлы — не на UI-потоке. `CancellationException` не поглощать. Отмена корутины отменяет HTTP-вызов (`Call.await()` из `torrents/HttpAwait.kt`) и закрывает ответ.
- Любой сбой `.torrent` → magnet, без пользовательских надписей. Новых пользовательских строк нет.
- Лимиты: таймаут загрузки 10 с (`TORRENT_FETCH_TIMEOUT_MS = 10_000L`), размер 5 МБ (`TORRENT_FILE_MAX_BYTES = 5L * 1024 * 1024`), редиректов не больше 5 (`TORRENT_MAX_REDIRECTS = 5`), только `http`/`https`.
- Хэш сверяется по v1, в нижнем регистре, с `engine.hashOf(magnet)`.
- Имя файла: `meta.torrent` в `torrents/<hash>/`; запись через `meta.torrent.tmp` + rename.
- Ключи позиций `bt:<hash>:<fileIndex>` и владение сессией не меняются.
- Комментарии к коду кратко на английском. UTF-8 без BOM.

## Review Focus

1. rutor на ТВ заблокирован: загрузка висит до таймаута — раздача должна открыться через magnet не позже чем через ~10 с, а выход с экрана в это время сразу отменяет запрос (тест в задаче 4: отмена во время загрузки → `engine.add` не вызван, вызов отменён).
2. Сервер отдаёт HTML-заглушку блокировки со статусом 200 вместо `.torrent` — разбор падает, нужен magnet (тест в задаче 4: `torrentHashOf` → `null` → add без файла).
3. Тело без `Content-Length` больше 5 МБ (chunked) — чтение обрывается на пределе (тест в задаче 2).
4. Сохранённый `meta.torrent` обрезан/повреждён после сбоя питания — удаляется и качается заново (тест в задаче 4).
5. Повторный выбор той же раздачи, пока прошлая сессия ещё останавливается — путь с файлом проходит те же проверки `removing`/`uncertainAdds` (задача 3, код общий для обоих путей; проверяется ревью и на ТВ).

---

### Task 1: ссылка на `.torrent` у rutor и YTS

**Files:**
- Modify: `app/src/main/java/dev/gamspi/videogallery/torrents/TorrentResult.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/torrents/rutor/RutorParser.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/torrents/yts/YtsProvider.kt`
- Modify: `app/src/test/resources/torrents/yts.json` (добавить `"url"` хотя бы у одного торрента)
- Test: `app/src/test/java/dev/gamspi/videogallery/torrents/rutor/RutorParserTest.kt`, `.../yts/YtsProviderTest.kt`

**Interfaces:**
- Produces: `TorrentResult.torrentUrl: String? = null` (последнее поле, со значением по умолчанию).

- [ ] **Step 1: тесты**
  - `RutorParserTest.parsesTorrentUrl`: первая строка `rutor_search.html` → `torrentUrl == "https://d.rutor.info/download/1098254"`.
  - `RutorParserTest.missingDownloadLinkGivesNullUrl`: строка без `a[href*=/download/]` → `torrentUrl == null`, остальное как раньше.
  - `YtsProviderTest.mapsTorrentUrl`: торрент с `url` → `torrentUrl` равен ему; без `url` (пустая строка) → `null`.
- [ ] **Step 2:** запустить, убедиться, что тесты падают.
- [ ] **Step 3:** реализация. Rutor: `a[href*=/download/]`, `//host/...` → `https://host/...`, принимать только итоговые `http(s)`. YTS: `YtsTorrent.url: String = ""`, пустое → `null`. Apibay не трогать.
- [ ] **Step 4:** `./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest --tests "*Rutor*" --tests "*Yts*" --tests "*Apibay*"` — PASS.
- [ ] **Step 5:** commit `feat(torrents): expose .torrent url from rutor and yts`.

### Task 2: `TorrentFileFetcher`

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/stream/TorrentFileFetcher.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/stream/TorrentFileFetcherTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  fun interface TorrentFileSource { suspend fun fetch(url: String): ByteArray? }
  class TorrentFileFetcher(
      client: OkHttpClient,
      timeoutMs: Long = TORRENT_FETCH_TIMEOUT_MS,
      maxBytes: Long = TORRENT_FILE_MAX_BYTES,
      maxRedirects: Int = TORRENT_MAX_REDIRECTS,
  ) : TorrentFileSource
  ```
  Константы — top-level `internal const val` в этом файле. `fetch` возвращает `null` при любой ошибке (кроме `CancellationException`, она пробрасывается).

- [ ] **Step 1: тесты (MockWebServer)**
  - `returnsBodyOn200`: тело `d8:announce...e` → те же байты.
  - `nullOnHttpError`: 404 → `null`.
  - `nullWhenContentLengthOverLimit`: `maxBytes = 10`, тело 11 байт с длиной → `null`.
  - `nullWhenChunkedBodyOverLimit`: `maxBytes = 10`, chunked 11 байт → `null`.
  - `followsCrossHostRedirect`: сервер A 302 → сервер B 200 → байты B.
  - `nullAfterTooManyRedirects`: 6 редиректов подряд при `maxRedirects = 5` → `null`; 5 редиректов → байты.
  - `nullOnNonHttpScheme`: `fetch("ftp://x")` и редирект на `magnet:` → `null`.
  - `nullOnTimeout`: `timeoutMs = 200`, ответ с задержкой тела 1 с → `null`.
  - `cancellationCancelsCall`: отмена корутины во время задержки ответа → корутина отменена, на сервере запрос оборван (`takeRequest` получил запрос, `fetch` бросил `CancellationException`), исключение не проглочено.
- [ ] **Step 2:** убедиться, что падают.
- [ ] **Step 3:** реализация: клиент `client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(timeoutMs)`; ручной цикл редиректов (3xx + `Location`, проверка схемы на каждом шаге); `Call.await()`; чтение `body.source()` не больше `maxBytes + 1`; всё в `Dispatchers.IO`.
- [ ] **Step 4:** `--tests "*TorrentFileFetcherTest*"` — PASS.
- [ ] **Step 5:** commit `feat(stream): add bounded cancellable .torrent fetcher`.

### Task 3: вход движка из `.torrent`

**Files:**
- Modify: `app/src/main/java/dev/gamspi/videogallery/stream/TorrentEngine.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/stream/Libtorrent4jEngine.kt`
- Modify: `app/src/test/java/dev/gamspi/videogallery/stream/FakeTorrentEngine.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/stream/FallbackTrackersTest.kt`

**Interfaces:**
- Produces:
  - `fun torrentHashOf(bytes: ByteArray): String? = null` — v1 info-hash в нижнем регистре или `null` (ошибка разбора, нет v1).
  - `suspend fun add(magnet: String, saveDir: File, torrentFile: File? = null): String` — заменяет прежнюю сигнатуру (параметр по умолчанию сохраняет вызовы).
  - `internal fun mergeTrackers(vararg sources: List<String>): List<String>` в `Libtorrent4jEngine.kt`: порядок первых вхождений, без дублей и пустых, затем `withFallbackTrackers`.
  - `FakeTorrentEngine`: `var torrentHash: (ByteArray) -> String? = { null }`, `val addedFiles = mutableListOf<File?>()`.

- [ ] **Step 1: тесты** `FallbackTrackersTest.mergeTrackersKeepsOrderAndDropsDuplicates`: `mergeTrackers(listOf("http://a", "http://b"), listOf("http://b", "", "udp://c"))` → начинается с `["http://a", "http://b", "udp://c"]`, далее запасные из `withFallbackTrackers` без повторов.
- [ ] **Step 2:** убедиться, что падает.
- [ ] **Step 3:** реализация в `Libtorrent4jEngine`:
  - `torrentHashOf`: `TorrentInfo.bdecode(bytes)` (или эквивалент libtorrent4j 2.1 — сверить по API через `javap`/исходники), `infoHashes().hasV1()` → `getV1().toHex().lowercase()`, исключения → `null`.
  - `add` с файлом: `AddTorrentParams` с `TorrentInfo(torrentFile)`; трекеры `mergeTrackers(трекеры файла, трекеры magnet)`; `savePath`, флаги, ожидание `removing`/`uncertainAdds`/поиск handle — тот же код, что для magnet (вынести общее, не дублировать). Хэш для каталога — `hashOf(magnet)`; если хэш `TorrentInfo` другой — `error(...)` до `asyncAddTorrent`.
  - Без файла поведение прежнее.
- [ ] **Step 4:** `--tests "*FallbackTrackersTest*" --tests "*StreamManagerTest*"` — PASS; `assembleDebug` — компилируется.
- [ ] **Step 5:** commit `feat(stream): add torrents from .torrent metadata`.

### Task 4: выбор пути в `StreamManager`

**Files:**
- Modify: `app/src/main/java/dev/gamspi/videogallery/stream/StreamManager.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/stream/StreamManagerTest.kt` (новые тесты; можно отдельный `StreamManagerTorrentFileTest.kt`)

**Interfaces:**
- Consumes: `TorrentFileSource` (задача 2), `torrentHashOf`/`add(..., torrentFile)` (задача 3).
- Produces: конструктор `StreamManager(..., torrentFiles: TorrentFileSource? = null)`; `fun start(magnet: String, title: String, torrentUrl: String? = null)`.

Алгоритм `start` (внутри `guarded`, после `predecessor.join()`, **до** `withContext(NonCancellable)`):
1. `hash = engine.hashOf(magnet)`; если `null` — старый путь.
2. На `Dispatchers.IO`: прочитать `torrents/<hash>/meta.torrent` (если есть и ≤ 5 МБ); если `torrentHashOf(bytes) != hash` — удалить файл, `bytes = null`.
3. Если `bytes == null` и `torrentUrl != null` — `torrentFiles.fetch(url)`; если `torrentHashOf(fetched) != hash` — `null`.
4. Внутри блока, после `cache.initialize()` и `acquire(hash)`: если `bytes != null` — атомарно записать `meta.torrent` (ошибка записи → magnet без файла), передать файл в `engine.add`.
5. Для приёмки на ТВ — одна строка лога об источнике (`stored`/`fetched`/`magnet`) через параметр-лямбду `log: (String) -> Unit = {}` конструктора; в `StreamModule` — `android.util.Log.i("StreamManager", it)`. URL не логировать целиком (только хост).

- [ ] **Step 1: тесты** (fake-движок с `hashOf` = хэш из magnet, fake `TorrentFileSource`, `filesDir` во временной папке):
  - `usesStoredTorrentWithoutFetching`: файл с правильным хэшем есть → `fetch` не вызван, `addedFiles.last()` = этот файл.
  - `fetchesAndStoresWhenMissing`: файла нет → `fetch(url)` один раз, файл записан, передан в `add`, `.tmp` не осталось.
  - `fallsBackToMagnetWhenFetchFails`: `fetch` → `null` → `add` с `torrentFile == null`, состояние не `Failed`.
  - `fallsBackOnHashMismatch`: `torrentHashOf` → другой хэш → `add` без файла, файл не записан.
  - `replacesCorruptStoredFile`: сохранённый файл с битым хэшем → удалён, вызван `fetch`.
  - `noUrlUsesMagnet`: `torrentUrl = null`, файла нет → `fetch` не вызван.
  - `cancelDuringFetchSkipsAdd`: `fetch` висит, `stopIf(sessionId)` → `fetch` отменён (`CancellationException` получен внутри fake), `engine.add` не вызван.
  - `readyKeyUnchanged`: путь с файлом даёт `Ready.key == "bt:<hash>:<fileIndex>"` как раньше.
- [ ] **Step 2:** убедиться, что падают.
- [ ] **Step 3:** реализация по алгоритму выше.
- [ ] **Step 4:** `--tests "*StreamManager*" --tests "*StreamStorageIntegrationTest*" --tests "*TorrentCacheStoreTest*"` — PASS.
- [ ] **Step 5:** commit `feat(stream): prefer provider .torrent over magnet`.

### Task 5: маршрут, ViewModel, DI

**Files:**
- Modify: `app/src/main/java/dev/gamspi/videogallery/ui/nav/AppNavHost.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/stream/StreamViewModel.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/stream/StreamModule.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/stream/StreamViewModelTest.kt`

**Interfaces:**
- Consumes: `StreamManager.start(magnet, title, torrentUrl)`, `TorrentFileFetcher`.
- Produces: `STREAM_ROUTE = "stream?magnet={magnet}&title={title}&imdb={imdb}&torrent={torrent}"`; пустая строка = нет ссылки.

- [ ] **Step 1: тест** `StreamViewModelTest.passesTorrentUrl`: `SavedStateHandle` с `torrent = "https://x/t"` → менеджер получил эту ссылку; пустая → `null`.
- [ ] **Step 2:** убедиться, что падает.
- [ ] **Step 3:** реализация: маршрут кодирует `r.torrentUrl.orEmpty()`; `StreamModule` создаёт `TorrentFileFetcher(OkHttpClient.Builder().build())` (отдельный клиент без HTTP-кэша каталога) и передаёт `torrentFiles` в `StreamManager`.
- [ ] **Step 4:** `./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest assembleDebug` — всё PASS, XML без failures, APK собран, ABI `armeabi-v7a`.
- [ ] **Step 5:** commit `feat(stream): pass provider .torrent url to streaming`.

### Task 6: приёмка на ТВ и документы

**Files:**
- Create: `docs/verification/2026-11-02-torrent-file.md`
- Modify: `docs/STATUS.md`, `docs/BACKLOG.md` (G-10), этот план (статус)

- [ ] **Step 1:** протокол: commit, устройство (Xiaomi MiTV-MOEU1, API 34, `armeabi-v7a`), сценарии и итог:
  1. раздача YTS открывается, в логе видно использование `meta.torrent`, файл есть в `torrents/<hash>/`;
  2. раздача rutor на ТВ (блок) открывается через magnet не позже ~10 с после выбора;
  3. повторный выбор той же раздачи берёт сохранённый файл без загрузки;
  4. выход с экрана во время загрузки `.torrent` — без падений и висящих сессий;
  5. выбор файла, воспроизведение и продолжение позиции как раньше.
- [ ] **Step 2:** STATUS/BACKLOG/план синхронизировать по фактическому результату; невыполненное записать как ограничение.
- [ ] **Step 3:** `git diff --check`, проверить ссылки; commit `docs: record G-10 torrent-file acceptance`.
