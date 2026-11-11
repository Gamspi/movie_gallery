# iPhone: синхронизация шорткатов — проверка этапа 6

Дата: 2026-11-11. База: `2193f25`, изменения в рабочем дереве; коммит не создавался.
[План](../superpowers/plans/2026-11-11-iphone-sync.md).

## Что проверено

Mac и iPhone недоступны: шорткаты не собирались (`plutil`, `shortcuts sign`) и не запускались. Единственная возможная проверка — XML.

- Python `plistlib` (Windows, без сети) загружает все `ios/shortcuts/*/shortcut.xml` и `recommend/tonight.xml`: новый `sync` (268 действий) и изменённые `add` (411), `card` (1039), `rate` (202), `recommend` (1545), `stats` (392), `voice` (192); неизменённые `lookup`, `seed`, `tonight` — тоже. Файлы без BOM.
- Структурная проверка тем же скриптом во временной папке (в репозиторий не добавлялся) для всех файлов: каждый If / Repeat / Choose from Menu закрыт своим `GroupingIdentifier` в правильном порядке, Otherwise / пункты меню — у открытого блока; каждая ссылка `ActionOutput` указывает на более раннее действие; UUID действий уникальны; `Repeat Item N` не глубже текущей вложенности Repeat. До правок скрипт проходил на исходных файлах — так проверено, что он не отвергает рабочие шорткаты.
- Повторная загрузка/запись `plistlib` исходных файлов даёт байт-в-байт тот же XML (кроме CRLF рабочей копии и одного действия в `recommend`, записанного в исходнике в одну строку: оно переформатировано, содержимое то же), поэтому изменения ограничены вставленными действиями.
- Run Shortcut — только по имени (`workflowName`: Movie Sync, Movie Lookup, Movie Card, Movie Recommend), без `workflowIdentifier`. Имя «Movie Sync» совпадает с заголовком `sync/README.md`, из которого `build.sh` берёт имя файла; `build.sh` собирает `sync` первым.
- Идентификаторы действий взяты из существующих файлов репозитория, кроме трёх, которых там не было: `is.workflow.actions.getipaddress` (Get Current IP Address), параметры POST у `downloadurl` (`WFHTTPMethod`, `WFHTTPBodyType` = `File`, `WFRequestVariable`) и `WFMathOperation` `×`. Они не подтверждены импортом.
- Поля записи облака сверены с кодом: `cloud/list-worker/src/worker.js` (шаблон ключа, `updated_at`/`deleted`, формат `changes`), `src/cron.js` (`status`, `type`, `notify`, `imdb_id`, `catalog_*`, `premiere_date`, `year`, `poster_url`, `title_ru`), `app/.../watchlist/ListApi.kt`, `LocalListStore.kt`, `ListActions.kt`, `ListSyncService.kt` (`season_ratings`, `my_rating`, `notify`, ключи `tmdb-…`).
- Markdown: локальные ссылки изменённых документов указывают на существующие файлы; UTF-8 без BOM; `git diff --check` без замечаний.

## Не проверено

- Сборка на Mac и импорт в «Команды»; отображение вставленных действий в редакторе.
- Любой запуск на iPhone: все сценарии [TESTING.md](../../ios/shortcuts/TESTING.md#синхронизация-movie-sync) и регрессия разделов 1–4.
- Предположения: Dictionary → Text даёт JSON с `true`/`false` и числами; `1970-01-01T00:00:00Z` разбирается как UTC; Get Current IP Address (Local) пуст без сети; Data Jar создаёт промежуточные словари; текстовое тело File принимается воркером.
- Живой воркер и ТВ на том же облаке (деплой этапа 5 тоже не выполнен).
