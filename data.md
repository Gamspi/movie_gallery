# Данные (Data Jar)

Единственный источник правды по полям записи. Меняешь поле — обнови этот файл и все шорткаты, которые его используют.

## Корень `movies`

Всё хранится в **одном** ключе верхнего уровня Data Jar — `movies` (Dictionary), чтобы не мешать другим кейсам в том же Data Jar. Других ключей верхнего уровня система не создаёт и не читает.

```
movies
├─ items      — ключ записи → запись
└─ settings   — настройки, справочники, API-ключ
```

| Путь | Тип | Что |
|---|---|---|
| `movies.items` | Dictionary | ключ записи → запись |
| `movies.settings` | Dictionary | настройки, справочники, API-ключ |

Путь к полю: `movies.items.<key>.<field>`, напр. `movies.items.tt10466872.my_rating`. Точка — разделитель пути в Data Jar, поэтому в ключах записей точек нет.

## Ключ записи

По приоритету:

1. `imdb_id` — `tt10466872`.
2. `kp-<kinopoisk_id>` — `kp-1318972`.
3. `ai-<slug>-<year>` — `ai-the-brutalist-2024`.

Slug для `ai-`:

1. **Match Text** `[a-zA-Z]` в названии. Нет ни одной латинской буквы (кириллица, «Сохранить как есть» с `Брат 2`) → slug не делать, ключ сразу `ai-<Current Date yyyyMMddHHmm>`. Иначе из `Брат 2` вышел бы кривой `ai--2-…`.
2. Есть латиница → lowercase → пробелы в `-` → удалить всё, кроме `a-z`, `0-9`, `-` (Replace Text, regex `[^a-z0-9-]` → пусто) → схлопнуть повторные `-` (regex `-+` → `-`) → срезать `-` по краям (regex `^-+|-+$` → пусто).

Год есть → `-<year>` в конце, в обоих случаях. Нет года → без него.

Ключ не меняется после сохранения. Если «Обновить данные» нашло `imdb_id` у записи с ключом `kp-`/`ai-` — запись остаётся под старым ключом, `imdb_id` пишется в поле.

## Запись `movies.items.<key>`

| Поле | Тип | Источник | Описание |
|---|---|---|---|
| `key` | Text | вычисляется | дубль ключа записи |
| `title_en` | Text | poiskkino `enName` → `alternativeName` (если латиница) → ИИ | английское название |
| `title_ru` | Text | poiskkino `name` → ИИ | русское название |
| `year` | Number | poiskkino `year` / ИИ | год выхода (для сериала — первый год) |
| `type` | Text | маппинг `type` / ИИ | см. [Type](#type) |
| `genres` | Array of Text | маппинг `genres[].name` / ИИ | канонические англ. жанры |
| `imdb_id` | Text / null | `externalId.imdb` | `tt…` |
| `kinopoisk_id` | Number / null | `id` | id Кинопоиска |
| `ratings` | Dictionary | `rating.imdb`, `rating.kp` | `{imdb, kp}`, Number или null; `0` → null |
| `my_rating` | Number / null | пользователь | 1–10 |
| `status` | Text | пользователь | см. [Status](#status) |
| `tags` | Array of Text | пользователь | из `movies.settings.tags` |
| `note` | Text | пользователь | заметка |
| `length` | Number / null | `movieLength`; сериал — `seriesLength` | минуты; для сериала — средняя серия |
| `age_rating` | Number / null | `ageRating` | 0, 6, 12, 16, 18 |
| `premiere_date` | Text / null | `premiere.world` → `premiere.russia` | ISO-дата `YYYY-MM-DD` |
| `seasons_count` | Number / null | `seasonsInfo` (обычно null) → заполняет Movie Seasons через `/v1.5/season` | только сериалы; номер последнего сезона |
| `release_status` | Text / null | `status` (обычно null) | `filming` \| `pre-production` \| `completed` \| `announced` \| `post-production` |
| `description` | Text | `shortDescription` → первое предложение `description` | кратко |
| `poster_url` | Text / null | `poster.url` | для Quick Look |
| `source` | Text | вычисляется | `poiskkino` \| `ai` |
| `origin` | Text | вычисляется | `manual` \| `recommendation` |
| `added_date` | Text | Current Date | ISO 8601 |
| `watched_date` | Text / null | Current Date при оценке или статусе «Смотрел» | ISO 8601 |
| `updated_date` | Text | Current Date | ISO 8601, при любом изменении |

**null** в таблице = ключа в словаре нет (Dictionary в Shortcuts не хранит null). Проверка — `If … has any value`.

Пользовательские поля (не перезаписываются при «Обновить данные»; все остальные — перезаписываются): `my_rating`, `status`, `tags`, `note`, `origin`, `added_date`, `watched_date`, `key`.

Movie Lookup возвращает запись без `status`, `origin`, `added_date`, `watched_date`, `my_rating`, `tags`, `note` — их ставит вызывающий.

Даты хранятся текстом: Format Date → ISO 8601 (без времени для `premiere_date`). Перед сравнением дат или Format Date из сохранённого текста — **Get Dates from Input**.

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
  "release_status": "filming",
  "description": "Сотрудникам корпорации разделили память на рабочую и личную.",
  "poster_url": "https://…",
  "source": "poiskkino",
  "origin": "manual",
  "added_date": "2026-10-20T21:14:00+03:00",
  "watched_date": null,
  "updated_date": "2026-10-20T21:14:00+03:00"
}
```

## Перечисления

### Status

| Значение | Метка в меню | Смысл |
|---|---|---|
| `unwatched` | Не смотрел | в списке «посмотреть» |
| `watching` | Смотрю | в процессе |
| `watched` | Смотрел | посмотрено |
| `dropped` | Бросил | бросил |
| `dismissed` | Не предлагать | исключено из рекомендаций |

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

Сериалы (для Seasons и длины серии): `tv_show`, `animated_series`, `anime`.

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
  "type_labels_en": {"movie": "movie", "tv_show": "series", "animation": "animated film", "animated_series": "animated series", "anime": "anime", "documentary": "documentary"}
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
| `seasons_count` | Count `seasonsInfo`, если не null (обычно null — тогда пусто, заполнит Movie Seasons) |
| `description` | `shortDescription`, иначе первое предложение `description` (Split Text по `. ` → первый элемент + `.`) |

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
2. Шорткат ходит в poiskkino (Lookup, Card, Seasons) и `poiskkino_api_key` пуст → «Нет ключа poiskkino в movies.settings» → **Stop This Shortcut**.
3. `ai_enabled` не задан → считать `true`.

**Ошибка API.** Ответ poiskkino с ошибкой — словарь `{message, statusCode, error}` (проверено: неверный ключ → 401 «Переданный токен некорректен!»). Признак ошибки — нет ожидаемого поля (`docs` / `id`). Тогда уведомление с причиной:

**Show Notification** `poiskkino: <statusCode> <message>` (пусто → «нет ответа»). 401 — проверь ключ; лимит 200/сутки исчерпан — подождать до завтра.

**ИИ выключен** (`ai_enabled` = `false`): Lookup — сразу поиск по вводу (как «Нет в списке»); «Похожие» — только similarMovies; «По настроению», «Новое», «Спросить ИИ», «Разбор вкуса» → **Show Notification** «ИИ выключен» и назад.
