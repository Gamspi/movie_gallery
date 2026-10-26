# movies — контекст категории

Система шорткатов для учёта фильмов/сериалов: поиск, добавление, оценка, рекомендации, статистика, новые сезоны. Хранилище — Data Jar. Метаданные — API poiskkino. ИИ — действие Use Model (Private Cloud Compute).

## Карта файлов

| Путь | Что |
|---|---|
| `README.md` | обзор для пользователя, граф вызовов, установка |
| `data.md` | схема записи, настройки, enum, маппинги, ключи, ссылки — **единственный источник правды по полям** |
| `lookup/` | Movie Lookup — суб-шорткат поиска, ничего не сохраняет |
| `add/` | Movie Add — добавление |
| `card/` | Movie Card — суб-шорткат карточки, хаб действий |
| `rate/` | Movie Rate — список и оценка |
| `recommend/` | Movie Recommend + обёртка Movie Tonight |
| `stats/` | Movie Stats — статистика |
| `seasons/` | Movie Seasons — автоматизация новых сезонов |
| `voice/` | Movies — голосовой вход для Siri (add / recommend, ответ текстом) |

## Конвенции

- **Приоритет — экран iPhone.** Основные сценарии проектировать под экран (меню, списки, карточка). Голос (Movies, `voice: true`) — дополнительный упрощённый путь; не усложнять и не урезать экранные сценарии ради голоса.

- Поля записи — только в `data.md`. В README шорткатов ссылаться, не дублировать схему.
- Промпты ИИ — дословно (англ.) в README своего шортката.
- Контракты суб-шорткатов (вход/выход `Run Shortcut`) — в разделе «Вход / Выход» README суб-шортката:
  - Movie Lookup: вход Text | `{query, voice:true}` | `{kinopoisk_id}` | `{title_en,title_ru,year,type}` → запись без пользовательских полей или ничего.
  - Movie Card: вход `{key[, predicted]}` | `{key, auto:"similar"}` | `{record, saved:false[, predicted]}` → `{action:"back"}` | `{action:"similar", items:[…]}`.
  - Movie Recommend: вход пусто | Text `today` | `{mode:"voice", mood, types}` → Text.
- Названия действий — по-английски, как в приложении (iOS 27, англ. UI). Метки меню для пользователя — по-русски; **в голосовом пути (Movies, `voice: true`) — по-английски** (Siri на английском) и без экранных действий (Show Web Page, Quick Look, Follow Up, Add New Event).
- Data Jar: всё — в **одном** корне `movies` (`movies.items`, `movies.settings`), других ключей верхнего уровня не создавать — хранилище общее с другими кейсами. Пути: `movies.items.<key>.<field>`. В ключах нет точек.
- Проверено на iPhone 2026-10-24 ([мини-тесты](README.md#1-мини-тесты-ios-до-сборки)): Use Model Output Dictionary отдаёт словарь; Follow Up открывает чат; Show Web Page — после закрытия шорткат продолжается; **Stop This Shortcut в суб-шорткате останавливает только его** — вызывающий обязан обрабатывать пустой результат Run Shortcut; Data Jar — вложенные пути и ключи с `-` работают; **Siri с заблокированного iPhone не запускает шорткат**.
- **If** со строкой по плашке Any (`[Repeat Item: status]`, Selected Item из Choose from List, переменная из Get Dictionary Value) — сравнить со строкой нельзя: в плашке Type → **Text** (в XML после `WFDictionaryValueVariableAggrandizement` — `WFCoercionVariableAggrandizement` `WFStringContentItem`).
- Путь через точку (`rating.kp`) в Get Dictionary Value падает «no value was found for dictionary key rating», если родителя нет. Только для ответов poiskkino: `Get Value "rating"` → `If has any value` → `Get Value "kp" in [Dictionary Value]` → всё, что читает результат, — **внутри этого If** (снаружи значение пустое). Сравнение с числом — плашка Type → Number (`WFNumberContentItem`). Пути Data Jar (`movies.…`) и `settings` — можно.
- Run Shortcut в XML — только по имени (`workflowName`), без `workflowIdentifier`: при импорте ID у суб-шортката новый, старый ломает ссылку.
- Схемы — Mermaid. Кода (Python и пр.) нет.
- Сторонние приложения — только Data Jar. Сортировки нет: топ — перебором максимума ([data.md](data.md#топ-без-сортировки)).
- Ссылки — только **Show Web Page** (не Open URLs).
- Use Model — всегда с запасным путём ([data.md](data.md#ответ-ии-use-model)); чаты (Follow Up) — Output Text, без него.
- Use Model: вход/выход по-английски; русский — через Translate Text. Применено в режиме «По настроению» Movie Recommend (русский промпт/ответ там давал «The model cannot provide a response…»; ошибку Shortcuts не ловит).
- Отзыв (`note`) — сигнал для ИИ: промпты рекомендаций и чатов передают `my_rating — note`.
- Любой ответ poiskkino проверять (`m.id` / `docs` есть) до записи в Data Jar: пустой ответ не должен затирать данные. Ошибки — по правилам [data.md](data.md#ошибки): проверка настроек первым шагом, уведомление `poiskkino: <statusCode> <message>`, учёт `ai_enabled`.

## API poiskkino (OpenAPI v1.4.1)

- Base `https://api.poiskkino.dev`, заголовок `X-API-KEY`. Демо-ключ: 200 запросов/сутки (бот @poiskkinodev_bot).
- `GET /v1.5/movie/search?query=<q>&limit=5` → `{docs,total,page,pages}`; doc: `id, name, enName, alternativeName, type, year, description, shortDescription, movieLength, externalId{imdb,tmdb,kpHD}, poster{url,previewUrl}, rating{kp,imdb,…}, genres[{name,slug,id}], releaseYears, isSeries, seriesLength, ageRating, status`.
- `GET /v1.5/movie/{id}` → то же + `similarMovies[]`, `seasonsInfo[{number,episodesCount}]`, `premiere{world,russia,digital,cinema}`, `watchability`, `sequelsAndPrequels`.
- `GET /v1.5/movie?externalId.imdb=<tt>&limit=1` → `{docs:[полная запись]}`.
- `GET /v1.5/season?movieId=<id>&limit=50&selectFields=number&selectFields=episodesCount` → `{docs:[{number, episodesCount, airDate?}]}` — сезоны (в `movie/{id}` `seasonsInfo` обычно null).
- Проверено живым запросом 2026-10-23: `enName` обычно пуст/null (англ. название — в `alternativeName`); `status` обычно null; `releaseYears[].end` = 0 у идущего сериала; `similarMovies[]` без `enName`, `year` бывает пропущен, `rating` только `kp`; ошибка ключа — 401 `{message, statusCode}`; жанры — 32 шт. в `/v1.5/dictionary/genres`, все есть в `genre_map`.
- Рейтинг `0` → null.

## Правила

- Никогда не вписывать API-ключи в документацию. Ключ — только в `movies.settings.poiskkino_api_key`.
- `data.md` — единственный источник правды по полям. Меняешь поле → обнови `data.md` и все шорткаты, которые его читают/пишут (поиск по имени поля по всей `movies/`).
- Экономить запросы poiskkino: не дёргать API до открытия карточки; лимит указывать в разделе «Расход запросов».
