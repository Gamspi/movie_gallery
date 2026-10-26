# Movie Rate

## Назначение

Просмотр списка по статусу и вход в карточку для оценки/правки. Вся работа с тайтлом — в Movie Card.

## Вход / Выход

Вход: нет. Выход: нет. Выход из шортката — Cancel в меню «Показать» или в списке тайтлов (пункта «Выход» нет).

## Схема

```mermaid
flowchart TD
    R[Repeat] --> Q{menuDone = 1?}
    Q -->|нет| F[Choose from Menu «Показать»: фильтр]
    F -->|Cancel| End[конец]
    F --> D[чтение базы по фильтру]
    Q -->|да| D
    D -->|пусто: «Пусто», menuDone пуст| R
    D --> L[Choose from List тайтлов]
    L -->|Cancel| End
    L -->|«← К фильтрам»: menuDone пуст| R
    L --> C[Run Movie Card key]
    C -->|back| R
    C -->|similar| S[Choose from List похожих]
    S -->|выбор| CU[Run Movie Card record, saved false] --> R
    S -->|Назад| R
```

## Шаги

Плоский список действий для сборки в редакторе — [actions.md](actions.md).

1. **Get Value** `movies.settings` → `settings`. Пусто → **Show Notification** «Нет movies.settings — см. README, Установка» → **Stop This Shortcut**.
2. `menuDone` = пусто (Nothing → Set Variable) — флаг «фильтр выбран». Пустой → в начале оборота показать меню.
3. **Repeat** 100 раз (один цикл на меню, список и карточку — goto в Shortcuts нет):
   0. **If** `menuDone` ≠ 1 → **Choose from Menu** «Показать»: Несмотренные / Смотрю / Посмотренные / Бросил / Скрытые / Все → Set Variable `filter` = `unwatched` / `watching` / `watched` / `dropped` / `dismissed` / `all`; `filterT` = метка пункта; `menuDone` = 1. Cancel → шорткат завершается. «Скрытые» — чтобы вернуть тайтл в рекомендации (Статус в карточке).
   1. **Get Value** `movies.items` → **Get Dictionary Value** All Values → `all`.
   2. **Repeat with Each** `all`: **If** `filter` = `all` → берём всё, кроме `status` = `dismissed`; иначе — только `status` = `filter` → **Add to Variable** `items`.
   3. `items` пуст → **Show Notification** «Пусто» → `menuDone` = пусто → следующий оборот снова показывает меню. Шаги 4–9 — в Otherwise.
   4. Первый пункт `labels` — **Text** «← К фильтрам». Затем **Repeat with Each** `items`:
      - **Text** метка: `title_ru / title_en (year)`; если `my_rating` → дописать ` ★ my_rating`; если `status` = `watching` и `filter` = `all` → ` ▶`.
      - Если метка уже есть в `labelMap` (ремейк того же года и т. п.) → дописать ` · key`.
      - **Add to Variable** `labels`; **Set Dictionary Value** метка → `key` в `labelMap`.
   5. Без сортировки — порядок, как отдаёт Data Jar (обычно порядок добавления).
   6. **Choose from List** `labels` (prompt «filterT: N», N = Count `items`, напр. «Смотрю: 3»). Cancel → шорткат завершается.
   7. **If** Chosen Item = «← К фильтрам» → `menuDone` = пусто (следующий оборот — меню). Проверка — до поиска в `labelMap`; метка тайтла всегда содержит « / », так что с «← К фильтрам» не совпадёт.
      Otherwise → **Get Dictionary Value** Chosen Item из `labelMap` → `key`; шаги 8–9 — тоже в Otherwise.
   8. **Run Shortcut** Movie Card, Input **Dictionary** `{key}` → `res`.
   9. **If** `res.action` = `similar` → показать похожие:
      - **Repeat with Each** `res.items` → метка `title_ru / title_en (year)` + ` — IMDb x` или ` — КП y` (у похожих из poiskkino есть только КП) → `simLabels`, `simMap`.
      - **Add to Variable** `simLabels` «← Назад».
      - **Choose from List** (prompt «Похожие») → «← Назад» → ничего. Иначе → **Run Shortcut** Movie Card `{record: item, saved: false}`.
   10. Очистить `items`, `labels`, `labelMap`, `simLabels`, `simMap` (Set Variable пустым) — следующий оборот перечитывает базу (статус мог измениться).
4. End Repeat.

## Промпты ИИ

Нет.

## Data Jar

Читает: `movies.items`. Пишет: только через Movie Card.

## Расход запросов poiskkino

0 сам по себе. Карточка — см. [card](../card/README.md#расход-запросов-poiskkino).

## Заметки / ограничения

- Сортировки по алфавиту нет (в Shortcuts её нет, сторонние приложения не используем). Choose from List умеет искать по тексту — для длинного списка этого хватает.
- Повторное нажатие на ту же запись после оценки — метка уже с ★, т. к. база перечитывается.
- Cancel в меню «Показать» или в Choose from List полностью останавливает шорткат — это и есть выход.
- Лимит — 100 оборотов на запуск (оборот = список или «Пусто»); хватает с запасом.
