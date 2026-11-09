# API личного списка

Реализованы health, атомарная запись пачек и лента текущего состояния.
Android пока не подключён. Проверка: из корня `./scripts/list-worker-test.ps1`.
Тесты выполняются в Docker Node 22 на локальной D1 без аккаунта Cloudflare.

## Развёртывание в панели

1. Создать D1 `vg-list`. В консоли новой базы выполнить `schema.sql`.
2. Создать отдельный Worker, вставить `src/worker.js` (ES module).
3. Settings → Bindings → D1: имя `DB`, база `vg-list`.
4. Добавить секрет `LIST_KEY`: длинная случайная строка, отдельная от ключа прокси.
5. Deploy. Сохранить HTTPS-адрес и секрет локально в `.env` как `LIST_URL` и `LIST_KEY`.

Если база уже создана по старому каркасу, выполнить только
`migrations/0002_client_time.sql`, затем обновить воркер. Для новой базы полный
schema.sql эквивалентен миграциям 0001 + 0002; повторно применять их не нужно.
В wrangler.toml database_id — локальная заглушка; для публикации через CLI заменить
его настоящим идентификатором. Секрет в конфигурацию не записывать.

## Контракт

Все методы требуют заголовок `X-VG-List-Key`. Без серверного секрета — 500,
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

Тестовый пакет — @cloudflare/vitest-plugin:
[переименование интеграции](https://developers.cloudflare.com/workers/testing/vitest-integration/migration-guides/migrate-to-vitest-plugin/).
