# Stage 3 — Dual Subtitles Implementation Plan

> Working rules: [AGENTS.md](../../../AGENTS.md). No external skill or delegation is required. Track verified implementation separately from historical TDD steps and device acceptance.

## Status — 2026-11-01

Audited application revision: `d1ea23e`. Full Docker unit rerun: 242 tests, no failures/errors/skips; debug assembly succeeded. No fresh TV/emulator acceptance was performed in this audit.

| Task | Status | Evidence / remaining work |
|---|---|---|
| 1. Parsers / track | Implemented, unit tests pass | `d03a1f4` |
| 2. OpenSubtitles | Implemented, unit tests pass | `4d6cc0f`, token fix `415a1c6`; G-02 cancellation and G-03 fallback implemented in working tree |
| 3. Settings | Implemented, unit tests pass | `825edd1`; settings are now connected to playback |
| 4. IMDb navigation | Implemented, unit tests pass | `d1ea23e` |
| 5. Embedded renderers | Implemented, phone gate PASS | Redmi Note 8 Pro API30; simultaneous cue, pause, seek and clearing confirmed |
| 6. Layer state / sourcing | Implemented, unit tests pass | G-02/G-03 included; manual choices and stale/cancelled responses tested |
| 7. Overlay / menu | Implemented, unit tests and assembly pass; phone smoke PASS | USB phone run: independent off/on A/B while paused, focus/scroll/Back, no-audio/no-key and focused-button contrast |
| 8. Device acceptance | Partial: phone smoke PASS | USB phone menu/off-on A/B/Back/no-key/contrast passed; target TV, physical remote and live OpenSubtitles pending; see probe report |

Implementation/test/commit checkboxes for tasks 1–4 below are updated from code, commits and the current verification. Historical steps explicitly requiring a failing test run stay unchecked: that sequence was not established by the audit. See [STATUS](../../STATUS.md) and [BACKLOG](../../BACKLOG.md) for the cross-stage view.

**Goal:** Show two subtitle languages at once, A above B, at the bottom of the video. Each layer takes subtitles from the file itself or from OpenSubtitles.

**Architecture:**
- New package `subs`, pure Kotlin, unit-tested:
  - parsers (SRT, WebVTT, ASS/SSA) produce `SubtitleTrack`, which can say which text shows at a given moment;
  - an OpenSubtitles client and the file hash;
  - `chooseSource`, which picks where a layer's subtitles come from.
- Subtitles inside the file come from Media3 through **two** text renderers. Each one draws into its own layer, and the built-in Media3 subtitle view is hidden.
- `PlayerViewModel` owns the state of both layers. `PlayerScreen` draws them with `SubtitleOverlay`. The ↓ key opens `TracksMenu`, which replaces `AudioMenu`.

**Tech Stack:** Kotlin, Compose for TV, Media3 1.11.1 (exoplayer/ui), Retrofit + kotlinx.serialization + OkHttp, JUnit4 + MockWebServer3 + Turbine + coroutines-test, Hilt.

**Spec:** `docs/superpowers/specs/2026-10-29-android-tv-torrent-gallery-design.md` (sections "Плеер" and "Субтитры").

## Decisions settled with the user (2026-10-31)
- Both layers sit at the bottom, stacked. Language A (default `en`) is on top, language B (default `ru`) is right below it. The user asked to "do it per the spec, we'll adjust later".
- Order of sources for each language: tracks inside the file → OpenSubtitles (by file hash, then by imdb id). Translation is stage 4 and is not part of this stage.

## Calls I made (the user can correct them)
- Stage 3 currently limits time offsets to external (OpenSubtitles) subtitles. Media3 drives embedded cue timing; embedded tracks are not guaranteed to be correctly synchronized. The narrower scope versus the original spec is tracked as G-07; the UI must make the limitation explicit.
- Offsets live in two places:
  - **Settings:** the starting offset for each layer.
  - **Player menu «Сдвиг»:** ±0,5 s for this viewing only. It is not saved.
- A track picked by hand in the menu is not saved. The settings store the languages, and on every launch the source is chosen automatically by language.
- **OpenSubtitles account:**
  - the API key is required;
  - username and password are optional; if they are set, downloads use a token, which gives a higher daily limit;
  - every OpenSubtitles request sends `User-Agent: VideoGallery v1.0`, which the API requires to name the app. This is our own app name, not someone else's.
- **Style:**
  - size is one of «Мелкий» 28sp / «Средний» 36sp / «Крупный» 46sp;
  - colour is one of «Белый» #FFFFFF / «Жёлтый» #FFEB3B / «Голубой» #80D8FF;
  - the text has a black outline (shadow) and no background box.
- While the player overlay (title, progress bar) is visible, the subtitles rise above it.

## Global Constraints
- Builds and tests run only through Docker: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug`.
- Files are UTF-8 without BOM. See [TESTING](../../TESTING.md) for checks; an untracked temporary `u8.py` is not required.
- Follow the current user instructions and repository workflow for branches and commits. Do not add attribution for an agent that did not contribute.
- Keys and passwords are stored only through `SecretStore` (EncryptedSharedPreferences).
- Parsers keep only text and timings; styles are ignored (spec).
- Code comments are in English and short. UI strings are in Russian.
- Every new network/IO function rethrows `CancellationException` (rule R3 from stage 2a). Cancelling the coroutine must also cancel the underlying HTTP call, including body reads; see G-02.
- If no subtitles are found for a layer, the layer stays empty and the track menu marks it «нет субтитров» (spec, errors table).
- OpenSubtitles API: base `https://api.opensubtitles.com/api/v1/`, header `Api-Key: <key>`, header `User-Agent: VideoGallery v1.0`. Before writing the client, check the endpoint fields against the official docs (opensubtitles.stoplight.io, via context7 or the web). The fields named in the plan are the expected ones.

## Review Focus
1. **Subtitle file in cp1251, or with a BOM.** Russian SRT files are often cp1251. Expect correct Cyrillic, not mojibake (Task 1: test `decodes_cp1251_fallback`, `strips_utf8_bom`).
2. **Overlapping cues, or a position exactly on a cue boundary.** Expect both lines from overlapping cues, and nothing at `endMs` (end is exclusive) (Task 1: `textAt_overlapping_joins_lines`, `textAt_end_exclusive`).
3. **No key, wrong key (401), or download limit reached (406/429).** Expect the layer to stay empty, the menu to show the reason, and the player not to crash (Task 2 + Task 6: `download_limit_maps_to_LimitReached`, `layer_shows_reason_on_auth_error`).
4. **Both layers set to the same language, or a file with only one embedded track of that language.** Expect B to use OpenSubtitles or stay empty; one embedded track is not given to both layers if a different source exists (Task 6: `same_language_both_layers`).
5. **The user leaves the player while subtitles are downloading.** Expect the request to be cancelled, with no crash and no write into a dead state (Task 6: `cancel_on_clear`).

---

### Task 1: Parsers and `SubtitleTrack`

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/subs/SubtitleTrack.kt`
- Create: `app/src/main/java/dev/gamspi/videogallery/subs/SubtitleParser.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/subs/SubtitleParserTest.kt`, `SubtitleTrackTest.kt`

**Interfaces:**
- Produces:
  - `data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)`
  - `class SubtitleTrack(cues: List<SubtitleCue>)`. It sorts the cues by `startMs` and provides `fun textAt(positionMs: Long): String?`. When several cues overlap, their texts are joined with `\n`; outside any cue it returns `null`. The interval is `[start, end)`.
  - `object SubtitleParser { fun parse(bytes: ByteArray, fileName: String?): SubtitleTrack }`:
    - the format is chosen by the extension (`.srt/.vtt/.ass/.ssa`) or, failing that, by the content (`WEBVTT` header, `[Script Info]`, otherwise SRT);
    - garbage input gives an empty track, never an exception.
  - `fun decodeSubtitleText(bytes: ByteArray): String` (internal). It strips a UTF-8 BOM, and if strict UTF-8 decoding fails it falls back to `windows-1251`.

- [ ] **Step 1: Write the failing tests**
  - `srt_basic`: input `"1\n00:00:01,000 --> 00:00:02,500\nHello\nworld\n\n2\n00:00:03,000 --> 00:00:04,000\nBye\n"`; expect 2 cues; `cues[0] == SubtitleCue(1000, 2500, "Hello\nworld")`.
  - `srt_crlf_and_html_tags`: line endings are `\r\n` and the text is `<i>Hi</i> {\an8}there`; expect `"Hi there"`. Strip `<...>` tags and `{...}` overrides.
  - `vtt_basic`: `"WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nHi\n"`; expect `SubtitleCue(1000, 2000, "Hi")`. Hours are optional and cue settings after the time are ignored.
  - `ass_dialogue`:
    - the `[Events]` section has `Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text` and `Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\i1}Line one\NLine two`;
    - expect `SubtitleCue(1500, 3000, "Line one\nLine two")`. The `Text` field is the rest of the line after the 9th comma; `\N` and `\n` become a line break and `{...}` is removed.
  - `decodes_cp1251_fallback`: the bytes of `"Привет"` in `windows-1251` give `"Привет"`.
  - `strips_utf8_bom`: `EF BB BF` + `"1\n00:00:00,000 --> 00:00:01,000\nA\n"`; the first cue is `"A"`.
  - `garbage_gives_empty_track`: `"not a subtitle"` gives `textAt(0) == null` and no exception.
  - `textAt_overlapping_joins_lines`: cues (0..2000 "A") and (1000..3000 "B"); `textAt(1500) == "A\nB"`.
  - `textAt_end_exclusive`: cue (1000..2000 "A"); `textAt(2000) == null` and `textAt(1999) == "A"`.

- [ ] **Step 2: Run them and confirm they fail.** Run `scripts/gradle-docker.sh ./gradlew testDebugUnitTest --tests 'dev.gamspi.videogallery.subs.*'`. Expected: FAIL (unresolved references).
- [x] **Step 3: Implement** `SubtitleTrack`, `SubtitleParser`, `decodeSubtitleText` with the signatures above. In `textAt`, use binary search over `startMs`, then look back over cues that can still be active (overlap). Keep a precomputed max duration so the look-back is bounded.
- [x] **Step 4: Run the tests.** PASS in the full rerun on 2026-10-31.
- [x] **Step 5: Commit** `feat(subs): SRT/VTT/ASS parsers and cue lookup` (`d03a1f4`).

---

### Task 2: OpenSubtitles: file hash and client

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/subs/OpenSubtitlesHash.kt`
- Create: `app/src/main/java/dev/gamspi/videogallery/subs/OpenSubtitlesApi.kt` (Retrofit + DTOs)
- Create: `app/src/main/java/dev/gamspi/videogallery/subs/OpenSubtitlesClient.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/subs/OpenSubtitlesHashTest.kt`, `OpenSubtitlesClientTest.kt`
- Fixtures: `app/src/test/resources/opensubtitles/search.json`, `download.json`

**Interfaces:**
- Produces:
  - `fun openSubtitlesHash(size: Long, head: ByteArray, tail: ByteArray): String`.
    - Classic OSDb hash: `size` plus the sum of little-endian uint64 words of the first and last 64 KiB, modulo 2^64.
    - Formatted as 16 hex characters, lower case.
    - `head` and `tail` are each 65536 bytes, or the whole file if it is shorter.
  - `suspend fun fetchHashOverHttp(client: OkHttpClient, url: String): String?`.
    - Makes two Range requests: `bytes=0-65535`, then `bytes=<size-65536>-<size-1>`.
    - Takes `size` from `Content-Range: bytes 0-65535/<size>`.
    - Returns `null` if the server does not support Range.
    - Works for the local torrent stream (`StreamServer`) and for the test URL.
  - `sealed interface SubsResult<out T>`:
    - `data class Ok<T>(val value: T)`;
    - `data object NoKey`;
    - `data object BadKey` (401/403);
    - `data object LimitReached` (406/429, or `remaining <= 0`);
    - `data class Failed(val message: String)`.
  - `data class RemoteSubtitle(val fileId: Long, val language: String, val release: String, val hashMatch: Boolean, val downloads: Int)`
  - `class OpenSubtitlesClient(baseUrl: String, settings: SubtitleSettingsSource, http: OkHttpClient)`, which provides:
    - `suspend fun search(hash: String?, imdbId: String?, languages: List<String>): SubsResult<List<RemoteSubtitle>>`:
      - `GET subtitles` with `moviehash` (when hash is not null) and/or `imdb_id`. The `imdb_id` value is the number without `tt` and without leading zeros.
      - `languages` is a comma-joined list such as `en,ru`.
      - Results are sorted by `hashMatch` first, then by `downloads` descending.
      - If hash and imdb are both null, it returns `Ok(emptyList())` without making a request.
    - `suspend fun download(fileId: Long): SubsResult<ByteArray>`:
      - `POST download` with `{"file_id": N}` returns `link`; a `GET` on that link returns the bytes.
      - If a username and password are set, it first calls `POST login` and sends `Authorization: Bearer <token>`. The token is cached in memory.
  - `interface SubtitleSettingsSource { val openSubtitlesKey: String?; val openSubtitlesUser: String?; val openSubtitlesPassword: String? }`. Task 3 implements it through `SettingsRepository`.
- Expected response fields (check them against the docs):
  - search: `data[].attributes.language`, `.release`, `.download_count`, `.moviehash_match`, `.files[0].file_id`;
  - download: `link`, `remaining`;
  - login: `token`.

- [ ] **Step 1: Write the failing tests**
  - `hash_known_vector`: for a synthetic 131072-byte file where byte i = `(i % 256)`, the result equals the value computed by a reference Python snippet (`struct.unpack('<Q')` over head+tail + size). Put the number into the test as a constant and explain in a test comment how it was computed.
  - `hash_small_file`: for a file shorter than 64 KiB, head and tail are the whole file. It returns 16 characters and does not crash.
  - `fetchHash_over_range`: MockWebServer answers 206 with `Content-Range`. The function returns a hash equal to `openSubtitlesHash` of the same bytes.
  - `fetchHash_no_range_returns_null`: the server answers 200 with no Content-Range, so the result is `null`.
  - `search_sends_headers_and_sorts`:
    - check `Api-Key` and `User-Agent: VideoGallery v1.0`;
    - check that `imdb_id=63350` comes from `tt0063350`;
    - check that results with `moviehash_match=true` come first.
  - `search_without_key_is_NoKey`: no request is made.
  - `search_401_is_BadKey`.
  - `download_limit_maps_to_LimitReached`: download answers 406.
  - `download_fetches_link_bytes`: `link` points to the same MockWebServer, and the bytes come back.
  - `download_uses_login_token_when_credentials_set`: `POST login` happens once for two downloads.
- [ ] **Step 2: Run them and confirm they fail.**
- [x] **Step 3: Implement** the files above. Build Retrofit the same way as `KinopoiskCatalogRepository.create`. Run network and IO on `Dispatchers.IO`.
- [x] **Step 4: Run the tests.** PASS in the full rerun on 2026-10-31. Additional cancellation/fallback coverage is still required by G-02/G-03.
- [x] **Step 5: Commit** `feat(subs): OpenSubtitles hash and API client` (`4d6cc0f`; token fix `415a1c6`).

**Integration follow-ups — resolved in working tree:** G-03 is owned by client.search: hash-only exact matches, then IMDb-only for missing languages; partial errors preserved as per-language notes. G-02 now cancels and closes HTTP including body reads; delayed headers/body and VM cancellation tests pass. Live API/device acceptance remains task 8.

---

### Task 3: Subtitle settings

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/settings/SubtitleSettings.kt` (models and defaults)
- Modify: `app/src/main/java/dev/gamspi/videogallery/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/settings/SettingsViewModel.kt`, `SettingsScreen.kt`
- Create/Modify: `app/src/main/java/dev/gamspi/videogallery/subs/SubsModule.kt` (Hilt: provides `OpenSubtitlesClient`; `SettingsRepository` is bound as `SubtitleSettingsSource`)
- Test: `app/src/test/java/dev/gamspi/videogallery/settings/SettingsRepositoryTest.kt`, `SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SubtitleSettingsSource` (Task 2).
- Produces:
  - `enum class SubSize(val sp: Int, val label: String) { Small(28, "Мелкий"), Medium(36, "Средний"), Large(46, "Крупный") }`
  - `enum class SubColor(val argb: Long, val label: String) { White(0xFFFFFFFF, "Белый"), Yellow(0xFFFFEB3B, "Жёлтый"), Cyan(0xFF80D8FF, "Голубой") }`
  - `data class LayerStyle(val size: SubSize = SubSize.Medium, val color: SubColor = SubColor.White, val offsetMs: Long = 0)`
  - `data class SubtitlePrefs(val languageA: String = "en", val languageB: String = "ru", val styleA: LayerStyle = LayerStyle(), val styleB: LayerStyle = LayerStyle(color = SubColor.Yellow))`
  - `SettingsRepository`:
    - `val subtitlePrefs: StateFlow<SubtitlePrefs>`;
    - `fun setSubtitlePrefs(p: SubtitlePrefs)`;
    - `fun setOpenSubtitles(key: String, user: String, password: String)`, where empty strings become `null`;
    - it implements `SubtitleSettingsSource`.
- Settings screen:
  - a new section «Субтитры» with these fields:
    - «Язык A» and «Язык B», each a button that cycles `en → ru → de → fr → es → it → ja → en`;
    - «Размер A/B» and «Цвет A/B», each cycling through its enum;
    - «Сдвиг A/B» as `−0,5 с` / value / `+0,5 с`, in 500 ms steps, range ±10 s;
    - `TvTextField` fields «OpenSubtitles ключ», «OpenSubtitles логин» and «OpenSubtitles пароль» (the password is masked).
  - D-pad navigation and saving follow the existing `TvTextField` pattern. The keyboard opens only on OK.

- [ ] **Step 1: Write the failing tests**
  - `subtitlePrefs_defaults`: A=`en`, B=`ru`, B is yellow.
  - `subtitlePrefs_persist_roundtrip`: after `setSubtitlePrefs`, a new `SettingsRepository` built on the same `FakeSecretStore` returns the same prefs.
  - `openSubtitles_blank_is_null`.
  - Add one test to `SettingsViewModelTest` for each new VM method (cycle language, change offset, clamp to ±10 s).
- [ ] **Step 2: Run them and confirm they fail.**
- [x] **Step 3: Implement.** Store the prefs as separate keys in `SecretStore`, in the same style as the trackers.
- [x] **Step 4: Run the tests and `assembleDebug`.** Verified on 2026-10-31; unit tests rerun, assembly up-to-date and successful.
- [x] **Step 5: Commit** `feat(settings): subtitle languages, styles, OpenSubtitles credentials` (`825edd1`).

---

### Task 4: Pass the imdb id into the player

**Files:**
- Modify: `app/src/main/java/dev/gamspi/videogallery/releases/ReleasesViewModel.kt` (add `imdbId: String?` to `Content`), `ReleasesScreen.kt` (change `onPlay` to `(TorrentResult, String?) -> Unit`)
- Modify: `app/src/main/java/dev/gamspi/videogallery/ui/nav/AppNavHost.kt`:
  - routes become `stream?magnet={magnet}&title={title}&imdb={imdb}` and `player?url={url}&title={title}&key={key}&imdb={imdb}`;
  - the default `imdb` value is `""`.
- Modify: `app/src/main/java/dev/gamspi/videogallery/stream/StreamScreen.kt`/`StreamViewModel.kt`:
  - pass `imdb` through to `onReady(url, key, title, imdb)`.
- Modify: `PlayerViewModel.kt`:
  - read `savedState["imdb"]`, with blank becoming `null`.
- Test: `ReleasesViewModelTest` (`imdbId` comes from `details.movie.imdbId`) and `StreamViewModelTest`/`PlayerViewModelTest` (the argument is read).

- [ ] **Step 1:** Write the failing tests named above.
- [ ] **Step 2:** Run them; expected FAIL.
- [x] **Step 3:** Implement. Encode every argument with `Uri.encode`, as is done now.
- [x] **Step 4:** Run the tests and `assembleDebug`. Verified on 2026-10-31; unit tests rerun, assembly up-to-date and successful.
- [x] **Step 5:** Commit `feat(nav): carry imdb id to the player` (`d1ea23e`).

---

### Task 5: Two embedded text tracks at once (Media3)

The highest-risk task. Start it with a short experiment.

**Current experiment:** implementation and debug-only `DualTextProbeActivity` are in the working tree. The user chose Redmi Note 8 Pro API30 (`armeabi-v7a` supported) instead of MiTV. Both embedded tracks played together, paused seek into cue/gap and resume passed. This unlocks tasks 6–7; phone results do not replace TV/D-pad acceptance. Commands, fixture and actual results: [probe report](../../verification/2026-10-31-dual-text-probe.md).

Docker unit/assembly verification after integration passed: 291 tests, no failures/errors/skips (includes G-02/G-03). Device gate PASS on phone; no commit was created.

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/player/DualText.kt`
- Modify: `app/src/main/java/dev/gamspi/videogallery/player/Media3Tracks.kt` (add `Tracks.textTracks()`)
- Test: `app/src/test/java/dev/gamspi/videogallery/player/Media3TracksTest.kt` (text tracks), and a pure-logic test for slot assignment

**Interfaces:**
- Produces:
  - `data class TextTrack(val group: Int, val index: Int, val language: String?, val label: String?, val supported: Boolean)`, plus `fun Tracks.textTracks(): List<TextTrack>`.
  - `class DualTextRenderersFactory(context: Context, val outA: (List<Cue>) -> Unit, val outB: (List<Cue>) -> Unit) : DefaultRenderersFactory`. It overrides `buildTextRenderers` to add **two** `TextRenderer`s.
  - `class DualTextTrackSelector(context: Context) : DefaultTrackSelector`, with `fun setTextSlots(a: Pair<Int, Int>?, b: Pair<Int, Int>?)`. Each pair is (track group index in `Tracks`, index within the group), or `null` for none. Each text renderer gets its own track. Possible way: override `selectAllTracks`; after `super`, put the definitions into the text-renderer slots, found through `MappedTrackInfo.getRendererType == C.TRACK_TYPE_TEXT`. Then call `invalidate()`.
  - The screen must not draw Media3 subtitles: `playerView.subtitleView?.visibility = View.GONE`. `setTrackTypeDisabled(TEXT, true)` must be removed, or the renderers get nothing.
- **Experiment gate:**
  - make the test file `subs2.mkv` (see Task 8) and check on a compatible TV that two tracks render at the same time. The current APK contains only `armeabi-v7a`; an emulator is an alternative only after its ABI/native-library compatibility is established;
  - if Media3 does not allow it within a reasonable effort (about 2 hours), stop and report **BLOCKED** with your findings;
  - fallback (the controller decides): one embedded track through the standard renderer for layer A only, with B from OpenSubtitles.

- [ ] **Step 1:** Write failing tests:
  - `textTracks_lists_text_groups_only`: a `Tracks` built from `Tracks.Group` objects with `MimeTypes.TEXT_VTT` and audio; only the text entries are returned, with language and label.
  - Pure function `fun textSlotDefinitions(textRenderers: List<Int>, a: Pair<Int, Int>?, b: Pair<Int, Int>?): Map<Int, Pair<Int, Int>>`. Test `slots_map_to_first_and_second_text_renderer` and `null_slot_leaves_renderer_empty`.
- [ ] **Step 2:** Run them; expected FAIL.
- [x] **Step 3:** Implement the factory, the selector and `textTracks()`. Also added an isolated debug probe; production screen integration remains task 7.
- [x] **Step 4:** Run the tests and `assembleDebug`, then try it on the device (gate). PASS on the user-selected phone; see report for limits.
- [ ] **Step 5:** Commit `feat(player): two simultaneous embedded text tracks`.

---

### Task 6: Layer logic in `PlayerViewModel`

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/subs/SubtitleSourcing.kt` (pure functions)
- Modify: `app/src/main/java/dev/gamspi/videogallery/player/PlayerViewModel.kt`
- Test: `app/src/test/java/dev/gamspi/videogallery/subs/SubtitleSourcingTest.kt`, `app/src/test/java/dev/gamspi/videogallery/player/PlayerViewModelTest.kt`

**Interfaces:**
- Consumes:
  - `SubtitleTrack` and `SubtitleParser` (T1);
  - `OpenSubtitlesClient`, `SubsResult`, `RemoteSubtitle` and `fetchHashOverHttp` (T2);
  - `SubtitlePrefs` and `LayerStyle` (T3);
  - `imdb` (T4);
  - `TextTrack` (T5).
- Produces:
  - `enum class Layer { A, B }`
  - `sealed interface LayerSource`, with these cases:
    - `data object Off`;
    - `data class Embedded(val track: TextTrack)`;
    - `data class Remote(val sub: RemoteSubtitle)`;
    - `data object None` (nothing found).
  - `fun chooseSource(language: String, embedded: List<TextTrack>, remote: List<RemoteSubtitle>, taken: TextTrack?): LayerSource`. The order is:
    1. a supported embedded track in this language whose group differs from `taken.group` (ProgressiveMediaPeriod permits only one stream per group, even for different indices);
    2. the first remote result in this language (the list is already sorted);
    3. `None`.
    - Languages are compared through ISO3 (`Locale.forLanguageTag(x).isO3Language`), so `en`, `eng` and `en-US` are all the same language.
  - `data class LayerState(val source: LayerSource = LayerSource.None, val track: SubtitleTrack? = null, val note: String? = null, val offsetMs: Long = 0, val style: LayerStyle = LayerStyle())`. `note` is the reason shown in the menu: «нет субтитров», «нужен ключ OpenSubtitles», «ключ OpenSubtitles неверный», «лимит OpenSubtitles на сегодня», «ошибка загрузки».
  - Additions to `PlayerViewModel`:
    - `val layers: StateFlow<Map<Layer, LayerState>>`;
    - `val remoteOptions: StateFlow<List<RemoteSubtitle>>`;
    - `fun onTextTracks(tracks: List<TextTrack>)`: the first call starts auto-selection. Without blocking playback, compute the hash with `fetchHashOverHttp(url)`, then search using that hash and apply the per-language IMDb fallback contract from G-03. Run `chooseSource` for A, then for B with `taken` = A's embedded track. For a `Remote` source call `download` and `parse`.
    - `fun select(layer: Layer, source: LayerSource)`, called from the menu;
    - `fun shiftOffset(layer: Layer, deltaMs: Long)`, clamped to ±10 s;
    - `fun textAt(layer: Layer, positionMs: Long): String?`: for `Remote` it uses `track.textAt(positionMs - offsetMs)`; for `Embedded` it returns `null`, because those cues come from the renderer.
    - `val embeddedSlots: StateFlow<Pair<Pair<Int, Int>?, Pair<Int, Int>?>>`, which the screen passes to `DualTextTrackSelector.setTextSlots`.
  - Network work runs in `viewModelScope`; G-02 must also connect cancellation to each underlying call/body read. Cancel obsolete layer requests when selection changes and prevent late results from overwriting the current selection.
  - Constructor: add `client: OpenSubtitlesClient`, `prefs: StateFlow<SubtitlePrefs>` (or `SettingsRepository`) and `http: OkHttpClient` to the internal constructor. Tests use fakes or MockWebServer.

- [ ] **Step 1: Write the failing tests**
  - `SubtitleSourcingTest`:
    - `embedded_preferred_over_remote`;
    - `iso3_matching_en_eng`;
    - `remote_when_no_embedded`;
    - `none_when_nothing`;
    - `same_language_both_layers`: there is one embedded `en` track and remote `en`. A gets Embedded and B gets Remote. With no remote, B gets None.
  - `PlayerViewModelTest`:
    - `autoselect_downloads_remote_for_B`: a fake client returns a result for `ru` plus SRT bytes. Then `layers[B].track.textAt(...)` returns the text.
    - `layer_shows_reason_on_auth_error`: search returns `BadKey`, so `note == "ключ OpenSubtitles неверный"`.
    - `no_key_note`: with `NoKey` and no embedded tracks, `note == "нужен ключ OpenSubtitles"`.
    - `offset_applies_to_remote_and_clamps`: `shiftOffset(B, +500)` moves `textAt`; ten thousand calls stop at 10 000.
    - `cancel_on_clear`: download hangs; `onCleared` (through `ClearViewModel.kt`) causes no exception and no write into `layers`.
- [ ] **Step 2:** Run them; expected FAIL.
- [x] **Step 3:** Implement. Includes G-02 VM cancellation and G-03 client integration.
- [x] **Step 4:** Run the tests; PASS in the 291-test full run.
- [ ] **Step 5:** Commit `feat(subs): layer sourcing and state in player`.

---

### Task 7: Overlay and track menu

**Files:**
- Create: `app/src/main/java/dev/gamspi/videogallery/player/SubtitleOverlay.kt`
- Create: `app/src/main/java/dev/gamspi/videogallery/player/TracksMenu.kt` (replaces `AudioMenu.kt`; delete `AudioMenu.kt`)
- Modify: `app/src/main/java/dev/gamspi/videogallery/player/PlayerScreen.kt`, `PlayerOverlay.kt` (hint `↓ Аудио` becomes `↓ Дорожки`)
- Test: pure helpers only, e.g. `fun layerOptions(...)` for menu rows. There are no Compose UI tests.

**Interfaces:**
- Consumes: everything from T5–T6.
- `SubtitleOverlay(textA: String?, textB: String?, styleA: LayerStyle, styleB: LayerStyle, liftDp: Dp, modifier)`:
  - a column at the bottom centre with A on top and B under it, and 8dp between them;
  - the text is centred, at most 3 lines per layer, with a black outline/shadow;
  - bottom padding is 48dp + `liftDp`, where `liftDp` is the overlay height while the overlay is visible (fixed at 170dp);
  - an empty layer takes no space.
- `PlayerScreen`:
  - creates `ExoPlayer` with `DualTextRenderersFactory` and `DualTextTrackSelector`. Embedded cues go into `mutableStateOf<String?>` for each layer: `cues.joinToString("\n") { it.text }`;
  - for `Remote` layers, a `LaunchedEffect` loop every 100 ms reads `player.currentPosition` and calls `vm.textAt(layer, pos)`;
  - `onTracksChanged` also calls `vm.onTextTracks(tracks.textTracks())`;
  - `embeddedSlots` is collected and passed to `setTextSlots`.
  - The ↓ key opens the tracks menu even if there are no audio tracks; subtitle controls must remain accessible.
- `TracksMenu`:
  - a right-hand panel 420dp wide;
  - sections in this order, each starting with a heading:
    - «Аудио»: the current list;
    - «Субтитры A (en)» and «Субтитры B (ru)», each listing:
      - «Выкл»;
      - all embedded tracks, labelled `TextTrack` language + label, such as «Eng, SDH»;
      - up to 5 `remoteOptions` in the layer's language, labelled «OpenSubtitles · <release>»;
      - if the list is empty, a disabled row «нет субтитров» or the `note`;
      - the current item is marked `✓`;
    - «Сдвиг» (only for a `Remote` layer): rows `A: −0,5 с · +1,5 с · +0,5 с` with three buttons.
  - Focus starts on the current audio track. D-pad up/down moves through the list. Back closes the menu (rule R1 of 2b stays: while the menu is open, only Back goes through `handleKey`).
  - Choosing a subtitle calls `vm.select` and does **not** close the menu, so the user can set both layers at once. Choosing audio closes it, as before.

- [ ] **Step 1:** Write failing tests for `layerOptions` (order: Выкл → embedded → remote, at most 5 remote, «нет субтитров» when the list is empty) and for `formatOffset(ms)`, where `1500 → "+1,5 с"`, `-500 → "−0,5 с"` and `0 → "0 с"`.
- [ ] **Step 2:** Run them; expected FAIL.
- [x] **Step 3:** Implement the UI and the wiring.
- [x] **Step 4:** Run the tests and `assembleDebug`; PASS in the 291-test full run.
- [ ] **Step 5:** Commit `feat(player): dual subtitle overlay and tracks menu`.

---

### Task 8: Check on the TV and record acceptance

Use [TESTING](../../TESTING.md) to record the application revision, device ABI/API and scenario results. LAN addresses and scratch files below are historical examples; establish the actual test setup before using them. G-09 stays open until device results exist.

- [ ] **Step 1: Test file.**
  - Write `eng.srt` and `rus.srt` (UTF-8, about 20 cues, 3 min) into the scratchpad `video/` folder. Mux them into `test3a.mkv` with ffmpeg in Docker:
    - `docker run --rm -v "$D:/w" linuxserver/ffmpeg -i /w/test3a.mkv -i /w/eng.srt -i /w/rus.srt -map 0 -map 1 -map 2 -c copy -c:s srt -metadata:s:s:0 language=eng -metadata:s:s:1 language=rus /w/subs2.mkv`
  - Also make `rus-cp1251.srt` with `iconv`, so cp1251 can be checked as an external file if needed.
- [ ] **Step 2: Serve the file and play it.** Start nginx `vg-http` (as in stage 2b). In Settings, set the test URL to `http://192.168.0.155:8000/subs2.mkv` and press «Играть». Expected: English on top, Russian below, both in sync, and nothing drawn by Media3 itself.
- [ ] **Step 3: Menu.**
  - Press ↓. The menu shows «Аудио», «Субтитры A/B» and «Сдвиг».
  - Switch B to «Выкл»: B disappears.
  - Switch it back: B returns.
- [ ] **Step 4: OpenSubtitles.**
  - Enter the key, from the user, in settings.
  - Play «Ночь живых мертвецов» 1968 from apibay.
  - Expected:
    - A takes the embedded track if there is one, otherwise OpenSubtitles;
    - B (ru) comes from OpenSubtitles, by hash or by imdb;
    - «Сдвиг» moves B.
- [ ] **Step 5: Errors.** With a wrong key, the menu shows «ключ OpenSubtitles неверный» and the player keeps playing.
- [ ] **Step 6:** Fix findings, rerun affected checks and perform the final whole-change review. Update the device report, STATUS and open backlog items; commit only as directed by the current workflow.
