# Облако списка (этап 1): D1 и API

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Дата: 2026-11-07. Статус: начат 2026-11-08; задачи 1–3 реализованы и проверены (30 тестов в Docker). Задача 4: развёртывание и живая приёмка пройдены 2026-11-08. Этап 1 принят; нагрузочная проверка CPU Free остаётся отдельным ограничением.

**Goal:** отдельный Cloudflare Worker с базой D1, который хранит тайтлы, серии и места остановки и отдаёт изменения клиентам (ТВ, iPhone, позже PWA) по правилу «свежая правка побеждает».

**Architecture:** новый воркер `cloud/list-worker/` (не прокси: свой ключ, своя база, позже Cron). Один файл `src/worker.js` без сборщика, чтобы его можно было вставить в панель Cloudflare, плюс `schema.sql`. Клиент пишет пачку правок `POST /v1/batch`, читает изменения `GET /v1/changes?since=<seq>`. Каждая принятая правка получает глобальный растущий номер `seq`; клиент хранит последний увиденный `seq`.

**Tech Stack:** Cloudflare Workers (ES modules), D1 (SQLite), Vitest + `@cloudflare/vitest-pool-workers` (локальный D1 через Miniflare), Node 22 в Docker.

**Spec:** [docs/superpowers/specs/2026-11-07-watchlist-sync-design.md](../specs/2026-11-07-watchlist-sync-design.md)

## Global Constraints

- Ключ доступа — секрет воркера `LIST_KEY`, заголовок `X-VG-List-Key`; не ключ прокси. Нет секрета → 500, неверный ключ → 403 (закрыт по умолчанию, как прокси).
- Секреты не попадают в код, тесты, README, журналы. В тестах — фиктивный ключ из конфигурации тестов.
- Конфликт: вся строка берётся из правки с бо́льшим `updated_at` (мс, время клиента). Равное или меньшее — правка игнорируется.
- Удаление — метка `deleted = 1`, строка не стирается.
- Ключ тайтла: `^(tt\d{5,10}|kp-\d{1,10}|tmdb-(movie|tv)-\d{1,10}|ai-[a-z0-9-]{1,80})$`.
- Только Docker: локальные Node/npm на хосте не требуются. Версии зависимостей точные, `package-lock.json` в git.
- Комментарии в коде кратко на английском; README и ответы об ошибках для человека — на русском не обязательно, тексты ошибок API — короткие английские коды (`bad_key`, `too_large`).
- Бесплатный тариф: один запрос клиента — один `db.batch()` (транзакция) на запись; без циклов запросов на элемент.

## Review Focus

1. Часы клиента впереди: `updated_at` из будущего навсегда «выигрывал» бы. Ожидание: сервер урезает `updated_at` до `now + 5 мин`. Тест в задаче 2.
2. Повторная отправка той же пачки (обрыв сети после записи): ожидание — повтор ничего не меняет и не создаёт новых `seq`. Тест в задаче 2.
3. Очень длинная лента изменений (первая синхронизация): ожидание — страницы по `limit`, `next` и `more`, ни одна строка не теряется на границе страниц. Тест в задаче 3.
4. Мусор на входе (не JSON, `data` не объект, огромное тело, неизвестный ключ): ожидание — 400/413 без частичной записи. Тест в задаче 2.
5. Удалённый тайтл и поздняя правка с меньшим временем: ожидание — метка удаления остаётся. Тест в задаче 2.

---

### Task 1: Каркас воркера, тестовый стенд в Docker, здоровье и ключ

**Files:**
- Create: `cloud/list-worker/package.json`, `package-lock.json`, `wrangler.toml`, `vitest.config.js`, `schema.sql`, `migrations/0001_init.sql` (тот же SQL, для тестов), `src/worker.js`, `test/auth.test.js`
- Create: `scripts/list-worker-test.ps1` (Docker `node:22` с монтированием `cloud/list-worker`, `npm ci && npx vitest run`)
- Modify: `.gitignore` (`cloud/list-worker/node_modules/`, `.wrangler/`)

**Interfaces:**
- Produces: `export default { fetch(request, env) }`; привязка D1 `env.DB`; секрет `env.LIST_KEY`; ответы JSON `{"error":"<code>"}` при ошибке.
- Produces схему:
  - `titles(key TEXT PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL)`
  - `episodes(title_key TEXT, season INTEGER, episode INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL, PRIMARY KEY(title_key, season, episode))`
  - `positions(title_key TEXT, season INTEGER, episode INTEGER, position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL, PRIMARY KEY(title_key, season, episode))` — фильм: `season = 0, episode = 0`
  - `meta(k TEXT PRIMARY KEY, v INTEGER NOT NULL)` со строкой `('seq', 0)`
  - индексы по `seq` во всех трёх таблицах

- [x] **Step 1: Стенд.** `package.json` с точными версиями `wrangler`, `vitest`, `@cloudflare/vitest-pool-workers` (совместимая тройка из документации пула; сверить через context7). `wrangler.toml`: `main = "src/worker.js"`, `compatibility_date`, `[[d1_databases]] binding = "DB"`, `migrations_dir = "migrations"`. `vitest.config.js` применяет миграции к тестовому D1 и задаёт `LIST_KEY = "test-key"` через `miniflare.bindings`.
- [x] **Step 2: Тесты `test/auth.test.js`:**
  - `GET /v1/health` с верным ключом → 200, `{"ok":true,"seq":0}`
  - без заголовка → 403 `{"error":"bad_key"}`; неверный ключ → 403
  - `LIST_KEY` не задан (env без ключа) → 500 `{"error":"not_configured"}`
  - неизвестный путь → 404 `{"error":"not_found"}`; `PUT /v1/health` → 405
- [ ] **Step 3:** `scripts/list-worker-test.ps1` → тесты падают (нет обработчиков).
- [x] **Step 4:** Реализовать маршрутизацию, проверку ключа (сравнение SHA-256 за постоянное время — перенести `keyMatches` из `proxy/cloudflare-worker/worker.js`), `/v1/health` читает `meta.seq`.
- [x] **Step 5:** Тесты зелёные.
- [ ] **Step 6: Commit** `feat(cloud): list worker skeleton with D1 schema and auth`

### Task 2: Запись пачки `POST /v1/batch`

**Files:**
- Modify: `cloud/list-worker/src/worker.js`
- Test: `cloud/list-worker/test/batch.test.js`

**Interfaces:**
- Consumes: схема и проверка ключа из задачи 1.
- Produces запрос:
  ```json
  {"titles":[{"key":"tt0111161","data":{...},"updated_at":1762500000000,"deleted":false}],
   "episodes":[{"title_key":"tt0903747","season":2,"episode":5,"watched":true,"updated_at":...,"deleted":false}],
   "positions":[{"title_key":"tt0111161","season":0,"episode":0,"position_ms":600000,"duration_ms":8520000,"updated_at":...,"deleted":false}]}
  ```
  ответ `200 {"seq": <текущий seq после записи>, "applied": <число принятых строк>}`. Любой из трёх массивов можно опустить.

- [x] **Step 1: Тесты `test/batch.test.js`:**
  - новая строка тайтла → `applied: 1`, `seq: 1`; `data` возвращается тем же объектом через `GET /v1/changes` (после задачи 3 — до неё проверять прямым `env.DB` запросом)
  - правка с бо́льшим `updated_at` заменяет строку целиком; с равным или меньшим — `applied: 0`, строка и `seq` не меняются
  - повтор той же пачки → `applied: 0`, `seq` прежний (Review Focus 2)
  - `updated_at` = `now + 1 день` → сохраняется `≤ now + 300000` (Review Focus 1)
  - удаление (`deleted: true`, свежее время) → `deleted = 1`, `data` сохраняется последней; поздняя правка с меньшим временем не снимает метку (Review Focus 5)
  - серии и позиции: те же правила независимо от тайтла; правка тайтла не трогает серии
  - ошибки без частичной записи (Review Focus 4): тело не JSON → 400 `bad_json`; `key` не по шаблону → 400 `bad_key_format`; `data` не объект → 400 `bad_data`; `season`/`episode` не целые 0..9999 → 400 `bad_episode`; `position_ms`/`duration_ms` отрицательные → 400 `bad_position`; больше 500 строк суммарно → 413 `too_many`; тело > 1 МБ или `data` одной строки > 16 КБ в JSON → 413 `too_large`. После любой ошибки `seq` = 0 и таблицы пусты.
- [ ] **Step 2:** Тесты падают.
- [x] **Step 3:** Реализовать: сначала полная проверка всей пачки, затем один `env.DB.batch([...])`. Условная вставка `INSERT … ON CONFLICT … DO UPDATE … WHERE excluded.updated_at > <table>.updated_at`; строке, которая реально изменилась, — следующий `seq` из `meta` (счётчик увеличивается в той же транзакции только для применённых строк). `applied` — сумма `meta.changes` по операциям.
- [x] **Step 4:** Тесты зелёные.
- [ ] **Step 5: Commit** `feat(cloud): batch upsert with last-write-wins`

### Task 3: Лента изменений `GET /v1/changes`

**Files:**
- Modify: `cloud/list-worker/src/worker.js`
- Test: `cloud/list-worker/test/changes.test.js`

**Interfaces:**
- Consumes: таблицы и `seq` из задач 1–2.
- Produces `GET /v1/changes?since=<int ≥0>&limit=<1..1000, по умолчанию 500>` →
  ```json
  {"titles":[{"key":…,"data":{…},"updated_at":…,"deleted":false,"seq":…}],
   "episodes":[…], "positions":[…],
   "next": <наибольший seq в ответе или since, если пусто>, "more": <true, если есть строки с seq > next>}
  ```
  Строки всех трёх таблиц с `seq > since`, вместе не больше `limit`, по возрастанию `seq`; `deleted` — boolean, `data` — объект.

- [x] **Step 1: Тесты:**
  - пустая база → пустые массивы, `next: 0`, `more: false`
  - `since` отсекает уже виденное; удалённые строки приходят с `deleted: true`
  - 1200 строк вперемешку по таблицам, `limit=500`: три страницы по `next`, объединение = все 1200 без повторов, на последней `more: false` (Review Focus 3)
  - `since=-1`, `since=abc`, `limit=0`, `limit=5000` → 400 `bad_query`
- [ ] **Step 2:** Тесты падают.
- [x] **Step 3:** Реализовать: `UNION ALL` трёх таблиц по `seq > ?` с `ORDER BY seq LIMIT ?+1` (лишняя строка → `more`).
- [x] **Step 4:** Тесты зелёные; весь набор `scripts/list-worker-test.ps1` зелёный.
- [ ] **Step 5: Commit** `feat(cloud): changes feed with seq cursor`

### Task 4: Развёртывание и приёмка

**Files:**
- Create: `cloud/list-worker/README.md`
- Create: `docs/verification/2026-11-07-list-cloud.md`
- Modify: `docs/STATUS.md`, `docs/BACKLOG.md`, этот план (статус)

- [x] **Step 1: README** (по образцу `proxy/cloudflare-worker/README.md`): создать D1 `vg-list` в панели; в консоли D1 выполнить `schema.sql`; создать воркер, вставить `src/worker.js`; Settings → Bindings → D1 `DB` → `vg-list`; секрет `LIST_KEY` (длинная случайная строка); Deploy. Проверки `curl` с `<ключ>`-заглушкой: health, пачка из одного тайтла, changes. Лимиты бесплатного тарифа D1 и Workers — сверить с документацией Cloudflare и вписать с датой проверки.
- [x] **Step 2: Пользователь** разворачивает и кладёт адрес и ключ в `.env` как `LIST_URL`, `LIST_KEY` (значения не печатать).
- [x] **Step 3: Приёмка** с хоста `curl`: health → `seq`; запись тайтла → `applied: 1`; повтор → `applied: 0`; changes → строка видна; без ключа → 403. Тестовую строку затем пометить удалённой. Записать в протокол итог без секретов.
- [x] **Step 4:** STATUS, BACKLOG, статус плана; `git diff --check`.
- [ ] **Step 5: Commit** `docs: list cloud deploy and acceptance`

Проверка задачи 1: [протокол](../../verification/2026-11-07-list-cloud.md). Вместо устаревшего пула используется @cloudflare/vitest-plugin 1.6.0, Vitest 5.1.0, Wrangler 4.158.0. Исторический red-прогон не выполнялся; коммиты автоматически не создаются по AGENTS.md.

Уточнение задач 2–3: тесты объединены в test/sync.test.js. Миграция 0002 добавляет client_updated_at для повторов правок с будущим временем; schema.sql = 0001 + 0002. Проверка атомарности включает ошибку БД и конкурирующие запросы. Red-прогон не выполнялся.
