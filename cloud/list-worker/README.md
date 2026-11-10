# API личного списка

Реализованы health, атомарная запись пачек, лента текущего состояния и Cron
(новинки → Telegram, ночная копия в Google-таблицу, [план этапа 5](../../docs/superpowers/plans/2026-11-10-cron-telegram.md)).
Проверка: из корня `./scripts/list-worker-test.ps1`.
Тесты выполняются в Docker Node 22 на локальной D1 без аккаунта Cloudflare; внешние сервисы замоканы.

## Развёртывание через wrangler (Docker)

Код разделён на модули (`src/worker.js`, `src/cron.js`, `src/google.js`), поэтому
вставка в панель больше не подходит — публикация только через wrangler.
В `.env` (не в git) нужны `CLOUDFLARE_API_TOKEN` (права Workers Scripts:Edit, D1:Edit)
и `CLOUDFLARE_ACCOUNT_ID`; скрипт передаёт их в контейнер как переменные окружения,
не печатая.

```powershell
./scripts/list-worker-deploy.ps1 -DryRun    # только сборка, ничего не отправляет
./scripts/list-worker-deploy.ps1 -Secrets   # секреты из .env и google-sa.json через stdin
./scripts/list-worker-deploy.ps1            # миграции --remote, затем deploy
```

Что делает основной режим, вручную (из `cloud/list-worker`, в контейнере node:22 с
`-e CLOUDFLARE_API_TOKEN -e CLOUDFLARE_ACCOUNT_ID`):

```sh
npx wrangler d1 execute vg-list --remote --yes --file .wrangler/baseline.sql  # один раз для базы из панели
npx wrangler d1 migrations apply vg-list --remote
npx wrangler deploy
```

База, созданная в панели через schema.sql, не имеет таблицы `d1_migrations`;
скрипт создаёт её и отмечает 0001/0002 применёнными (если `titles` существует),
поэтому применяется только `0003_cron.sql` (`CREATE TABLE IF NOT EXISTS`).
Для новой пустой базы отметка не срабатывает, и миграции 0001–0003 идут по порядку.

Секреты по одному (значение вводится в stdin, в команду не попадает):

```sh
npx wrangler secret put LIST_KEY           # обязательно: wrangler deploy стирает переменные из панели
npx wrangler secret put TMDB_API_KEY       # v3 (32 hex) или v4 bearer
npx wrangler secret put POISKKINO_API_KEY  # необязателен: только для записей kp-<id>
npx wrangler secret put TG_BOT_TOKEN
npx wrangler secret put TG_CHAT_ID
npx wrangler secret put GOOGLE_SHEET_ID
npx wrangler secret put GOOGLE_SA_JSON     # содержимое JSON сервисного аккаунта
```

`LIST_KEY` уже задан и при deploy сохраняется. Таблицу нужно открыть на
редактирование для `client_email` сервисного аккаунта. Необязательные переменные:
`CRON_SUBREQUESTS` (предел исходящих вызовов на запуск, по умолчанию 50),
`POISKKINO_DAILY` (запросов PoiskKino в сутки, по умолчанию 20).

Исторически воркер разворачивался в панели (D1 `vg-list`, привязка `DB`, секрет
`LIST_KEY`); адрес и ключ лежат в `.env` как `LIST_URL` и `LIST_KEY`.

## Cron

`wrangler.toml` задаёт два триггера: `0 7 * * *` (10:00 МСК) — обновление
метаданных и сообщения в Telegram; `0 0 * * *` (03:00 МСК) — полная перезапись
листов «Тайтлы» и «Серии», затем обновление метаданных на остаток лимита.
Служебные таблицы: `cron_titles` (сопоставление TMDB и нормализованные даты),
`sent_events` (журнал доставки, одно событие — одно сообщение), `cron_state`
(суточный счётчик PoiskKino). `titles` Cron не изменяет: seq и LWW клиентов
не затрагиваются. Правила событий, лимиты и дедупликация — в плане этапа 5.

Ручной запуск (тот же ключ):

```bash
curl -X POST -H 'X-VG-List-Key: <ключ>' 'https://<worker>/v1/cron/run?job=notify&dry=1'
curl -X POST -H 'X-VG-List-Key: <ключ>' 'https://<worker>/v1/cron/run?job=notify'
curl -X POST -H 'X-VG-List-Key: <ключ>' 'https://<worker>/v1/cron/run?job=backup'
curl -X POST -H 'X-VG-List-Key: <ключ>' 'https://<worker>/v1/cron/run?job=refresh'
```

`dry=1`: notify обращается к каталогам (без PoiskKino), но ничего не пишет в D1
и не шлёт; backup только считает строки. Ответ — JSON с событиями
(`key`, `type`, `caption`, `photo`, `state`: new/retry/sent/pending/failed/gave_up).
Ошибка — `500 {"error":"cron_failed","detail":"<код>"}`; в журнал Workers пишутся
только коды, без адресов и токенов.

## Контракт

Все методы (включая `/v1/cron/run`) требуют заголовок `X-VG-List-Key`. Без серверного секрета — 500,
без верного ключа — 403. Ошибки JSON: `{"error":"code"}`.

- `GET /v1/health` → `{"ok":true,"seq":0}`.
- `POST /v1/batch`: необязательные массивы `titles`, `episodes`, `positions`;
  обязательные поля строк описаны в [плане](../../docs/superpowers/plans/2026-11-07-list-cloud.md).
  `deleted` и `watched` — boolean, время и позиции — неотрицательные безопасные целые.
  Ответ: `seq` и `applied`. Все строки проверяются до записи; одна транзакция D1.
- `GET /v1/changes?since=0&limit=500`: три массива, `next`, `more`.
  Продолжать с `since=next`, пока `more=true`. limit: 1–1000; по умолчанию 500.
  Это последние версии строк, включая удаления, а не история всех правок.
  `seq` ответа batch нельзя использовать вместо курсора чтения: так можно пропустить чужие правки.

Лимиты API: 500 строк, 1 МиБ тела, 16 КиБ JSON data (UTF-8) на тайтл.
Свежая правка заменяет строку целиком; равные/старые игнорируются.
Время из будущего ограничено серверным now + 5 минут. Внутренний client_updated_at
не выдаётся клиентам и предотвращает повторное применение той же будущей правки.
Удаление требует полный снимок строки с deleted=true. Физического удаления нет.

## Ручная проверка

Пример для Bash; здесь только заглушки, настоящий ключ не коммитить:

```bash
curl -H 'X-VG-List-Key: <ключ>' 'https://<worker>/v1/health'
curl -H 'X-VG-List-Key: <ключ>' -H 'Content-Type: application/json' \
  -d '{"titles":[{"key":"ai-smoke-test","data":{"name":"Проверка"},"updated_at":100,"deleted":false}]}' \
  'https://<worker>/v1/batch'
curl -H 'X-VG-List-Key: <ключ>' 'https://<worker>/v1/changes?since=0'
curl 'https://<worker>/v1/health'
```

Первую запись на новой базе ожидаем applied=1, точный повтор — applied=0,
без заголовка — 403. Затем отправить ту же тестовую строку с updated_at=101,
deleted=true. Для повторной приёмки использовать новый уникальный тестовый ключ.

## Бесплатный тариф

Сверено 2026-11-08: D1 — 5 млн прочитанных и 100 тыс. записанных строк в сутки,
5 ГБ суммарно; одна база до 500 МБ.
[Тариф D1](https://developers.cloudflare.com/d1/platform/pricing/),
[лимиты D1](https://developers.cloudflare.com/d1/platform/limits/).
Один db.batch считается одним запросом к D1; записи счётчика и индексов тоже
расходуют квоту. Workers Free — 100 тыс. запросов в сутки, 10 мс CPU на запрос:
[лимиты Workers](https://developers.cloudflare.com/workers/platform/limits/).
Предел CPU максимальной пачки на живом Free ещё не проверен.
Сверено 2026-11-10: Free — 50 subrequests на вызов (fetch и обращения к D1/KV/R2),
10 мс CPU на Cron Trigger, 5 Cron Triggers на аккаунт. Cron считает fetch и D1
одним счётчиком и обрабатывает список порциями с курсором `checked_at`.

Тестовый пакет — @cloudflare/vitest-plugin:
[переименование интеграции](https://developers.cloudflare.com/workers/testing/vitest-integration/migration-guides/migrate-to-vitest-plugin/).
