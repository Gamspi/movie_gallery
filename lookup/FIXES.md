# Movie Lookup — что поправить в шорткате на iPhone

Шорткат собран по [actions.md](actions.md); экспорт — [shortcut.xml](shortcut.xml). Номер `#N` — индекс действия в экспорте (в редакторе его нет — ищи по комментарию шага и соседним действиям). Сверено с экспортом.

## Ошибки

1. **#87** — комментарий «16. Выбор кандидата» → внутри If `cands` → Count → Set Variable `nCands` → Get Item from List → **Get Dictionary Value**. Сейчас ключ `condidence`, надо `confidence`. Иначе `conf1` пуст и автовыбор («1 кандидат, high») не срабатывает. [README:112](README.md), [actions.md:126](actions.md).
2. **#193** — комментарий «21. Выбрать foundId» → ветка `nDocs` > 1 → Repeat по `docsMatched` → после Add to Variable `docLabels` → **Set Dictionary Value** в `docMap`. Сейчас ключ — текст `label`, надо переменная `label` (как в #104 для `labelMap`). Иначе все варианты перезаписывают один ключ. [README:161](README.md), [actions.md:240](actions.md).
3. **#310** — комментарий «25.7 premier_date» → Get Dates from Input → **Format Date**. Сейчас Custom `yyyy-HH-dd`, надо `yyyy-MM-dd`. [README:186](README.md), [actions.md:372](actions.md).
4. **#369** — комментарий «25.12 необязательны поля» → If `seasons_count` → **Set Dictionary Value** в `rec`. Сейчас ключ `season_count`, надо `seasons_count`. [README:191](README.md), [actions.md:444](actions.md), [data.md:59](../data.md).
5. **#432** — комментарий «26.2 нет кандидата…» → Choose from List (`ui.not_found`) → **If**. Сейчас Chosen Item is `{ui:not_found}`, надо `{ui:no}` (внутри — Stop This Shortcut). Иначе выход по «Нет» не работает. [README:207](README.md), [actions.md:525](actions.md).
6. **#455** — там же, ветка «есть латиница» после «26.2», действие **Replace Text** `-+` → `-` (Regular Expression). Сейчас вход `{Updated Text}{Updated Text}` (дважды), надо один `{Updated Text}` (от предыдущего Replace). Иначе слаг удваивается. [actions.md:550](actions.md).
7. **#475–#480** — конец ветки 26.2, перед End If: **List** (пустой) → Set Dictionary Value `genres` → Set Variable `rec` → **Dictionary** (пустой) → Set Dictionary Value `ratings` → Set Variable `rec`. Удалить все 6 действий: пустое значение не принимается, `genres` / `ratings` в `rec` не ставятся. [README:211](README.md), [actions.md:576](actions.md).

## Мелкое

8. **#4** — комментарий «1. Настройки» → If `settings` → **Show Notification**. Сейчас «Нет настроек movies.settings», надо «Нет movies.settings — см. README, Установка». [actions.md:13](actions.md).
9. **#48** — комментарий «10. Тексты интерфейса» → ветка Otherwise → **Dictionary** (рус.). `year`: «Какой год?» → «Год?»; `type`: «Какой тип?» → «Тип?». [README: таблица `ui`](README.md).
10. **#141** — комментарий «20. Поиск по poiskkino» → If `statusCode` → **Show Notification**. Сейчас `poiskkino: {statusCode}{message}`, надо пробел между: `poiskkino: {statusCode} {message}`.

### Необязательное

11. **#137** — там же, после Get Contents of URL → **Set Variable** `resp`: на входе приведено к Text. Убрать приведение (в [actions.md:183](actions.md) его нет); работает и так.
12. **#279–#298** — комментарии «25.5 rating» и «25.6 length»: в проверках `> 0` числа приведены к Number (в #279 лишнее действие Number). По [actions.md](actions.md) не нужно; работает и так.
13. Комментарии (косметика): `{jinopoisk_id}` → `{kinopoisk_id}` (#53, «11. Ветка а»); «Спросит ии» → «Спросит ИИ» (#63, «15.»); `premier_date` → `premiere_date` (#301, «25.7»). Комментарий «14. Ветка с: текст» (#61) стоит внутри ветки B до Otherwise — перенести после Otherwise (#62).
