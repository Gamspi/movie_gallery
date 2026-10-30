# Этап 2б: плеер — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Полноэкранный плеер Media3/ExoPlayer, который играет любой http(s)-URL: управление пультом, полоса прогресса, меню «Аудио» с выбором озвучки, продолжение с места. Сейчас запускается из настроек по тестовому URL; в 2в его будет вызывать торрент-стример.

**Architecture:** Пакет `player`. Чистая логика без Android вынесена в отдельные файлы и покрыта тестами: модель дорожек и выбор озвучки, правило «продолжить», обработка клавиш, хранилище позиций и предпочтений. `PlayerViewModel` связывает логику и хранилище. `PlayerScreen` держит ExoPlayer: `remember` + `DisposableEffect`, рендер субтитров ExoPlayer выключен (свой оверлей — этап 3). Маршрут `player?url=…&title=…&key=…`; ключ позиции сейчас `url:<url>`, в 2в станет `bt:<hash>:<fileIndex>`.

**Tech Stack:** как раньше + `androidx.media3:media3-exoplayer` и `media3-ui` (последняя стабильная, одна версия `media3` в `libs.versions.toml`). ffmpeg-расширение отложено.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (модуль `player`). Решения пользователя на 2026-10-30:
- плеер проверяется через поле «Тестовый URL» в настройках;
- дорожки, которые ТВ не играет (DTS/TrueHD), показываются серыми с пометкой «не поддерж.», выбрать их нельзя;
- позиция сохраняется уже в 2б, при открытии — вопрос «Продолжить / Сначала»;
- озвучка запоминается по языку + label;
- меню по ↓ — только «Аудио»;
- оверлей — полоса прогресса.

## Global Constraints

- Пакет `dev.gamspi.videogallery`, новый код — в `player/`. Позиции и предпочтение озвучки — в обычных `SharedPreferences` (`"playback"`), не в `SecretStore`. Тестовый URL — через `SettingsRepository`/`SecretStore`, ключ `test_url`.
- Пульт: OK / Play-Pause / Media Play-Pause — пауза/играть; ←/→ — перемотка ±10 с с ограничением `[0, duration]`, автоповтор при удержании = повторная перемотка; ↓ — меню «Аудио»; «Назад» закрывает меню, если оно открыто, иначе выходит из плеера.
- Оверлей: любое нажатие показывает его на 3 с; на паузе виден постоянно. Состав: название, «0:12:34 / 2:19:00», полоса прогресса, значок паузы.
- Время: `h:mm:ss`, если длительность ≥ 1 ч, иначе `m:ss`.
- Продолжить: позиция < 60 с или > 95 % длительности считается «нет позиции»; во втором случае запись удаляется. Позиция пишется раз в 10 с, на паузе и при выходе.
- Озвучка: сначала поддерживаемая дорожка с тем же языком и label, затем первая поддерживаемая с тем же языком, иначе выбор ExoPlayer по умолчанию. Предпочтение сохраняется только при ручном выборе.
- Тексты (точно):
  - «Тестовый URL», «Играть», «Аудио», «не поддерж.», «Продолжить с 1:23:45», «Сначала», «Не удалось воспроизвести», «Назад»;
  - название дорожки без label и языка — «Дорожка N» (N с 1).
- Экран не гаснет, пока идёт воспроизведение (`keepScreenOn`).
- Файлы писать только через Edit/Write (UTF-8). Перед коммитом `git ls-files '*.kt' '*.xml' '*.kts' '*.json' '*.toml' '*.md' | py -I "$TEMP/u8.py"` должен вывести только `done`.
- Сборка: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`, запускать на переднем плане; после сборки проверить XML результатов. Контейнер `tvadb` не трогать.

## Review Focus

1. Предпочтённая озвучка есть только в DTS (не поддерж.) → выбирается поддерживаемая дорожка того же языка, а не тишина. — Task 1.
2. Удержание ←/→ у начала или конца → позиция зажата в `[0, duration]`, без отрицательных значений и выхода за конец; при неизвестной длительности (`C.TIME_UNSET`) перемотка вперёд не ограничивается. — Task 1.
3. «Назад» во время буферизации или ошибки → нет падения, плеер освобождается, позиция 0 не затирает сохранённую. — Task 2, Task 3.
4. Язык дорожки `und`/null и пустой label → «Дорожка N», а не «null». — Task 1.
5. Битый или недоступный URL → «Не удалось воспроизвести» + «Назад», а не чёрный экран. — Task 3.

---

### Task 1: Чистая логика плеера

**Files:**
- Create: `player/AudioTracks.kt`, `player/Resume.kt`, `player/PlayerKeys.kt`, `player/PlaybackStore.kt`
- Test: `player/AudioTracksTest.kt`, `player/ResumeTest.kt`, `player/PlayerKeysTest.kt`

**Interfaces:**
- Produces:
  - `data class AudioTrack(val group: Int, val index: Int, val language: String?, val label: String?, val codec: String?, val supported: Boolean)`
  - `fun AudioTrack.displayName(ordinal: Int): String`:
    - язык как ISO-639 тег с заглавной («Rus», «Eng»); `und`/null/пустой не выводится;
    - затем label, затем кодек в верхнем регистре («AC3», «DTS») через « · »;
    - пример: «Rus, MVO LostFilm · AC3»;
    - если нет ни языка, ни label — «Дорожка $ordinal»;
    - неподдерживаемая получает суффикс « — не поддерж.».
  - `data class AudioPreference(val language: String, val label: String?)`
  - `fun chooseAudio(tracks: List<AudioTrack>, pref: AudioPreference?): AudioTrack?` — правило из Global Constraints; сравнение языка и label без учёта регистра.
  - `fun resumeFrom(savedMs: Long?, durationMs: Long): Long?` — null, если позиции нет; при `durationMs <= 0` проверяется только порог 60 с.
  - `fun isFinished(positionMs: Long, durationMs: Long): Boolean` — больше 95 %.
  - `fun formatTime(ms: Long, durationMs: Long): String`
  - `enum class RemoteKey { Center, PlayPause, Left, Right, Down, Back }`
  - `sealed interface PlayerCommand { TogglePlay; SeekTo(ms: Long); OpenAudioMenu; CloseMenu; Exit }`
  - `fun handleKey(key: RemoteKey, positionMs: Long, durationMs: Long, menuOpen: Boolean): PlayerCommand` — `durationMs` может быть `C.TIME_UNSET` (`Long.MIN_VALUE + 1`); такое значение считать отрицательным = «неизвестно».
  - `interface PlaybackStore`:
    - `fun position(key: String): Long?`
    - `fun savePosition(key: String, ms: Long)`
    - `fun clearPosition(key: String)`
    - `fun audioPreference(): AudioPreference?`
    - `fun saveAudioPreference(p: AudioPreference)`
  - `class SharedPrefsPlaybackStore(prefs: SharedPreferences) : PlaybackStore`
  - тестовый `FakePlaybackStore` в `app/src/test`.

- [ ] **Step 1: Тесты**
  - `AudioTracksTest`:
    - `displayName_languageLabelCodec` — «Rus, MVO LostFilm · AC3»;
    - `displayName_noLanguageNoLabel_ordinal` — `und`, label null → «Дорожка 2»;
    - `displayName_unsupported_suffix`;
    - `choose_exactLanguageAndLabel`;
    - `choose_sameLanguageOtherLabel`;
    - `choose_preferredOnlyUnsupported_takesSupportedSameLanguage` (Review Focus 1);
    - `choose_noMatch_null`;
    - `choose_noPreference_null`.
  - `ResumeTest`:
    - `under60s_null`;
    - `over95_null`;
    - `middle_returnsSaved`;
    - `unknownDuration_onlyMinCheck`;
    - `formatTime_hours` — 5025000 мс при длительности 2 ч → «1:23:45»;
    - `formatTime_minutes` — 245000 мс → «4:05».
  - `PlayerKeysTest`:
    - `center_toggles`;
    - `left_clampsAtZero`;
    - `right_clampsAtDuration`;
    - `right_unknownDuration_unclamped`;
    - `down_opensMenu`;
    - `back_menuOpen_closes`;
    - `back_menuClosed_exits`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*player*'` → FAIL (нет классов).
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: player logic — audio choice, resume, remote keys, playback store`.

### Task 2: PlayerViewModel, тестовый URL, маршрут

**Files:**
- Create: `player/PlayerViewModel.kt`, `player/PlayerModule.kt` (Hilt: `PlaybackStore` → `SharedPrefsPlaybackStore(context.getSharedPreferences("playback", MODE_PRIVATE))`)
- Modify: `settings/SettingsRepository.kt` (`testUrl: StateFlow<String?>`, `setTestUrl(url)`: trim, пустое → null), `settings/SettingsViewModel.kt`, `settings/SettingsScreen.kt`, `ui/nav/AppNavHost.kt`
- Test: `player/PlayerViewModelTest.kt`, `settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `PlayerViewModel(savedState: SavedStateHandle, store: PlaybackStore)`; аргументы `url`, `title`, `key` (если `key` пуст, берётся `"url:$url"`).
  - `data class PlayerUiState(val title: String, val url: String, val resumeOffer: Long? = null, val error: String? = null)`, `val state: StateFlow<PlayerUiState>`.
  - Методы:
    - `fun onDuration(durationMs: Long)` — один раз вычисляет `resumeOffer` через `resumeFrom`;
    - `fun dismissResume()`;
    - `fun onProgress(positionMs: Long, durationMs: Long)` — сохраняет не чаще раза в 10 с;
    - `fun onPauseOrExit(positionMs: Long, durationMs: Long)` — сохраняет сразу; если `isFinished`, запись удаляет; `positionMs <= 0` не пишет;
    - `fun chooseInitialAudio(tracks: List<AudioTrack>): AudioTrack?`;
    - `fun onAudioSelected(t: AudioTrack)` — сохраняет `AudioPreference`, только если у дорожки есть язык;
    - `fun onError(message: String)`.
  - Время для троттлинга — инжектируемый `clock: () -> Long` (по умолчанию `SystemClock.elapsedRealtime`).
  - Маршрут `"player?url={url}&title={title}&key={key}"`, строковые аргументы с `defaultValue = ""`, значения кодируются через `Uri.encode`.
  - Настройки: в конце экрана поле «Тестовый URL» и кнопка «Играть»; кнопка неактивна при пустом поле. Нажатие сохраняет URL и открывает `player` с title «Тест».

- [ ] **Step 1: Тесты**
  - `PlayerViewModelTest`:
    - `resumeOffer_fromStore`;
    - `resumeOffer_nullWhenShort`;
    - `progress_throttled10s` — три вызова за 5 с → одна запись; через 10 с → вторая;
    - `exit_savesImmediately`;
    - `exit_finished_clears`;
    - `exit_zero_keepsSaved` (Review Focus 3);
    - `audioSelected_savesPreference`;
    - `initialAudio_usesPreference`;
    - `keyDefaultsToUrl`.
  - `SettingsRepositoryTest`: `testUrl_trimsAndBlankClears`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: player view model, test URL setting, player route`.

### Task 3: PlayerScreen (ExoPlayer, оверлей, меню «Аудио», продолжение)

**Files:**
- Create: `player/PlayerScreen.kt`, `player/PlayerOverlay.kt`, `player/AudioMenu.kt`, `player/Media3Tracks.kt` (`fun Tracks.audioTracks(): List<AudioTrack>`: группы `C.TRACK_TYPE_AUDIO`, `supported = group.isTrackSupported(i)`, `codec` — короткое имя из `sampleMimeType`: `audio/ac3`→AC3, `audio/eac3`→EAC3, `audio/vnd.dts*`→DTS, `audio/true-hd`→TrueHD, `audio/mp4a-latm`→AAC, иначе часть после `/`)
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (media3)
- Test: `player/Media3TracksTest.kt` (сопоставление MIME → кодек, как чистая функция `codecName(mime: String?): String?`)

**Interfaces:**
- Consumes: Task 1, Task 2.
- Поведение экрана:
  - ExoPlayer создаётся в `remember`, `release()` вызывается в `onDispose`; перед освобождением вызывается `onPauseOrExit`.
  - Включено `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)`.
  - Видео выводится через `AndroidView(PlayerView)` с `useController = false`.
  - Клавиши ловятся в `onPreviewKeyEvent` корневого `Box`, у которого есть фокус, и превращаются в `RemoteKey`, затем в `handleKey`.
  - Прогресс опрашивается раз в 500 мс, пока экран активен.
  - На первом `onTracksChanged` с аудио: `chooseInitialAudio` → `TrackSelectionOverride`.
  - На `STATE_READY` с известной длительностью вызывается `onDuration`; пока висит `resumeOffer`, воспроизведение на паузе. Показываются две кнопки «Продолжить с …» (фокус) и «Сначала».
  - `onPlayerError` → `onError(error.errorCodeName)` → экран «Не удалось воспроизвести», мелким текстом код ошибки, кнопка «Назад».
  - Меню «Аудио» — боковая панель справа:
    - список дорожек, текущая отмечена «✓ »;
    - неподдерживаемые серые и `enabled = false`;
    - фокус при открытии стоит на текущей дорожке;
    - OK выбирает дорожку, вызывает `onAudioSelected` и закрывает меню.
  - `keepScreenOn = true` на корневом View, пока `isPlaying`.

- [ ] **Step 1:** Тест `codecName_mapsCommonMimes` → FAIL.
- [ ] **Step 2:** Реализовать, run `testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 3:** Commit `feat: player screen with overlay, audio menu and resume`.
- [ ] **Step 4 (контроллер, ТВ):**
  - Тестовое видео: MKV с двумя аудиодорожками (rus AC3 с label, eng AAC) и DTS-дорожкой. Сделать через ffmpeg в Docker и раздавать с ПК в LAN (`python -m http.server`). Проверить, что ТВ достаёт до ПК: `toybox nc` с ТВ.
  - Проверить:
    - пауза и перемотка, удержание →;
    - оверлей пропадает через 3 с;
    - ↓ открывает «Аудио», DTS серая, смена дорожки;
    - «Назад» закрывает меню, второй «Назад» выходит;
    - повторное открытие → «Продолжить с …»;
    - битый URL → «Не удалось воспроизвести».
