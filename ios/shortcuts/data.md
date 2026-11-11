# Данные (Data Jar)

Единственный источник правды по полям записи. Меняешь поле — обнови этот файл и все шорткаты, которые его используют.

## Корень `movies`

Всё хранится в **одном** ключе верхнего уровня Data Jar — `movies` (Dictionary), чтобы не мешать другим кейсам в том же Data Jar. Других ключей верхнего уровня система не создаёт и не читает.

```
movies
├─ items      — ключ записи → запись
├─ settings   — настройки, справочники, API-ключи
├─ sync       — служебное синхронизации: дата, курсор, очередь правок
└─ episodes   — отметки серий из облака (для «Остановился: S2E5»)
```

| Путь | Тип | Что |
|---|---|---|
| `movies.items` | Dictionary | ключ записи → запись |
| `movies.settings` | Dictionary | настройки, справочники, API-ключи |
| `movies.sync.last_date` | Text | `yyyy-MM-dd` последнего полного забора изменений; = сегодня → Movie Sync в режиме `auto` не ходит в облако |
| `movies.sync.cursor` | Number | `next` последней прочитанной страницы `GET /v1/changes` |
| `movies.sync.queue` | Dictionary | ключ записи → строка облака `{key, data, updated_at, deleted}`, ещё не принятая воркером; удаляется целиком после удачной отправки |
| `movies.episodes.<key>.c<code>` | Text | просмотренная серия: `code` = сезон × 10000 + серия, значение `S2E5`; снятые и удалённые отметки стираются. Пишет только Movie Sync |
| `movies.seed_keys` | Array of Text | **временно**: ключи записей, созданных тестовым [Movie Seed](seed/README.md); удаляется его пунктом «Удалить тестовые». Убрать строку вместе с `seed/` |

Путь к полю: `movies.items.<key>.<field>`, напр. `movies.items.tt10466872.my_rating`. Точка — разделитель пути в Data Jar, поэтому в ключах записей точек нет.

## Ключ записи

По приоритету (шорткаты создают 1–3):

1. `imdb_id` — `tt10466872`.
2. `kp-<kinopoisk_id>` — `kp-1318972`.
3. `ai-<slug>-<year>` — `ai-the-brutalist-2024`.
4. `tmdb-tv-<id>` / `tmdb-movie-<id>` — создаёт только ТВ, когда у тайтла TMDB нет IMDb-id (`watchlistKey` в `app/src/main/java/dev/gamspi/videogallery/watchlist/ListRecord.kt`). Приходит синхронизацией.

Шорткаты принимают любой ключ: показывают те поля, что есть, правят статус, оценку, `notify`, теги, заметку. Облако принимает ключи по шаблону `tt` + 5–10 цифр, `kp-` + 1–10 цифр, `tmdb-movie-` / `tmdb-tv-` + 1–10 цифр, `ai-` + 1–80 символов `a-z0-9-`: более длинный `ai-`ключ воркер отвергнет (`bad_key_format`).

Slug для `ai-`:

1. **Match Text** `[a-zA-Z]` в названии. Нет ни одной латинской буквы (кириллица, «Сохранить как есть» с `Брат 2`) → slug не делать, ключ сразу `ai-<Current Date yyyyMMddHHmm>`. Иначе из `Брат 2` вышел бы кривой `ai--2-…`.
2. Есть латиница → lowercase → пробелы в `-` → удалить всё, кроме `a-z`, `0-9`, `-` (Replace Text, regex `[^a-z0-9-]` → пусто) → схлопнуть повторные `-` (regex `-+` → `-`) → срезать `-` по краям (regex `^-+|-+$` → пусто).

Год есть → `-<year>` в конце, в обоих случаях. Нет года → без него.

Ключ не меняется после сохранения. Если «Обновить данные» нашло `imdb_id` у записи с ключом `kp-`/`ai-`/`tmdb-` — запись остаётся под старым ключом, `imdb_id` пишется в поле. У записи без `kinopoisk_id` «Обновить данные» ищет poiskkino по названию (`title_en`, нет — `title_ru`) и году.

## Запись `movies.items.<key>`

| Поле | Тип | Источник | Описание |
|---|---|---|---|
| `key` | Text | вычисляется | дубль ключа записи |
| `title_en` | Text | poiskkino `enName` → `alternativeName` (если латиница) → ИИ | английское название |
| `title_ru` | Text | poiskkino `name` → ИИ | русское название |
| `year` | Number | poiskkino `year` / ИИ | год выхода (для сериала — первый год) |
| `type` | Text | маппинг `type` / ИИ | см. [Type](#type) |
| `genres` | Array of Text (1 шт. — Text) | маппинг `genres[].name` / ИИ | канонические англ. жанры |
| `imdb_id` | Text / null | `externalId.imdb` | `tt…` |
| `kinopoisk_id` | Number / null | `id` | id Кинопоиска |
| `ratings` | Dictionary | `rating.imdb`, `rating.kp` | `{imdb, kp}`, Number или null; `0` → null |
| `my_rating` | Number / null | пользователь | 1–10 |
| `status` | Text | пользователь | см. [Status](#status) |
| `tags` | Array of Text (1 шт. — Text) | пользователь | из `movies.settings.tags` |
| `note` | Text | пользователь | заметка |
| `length` | Number / null | `movieLength`; сериал — `seriesLength` | минуты; для сериала — средняя серия |
| `age_rating` | Number / null | `ageRating` | 0, 6, 12, 16, 18 |
| `premiere_date` | Text / null | `premiere.world` → `premiere.russia` | ISO-дата `YYYY-MM-DD` |
| `seasons_count` | Number / null | `seasonsInfo` (обычно null) → «Обновить данные» через `/v1.5/season` | только сериалы; номер последнего сезона |
| `release_status` | Text / null | вычисляется из `releaseYears` (только сериалы); `cancelled` — пользователь | см. [Release status](#release-status) |
| `next_episode` | Text / null | «Обновить данные» через `/v1.5/season` | только сериалы; ближайшая будущая дата: `S3E5 — 2026-11-12` или `Сезон 4 — 2027-03-01`; нет будущих дат → поле убрать |
| `actors` | Array of Text (1 шт. — Text) | `persons[]` с `enProfession` = `actor`, первые 5: `name`, иначе `enName` | для карточки и превью Add |
| `description` | Text | `shortDescription` → первое предложение `description` | кратко |
| `poster_url` | Text / null | `poster.url` | для Quick Look |
| `source` | Text | вычисляется | `poiskkino` \| `ai` |
| `origin` | Text | вычисляется | `manual` \| `recommendation` |
| `added_date` | Text | Current Date | ISO 8601 |
| `watched_date` | Text / null | Current Date при оценке или статусе «Смотрел» | ISO 8601 |
| `updated_date` | Text | Current Date | ISO 8601, при любом изменении |
| `seasons_checked` | Text / null | только удалённый Movie Seasons | устарело: больше никто не пишет и не читает; в старых записях может остаться |
| `notify` | Boolean / null | ТВ (сериал: включается при первом просмотре) / Movie Card «Уведомления» | только сериалы; `true` — Cron облака шлёт в Telegram новые серии и сезоны. Нет поля = выключено |
| `season_ratings` | Dictionary / null | ТВ | `{"<сезон>": 1..10}`; `my_rating` сериала на ТВ — округлённое среднее. Шорткаты не меняют, но сохраняют (запись уходит целиком) |
| `catalog_source`, `catalog_id` | Text, Number / null | ТВ | каталог ТВ (`Tmdb` / `PoiskKino` / `Kinopoisk`) и id в нём; шорткаты не трогают |
| `updated_ms` | Number / null | Movie Sync | время последней правки в мс от 1970-01-01 UTC (часы того, кто правил). Сравнивается с `updated_at` облака (LWW). Ставит Movie Sync при отправке и при приёме записи; нет — запись проигрывает любой версии из облака |

**null** в таблице = ключа в словаре нет (Dictionary в Shortcuts не хранит null). Проверка — `If … has any value`.

Пользовательские поля (не перезаписываются при «Обновить данные»; все остальные — перезаписываются): `my_rating`, `status`, `tags`, `note`, `origin`, `added_date`, `watched_date`, `key`, `notify`, `season_ratings`, `updated_ms`.

Запись из облака, созданная на ТВ, часто неполная: есть `key`, `title_ru`, `type` (`movie` / `tv_show`), `status`, `notify`, иногда `year`, `poster_url`, `imdb_id`, `premiere_date`, `my_rating`, `season_ratings`, `catalog_*`; нет `title_en`, `genres`, `ratings`, `added_date` и пр. Шорткаты читают поля через `has any value`; недостающее добавит «Обновить данные». Исключение — `release_status` = `cancelled` (поставлен вручную): автообновление его не трогает.

Movie Lookup возвращает запись без `status`, `origin`, `added_date`, `watched_date`, `my_rating`, `tags`, `note` — их ставит вызывающий.

Даты хранятся текстом: Format Date → ISO 8601 (без времени для `premiere_date`). Перед сравнением дат или Format Date из сохранённого текста — **Get Dates from Input**.

**Пустое в Set Dictionary Value не передать** (пустой List, Text `""`, пустой Dictionary → ошибка «No value was provided»). Нет значения → поле не ставить: нет `tags` = нет тегов; нет `genres` / `ratings` / `note` = пусто. Читать через `has any value`.

**Список из одного элемента ложится текстом** (проверено на iPhone 2026-10-25): Set Dictionary Value / Data Jar при одном значении пишут `"тег"`, а не `["тег"]`; обёртка в **List** не помогает. Касается `tags`, `genres`. Это норма: читать такие поля как список (Combine Text, `contains`, Repeat with Each, Count работают и с текстом — Count даст 1).

**Data Jar Get Value по пути к отсутствующему полю падает** («No value found at …», проверено на iPhone 2026-10-25), в отличие от Get Dictionary Value, которое отдаёт пусто. Необязательные поля (`note`, `my_rating`, `tags`…) читать так: Data Jar Get Value `movies.items.<key>` (запись целиком) → Get Dictionary Value поля.

**Путь через отсутствующий словарь падает** («No value was found for dictionary key 'ratings'», проверено 2026-10-26): `Get Value for "ratings.imdb"` падает, если в записи нет `ratings`. Читать в два шага: Get Value `ratings` → Set variable `rts` → If `rts` нет значения → пустой **Dictionary** → `rts`; потом Get Value `imdb` / `kp` из `rts`. Отсутствующий ключ первого уровня (`note`, `my_rating`) отдаёт пусто, не падает.

**Get Dictionary Value с пустым ключом падает** («No key was provided»). Ключ из переменной (`Get Value for [status] in [statusLabels]`) — сначала `If [переменная] has any value`.

Вход суб-шортката бывает Text или Dictionary (Lookup, Recommend): **Get Dictionary Value** из Text падает («couldn't convert from Text to Dictionary»). Сначала **Get Type** of Shortcut Input → If Type is `Text` / `Dictionary`.

Числа из словаря (Data Jar, ответ API, поле `Repeat Item`) приходят без типа — If не предлагает «больше/меньше». Перед сравнением — действие **Number** с этой переменной, сравнивать его результат.

### Пример

```json
{
  "key": "tt10466872",
  "title_en": "Severance",
  "title_ru": "Разделение",
  "year": 2022,
  "type": "tv_show",
  "genres": ["Drama", "Mystery", "Science Fiction", "Thriller"],
  "imdb_id": "tt10466872",
  "kinopoisk_id": 1318972,
  "ratings": {"imdb": 8.7, "kp": 8.1},
  "my_rating": 9,
  "status": "watching",
  "tags": ["пересмотреть"],
  "note": "",
  "length": 50,
  "age_rating": 18,
  "premiere_date": "2022-02-17",
  "seasons_count": 2,
  "release_status": "ongoing",
  "next_episode": "S3E5 — 2026-11-12",
  "actors": ["Адам Скотт", "Бритт Лоуэр", "Патрисия Аркетт"],
  "description": "Сотрудникам корпорации разделили память на рабочую и личную.",
  "poster_url": "https://…",
  "source": "poiskkino",
  "origin": "manual",
  "added_date": "2026-10-20T21:14:00+03:00",
  "watched_date": null,
  "updated_date": "2026-10-20T21:14:00+03:00"
}
```

## Синхронизация с облаком

Movie Sync ([sync/README.md](sync/README.md)) обменивается с воркером списка `cloud/list-worker` — тем же, что у приложения на ТВ ([контракт API](../../cloud/list-worker/README.md#контракт)). Заголовок `X-VG-List-Key: <sync_key>`. Сверено с кодом воркера (`src/worker.js`) и ТВ (`watchlist/ListApi.kt`, `LocalListStore.kt`, `ListActions.kt`) 2026-11-11.

### Сопоставление полей

Запись Data Jar и `data` строки облака — **одни и те же имена полей** этого файла: ТВ пишет `key`, `title_ru`, `type`, `status`, `my_rating`, `season_ratings`, `notify`, `year`, `poster_url`, `imdb_id`, `premiere_date` и свои `catalog_source` / `catalog_id`. Переименований нет.

| Data Jar | Строка `titles` облака | Примечание |
|---|---|---|
| ключ `movies.items.<key>` | `key` | шаблон — [Ключ записи](#ключ-записи) |
| запись целиком | `data` (объект, ≤ 16 КиБ JSON) | уходит и приходит целиком: LWW по всей записи |
| `updated_ms` | `updated_at` (целое, мс, время клиента) | сервер урезает будущее до now + 5 мин |
| нет записи (удалена) | `deleted: true` + последний снимок в `data` | физического удаления нет |
| `movies.episodes.<key>.c<code>` | строка `episodes` `{title_key, season, episode, watched, updated_at, deleted}` | только чтение; iPhone серии не пишет |
| — | `positions` | места остановки плеера ТВ; iPhone пропускает |

Значения: `status` — [Status](#status); `my_rating` — Number 1–10; `notify` — Boolean (Cron принимает и строку `"true"`); одиночные `tags` / `genres` могут лечь текстом — ТВ и Cron их не читают.

### Правила

- **Отправка**: правка → Movie Sync `{key}` ставит `updated_ms` = сейчас (мс), кладёт строку в `movies.sync.queue.<key>` и шлёт всю очередь одним `POST /v1/batch` (`{"titles":[…]}`, ≤ 500 строк). Ответ с `seq` → очередь удалена. Без сети или при выключенной синхронизации очередь ждёт; повторная правка того же ключа заменяет строку.
- **Удаление**: Movie Card стирает `movies.items.<key>` и шлёт `{key, deleted: true, record}` — строку с `deleted: true` и снимком записи.
- **Забор**: не чаще раза в день (`movies.sync.last_date`) и только при запуске экранных шорткатов; вручную — Rate → «Синхронизировать». `GET /v1/changes?since=<cursor>&limit=500`, страницы до `more = false` (не больше 20 за запуск), `cursor` = `next`. Перед забором — отправка очереди.
- **LWW**: строка облака применяется, если её `updated_at` > `updated_ms` локальной записи (нет записи или поля — применяется всегда). Новее — запись заменяется целиком (`data` + `key` + `updated_ms` = `updated_at`); метка удаления — запись стирается. Иначе — локальная остаётся (она уже ушла или уйдёт из очереди).
- **Серии**: просмотренная (`watched: true`, не `deleted`) → `movies.episodes.<title_key>.c<season·10000+episode>` = `S<season>E<episode>`; иначе путь стирается. Movie Card показывает максимальную — «Остановился: S2E5».
- Выключено (`sync_enabled` ≠ `true`) → ни одного запроса; ключ `sync_key` — только в `movies.settings`.

## Перечисления

### Status

| Значение | Метка в меню | Смысл |
|---|---|---|
| `unwatched` | Не смотрел | в списке «посмотреть» |
| `watching` | Смотрю | в процессе |
| `watched` | Смотрел | посмотрено |
| `dropped` | Бросил | бросил |
| `dismissed` | Не предлагать | исключено из рекомендаций |

**Будущая премьера** (ещё не вышел): `premiere_date` есть и > Current Date (через Get Dates from Input); нет `premiere_date` → `year` > текущего года. Такой записи доступны только `unwatched` и `dismissed`: в меню статусов нет «Смотрю», «Смотрел», «Бросил», нет «Оценить» / «Смотрел + оценка». В рекомендации (Recommend) не попадает.

**Новый сезон у «Смотрел»**: раньше переводил Movie Seasons (удалён). Теперь о новых сериях сообщает Cron облака в Telegram (сериалы с `notify`), а `watching` ставит ТВ при начале просмотра — приходит синхронизацией.

### Release status

Только сериалы ([Type](#type): `tv_show`, `animated_series`, `anime`). poiskkino `status` почти всегда null (проверено 2026-10-27) — не используется.

| Значение | Метка (карточка) | Откуда |
|---|---|---|
| `ongoing` | Идёт | `releaseYears[0].end` = 0 |
| `completed` | Завершён | `releaseYears[0].end` > 0 |
| `cancelled` | Отменён | только вручную в Movie Card; автообновление не перезаписывает |

Нет `releaseYears` → поле не ставить.

### Type

| Значение | Метка (`type_labels`) | Голос (`type_labels_en`) | poiskkino `type` |
|---|---|---|---|
| `movie` | Фильм | movie | `movie` |
| `tv_show` | Сериал | series | `tv-series`, `tv-show` |
| `animation` | Мультфильм | animated film | `cartoon` |
| `animated_series` | Мультсериал | animated series | `animated-series` |
| `anime` | Аниме | anime | `anime` |
| `documentary` | Документальный | documentary | `movie` + жанр `Documentary` |

Маппинг poiskkino `type` → наш хранится в `movies.settings.type_map` (Dictionary, 6 пар из колонки «poiskkino `type`»: `movie` → `movie`, `tv-series` → `tv_show`, `tv-show` → `tv_show`, `cartoon` → `animation`, `animated-series` → `animated_series`, `anime` → `anime`; `documentary` — по жанру, в карте его нет). Неизвестный тип (нет в карте) → `movie`.

Метки хранятся в `movies.settings.type_labels` / `type_labels_en` (Dictionary `type` → метка) — шорткаты берут их оттуда, текстом не вписывать.

**Метка по типу**: Get Dictionary Value `<type>` из `type_labels`.

**Выбор типа** (Choose from Menu не берёт пункты из переменных): Get Dictionary Value **All Values** из `type_labels` → **Choose from List** → **Repeat with Each** All Keys `type_labels`: **If** выбранное содержит Get Dictionary Value Repeat Item → это `type` (Add to Variable, если Select Multiple).

Правило: сначала маппинг по таблице; если результат `movie` и в `genres` (после маппинга) есть `Documentary` → `documentary`. Неизвестный `type` → `movie`.

Сериалы (длина серии, `notify`, «Остановился»): `tv_show`, `animated_series`, `anime`. ТВ пишет только `tv_show` (сериалом считает ещё `animated_series`).

## Жанры

Канонический список (`movies.settings.genres`, он же — в промптах ИИ):

```
Action, Adventure, Biography, Comedy, Concert, Crime, Documentary, Drama, Family, Fantasy, Film-Noir, Game Show, History, Horror, Kids, Music, Musical, Mystery, News, Reality, Romance, Science Fiction, Short, Sport, Talk Show, Thriller, War, Western
```

### Маппинг poiskkino → канон (`movies.settings.genre_map`)

`drop` — жанр не сохраняется (дублирует `type` или бесполезен).

| `genres[].name` | Канон |
|---|---|
| аниме | drop |
| биография | Biography |
| боевик | Action |
| вестерн | Western |
| военный | War |
| детектив | Mystery |
| детский | Kids |
| для взрослых | drop |
| документальный | Documentary |
| драма | Drama |
| игра | Game Show |
| история | History |
| комедия | Comedy |
| концерт | Concert |
| короткометражка | Short |
| криминал | Crime |
| мелодрама | Romance |
| музыка | Music |
| мультфильм | drop |
| мюзикл | Musical |
| новости | News |
| приключения | Adventure |
| реальное ТВ | Reality |
| семейный | Family |
| спорт | Sport |
| ток-шоу | Talk Show |
| триллер | Thriller |
| ужасы | Horror |
| фантастика | Science Fiction |
| фильм-нуар | Film-Noir |
| фэнтези | Fantasy |
| церемония | drop |

В `genre_map` значение `drop` хранится строкой `"drop"`.

Алгоритм для каждого `genres[]`:

1. `Get Dictionary Value` `name` из `genre_map`.
2. Есть и ≠ `drop` → добавить. Есть и = `drop` → пропустить.
3. Нет в карте → пропустить. (`genre_map` покрывает все 32 жанра справочника `/v1.5/dictionary/genres` — проверено 2026-10-23. Новый жанр появится — дописать в карту. `slug` английский, но не канонический: `sci-fi`, `reality-tv`, `children` — как значение не годится.)
4. Убрать дубли.

ИИ-жанры — только из канонического списка (список вставлен в промпт).

## `movies.settings`

```json
{
  "poiskkino_api_key": "<ключ>",
  "weights": {"smoothing_k": 3, "taste": 0.6, "external": 0.4, "min_ratings": 3},
  "top_n": 3,
  "ai_enabled": true,
  "tags": ["с семьёй", "на выходные", "пересмотреть"],
  "genres": ["Action", "Adventure", "…"],
  "genre_map": {"аниме": "drop", "биография": "Biography", "…": "…"},
  "type_map": {"movie": "movie", "tv-series": "tv_show", "tv-show": "tv_show", "cartoon": "animation", "animated-series": "animated_series", "anime": "anime"},
  "type_labels": {"movie": "Фильм", "tv_show": "Сериал", "animation": "Мультфильм", "animated_series": "Мультсериал", "anime": "Аниме", "documentary": "Документальный"},
  "type_labels_en": {"movie": "movie", "tv_show": "series", "animation": "animated film", "animated_series": "animated series", "anime": "anime", "documentary": "documentary"},
  "sync_enabled": true,
  "sync_url": "https://<воркер списка>",
  "sync_key": "<ключ>"
}
```

| Поле | Описание |
|---|---|
| `poiskkino_api_key` | ключ API, только здесь; в документацию не вписывать |
| `weights.smoothing_k` | сглаживание жанрового вкуса (см. Movie Recommend) |
| `weights.taste` / `weights.external` | веса вкуса и внешних рейтингов |
| `weights.min_ratings` | меньше оценок — только внешний рейтинг |
| `top_n` | сколько показывать в «Мой список» |
| `ai_enabled` | `false` — ИИ выключен (Apple Intelligence недоступна): Lookup ищет только по вводу, ИИ-пункты показывают «ИИ выключен» |
| `tags` | список тегов, редактируется руками в Data Jar |
| `genres` | канонические жанры |
| `genre_map` | ru → канон |
| `type_map` | poiskkino `type` → наш `type`, [Type](#type) |
| `type_labels` | `type` → рус. метка (меню, карточка, статистика), [Type](#type) |
| `type_labels_en` | `type` → англ. метка (голос, Siri) |
| `sync_enabled` | Boolean; `true` — синхронизация с облаком. Нет или `false` → ни одного запроса к воркеру, правки копятся в `movies.sync.queue` |
| `sync_url` | адрес воркера списка (`cloud/list-worker`), без `/` на конце; тот же, что `LIST_URL` у ТВ |
| `sync_key` | ключ доступа `X-VG-List-Key` (секрет воркера `LIST_KEY`); вписывается руками, в документацию и репозиторий не попадает |

## Ссылки карточки

| Что | Формат | Условие |
|---|---|---|
| Кинопоиск | `https://www.kinopoisk.ru/film/<kinopoisk_id>/` | есть `kinopoisk_id` |
| IMDb | `https://www.imdb.com/title/<imdb_id>/` | есть `imdb_id` |
| Трейлер | `https://www.youtube.com/results?search_query=<URL Encode("title_en year trailer")>` | всегда |

Открывать через **Show Web Page** — встроенный Safari поверх «Команд»: Done → шорткат продолжается, карточка снова на экране. **Open URLs** не использовать — он уводит в другое приложение.

## Lookup-данные poiskkino → запись

| Поле записи | Выражение |
|---|---|
| `title_en` | `enName` (обычно пуст или null — проверено), иначе `alternativeName` если только латиница (Match Text `^[A-Za-z0-9 .,:;'!?&()\-]+$`), иначе ИИ `title_en` |
| `title_ru` | `name`, иначе ИИ `title_ru` |
| `ratings.imdb` / `.kp` | `rating.imdb` / `rating.kp` → Round Number 0.1 (`kp` приходит как `7.168`); `0` или нет → null |
| `length` | сериал: `seriesLength`, иначе `movieLength` |
| `premiere_date` | `premiere.world`, иначе `premiere.russia`; Get Dates from Input → Format Date `yyyy-MM-dd` |
| `seasons_count` | Count `seasonsInfo`, если не null (обычно null — тогда пусто, заполнит «Обновить данные») |
| `release_status` | сериал: Get Value `releaseYears` → If has any value → Get Item at Index 1 → Get Value `end` → Number: 0 → `ongoing`, > 0 → `completed`. Старое значение `cancelled` — не трогать |
| `actors` | Repeat with Each `persons`: If `enProfession` (Type → Text) is `actor` и Count `actors` < 5 → Add to Variable `name` (нет → `enName`). Пусто → поле не ставить |
| `description` | `shortDescription`, иначе первое предложение `description` (Split Text по `. ` → первый элемент + `.`) |

## Сезоны poiskkino → `next_episode`, `seasons_count`

`GET /v1.5/season?movieId=<kinopoisk_id>&limit=50` (без `selectFields` — нужны `episodes`) → `docs[{number, airDate, episodesCount, episodes[{number, airDate}]}]`; `airDate` — `2026-08-02T00:00:00.000Z` (проверено 2026-10-27), бывает пусто.

- `seasons_count` — максимум `number > 0` среди сезонов, у которых `airDate` есть и ≤ Current Date (вышедшие). Анонсированный без даты или с будущей датой не считается.
- `next_episode` — минимум `airDate > Current Date` среди `episodes[]` всех сезонов → `S<season>E<episode> — <yyyy-MM-dd>`. Нет будущих серий, но есть сезон с `airDate > Current Date` → `Сезон <number> — <yyyy-MM-dd>`. Ничего → убрать поле.
- Минимум — перебором (как [топ](#топ-без-сортировки), N=1, сравнение «меньше»).

## Топ без сортировки

В Shortcuts нет сортировки списков, сторонние приложения не используем. Топ-N ищется перебором максимума:

1. **Dictionary** `scores`: id → число (балл, оценка).
2. **Repeat** N раз:
   1. `best` = -1, `bestId` = пусто.
   2. **Repeat with Each** ключ `scores`: **If** `picked` не содержит ключ **и** значение > `best` → `best` = значение, `bestId` = ключ.
   3. `bestId` есть → **Add to Variable** `picked`. Нет → конец (элементов меньше N).
3. `picked` — id по убыванию.

N × размер базы сравнений: 10 × 300 — доли секунды.

Списки без балла (Rate) — в порядке, который отдаёт Data Jar (обычно порядок добавления).

## Ответ ИИ (Use Model)

Везде одинаково:

1. **Use Model** (Private Cloud Compute, Output: **Dictionary**) → `ai`. Ожидаемый ключ (`candidates` / `similar` / `picks` / `items` / `questions`) есть и не пуст → готово (для `questions` пустой список — нормальный ответ).
2. Иначе — запасной путь: **Use Model** с тем же промптом + строка `Return ONLY valid JSON, no markdown, no explanations.`, Output: **Text** → **Replace Text** убрать ```` ```json ```` и ```` ``` ```` → **Get Dictionary from Input** → `ai`.
3. Ключа всё равно нет → **Show Notification** «ИИ не ответил» → действие по README шортката (Lookup — поиск по сырому вводу; Card — «Похожих нет»; Recommend — в меню).

Чат (Output: Text, **Follow Up** on — «Спросить ИИ» в карточке, «Разбор вкуса» в Stats) запасной путь не нужен: ответ — просто текст.

## Ошибки

Общие правила для всех шорткатов.

**Try/catch в Shortcuts нет.** Нет сети, упал Use Model или Data Jar → действие падает с системной ошибкой, шорткат останавливается. Поймать нельзя; данные при этом не портятся (запись идёт только после проверок). Если Apple Intelligence недоступна долго — `movies.settings.ai_enabled` = `false`.

**Проверка настроек** — первым шагом в каждом шорткате, который читает `movies.settings`:

1. **Get Value** `movies.settings` → `settings`. Пусто → **Show Notification** «Нет movies.settings — см. README, Установка» → **Stop This Shortcut**.
2. Шорткат ходит в poiskkino (Lookup, Card) и `poiskkino_api_key` пуст → «Нет ключа poiskkino в movies.settings» → **Stop This Shortcut**.
3. `ai_enabled` не задан → считать `true`.

**Ошибка API.** Ответ poiskkino с ошибкой — словарь `{message, statusCode, error}` (проверено: неверный ключ → 401 «Переданный токен некорректен!»). Признак ошибки — нет ожидаемого поля (`docs` / `id`). Тогда уведомление с причиной:

**Show Notification** `poiskkino: <statusCode> <message>`. 401 — проверь ключ; лимит 200/сутки исчерпан — подождать до завтра.

**Ошибка облака** (Movie Sync): ответ воркера не JSON или без `seq` / `next` → «Movie Sync: отправка не удалась — <ответ>» / «Movie Sync: изменения не получены — <ответ>»; в ответе — `{"error":"код"}` ([контракт](../../cloud/list-worker/README.md#контракт)): `bad_key` — неверный `sync_key`, `bad_key_format` — ключ записи вне шаблона, `too_many` — очередь больше 500. Очередь при ошибке не теряется. Без сети Movie Sync молчит (кроме ручного «Синхронизировать»).

**ИИ выключен** (`ai_enabled` = `false`): Lookup — сразу поиск по вводу (как «Нет в списке»); «Похожие» — только similarMovies; «По настроению», «Новое», «Спросить ИИ», «Разбор вкуса» → **Show Notification** «ИИ выключен» и назад.
