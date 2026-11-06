# Прокси через Cloudflare Worker и ключи из `.env`: план

Дата: 2026-11-06. Решения пользователя приняты в диалоге 2026-11-06. Второй план (источник rutracker) — отдельно, после приёмки этого.

## Факты (проверено с ТВ 2026-11-06, ByeDPI выключен)

- `api.themoviedb.org`, `image.tmdb.org` → DNS 127.0.0.1; `rutor.info` и `rutracker.org` — TCP/HTTPS режет провайдер (`000:0`).
- Cloudflare не режут: `speed.cloudflare.com` 5 МБ за 14,7 с (~340 КБ/с), обрыва на 16 КБ нет. `workers.dev` (8.6.112.0) TCP 443 открыт; скорость проверить после деплоя.
- Торрент-трафик идёт напрямую (TCP-пиры через HTTP-трекеры работают); через прокси его не пускаем.

## Решения

- **Воркер:** `proxy/cloudflare-worker/worker.js`, деплой — пользователь вставляет в панели Cloudflare. Запрос `https://<worker>/<host>/<path>?<query>`. Белый список хостов: `api.themoviedb.org`, `image.tmdb.org`, `rutor.info` и поддомены (`d.rutor.info`), `rutracker.org` и поддомены (для этапа 2). Иначе 403.
- **Защита:** заголовок `X-VG-Key` сравнивается с секретом воркера `PROXY_KEY` (Settings → Variables → Secret). Без совпадения 403. В приложении ключ в `SecretStore`.
- **Воркер передаёт:** метод (GET/HEAD/POST), тело, заголовки запроса без `Host`, `X-VG-Key`, `cf-*`; `redirect: "manual"` — редирект с абсолютным `Location` на исходный хост приложение снова пускает через прокси. Ответ отдаётся как есть.
- **Приложение:** настройки «Прокси через Cloudflare» (переключатель, по умолчанию выключен), «Адрес прокси» и «Ключ прокси» (маскированный). `ProxyInterceptor` переписывает запросы к хостам из списка на воркер, только если переключатель включён и адрес задан; иначе запрос без изменений. Переключение действует сразу, без перезапуска (интерсептор читает текущее значение настроек на каждый запрос). Подключается к клиентам каталога (TMDB), торрент-провайдеров (rutor), `TorrentFileFetcher` и к загрузчику постеров Coil. OpenSubtitles, Kinopoisk, PoiskKino, apibay, torrent — напрямую.
- **`.env` → сборка:** Gradle читает `.env` в корне (если файла нет — пусто), кладёт значения в `BuildConfig`. Ключи: `KINOPOISK_API_KEY`, `TMDB_API_KEY`, `POISKKINO_API_KEY`, `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME`, `OPENSUBTITLES_PASSWORD`, `PROXY_URL`, `PROXY_KEY`, `RUTRACKER_USERNAME`, `RUTRACKER_PASSWORD`.
- **Приоритет:** значение на ТВ главнее; `.env` только заполняет пустые. Реализация — декоратор `SecretStore`: `get` возвращает сохранённое значение, а если оно пустое — значение из `BuildConfig`. Без записи и без гонки при старте. Следствие: очищенное на ТВ поле снова покажет значение из `.env`.
- **Риск принят пользователем:** ключи из `.env` попадают внутрь APK; APK не распространяется. В git, документацию, логи и тесты значения не попадают.

## Задачи

| # | Что | Исполнитель | Файлы (основные) |
|---|---|---|---|
| 1 | Воркер + инструкция деплоя | субагент | `proxy/cloudflare-worker/worker.js`, `proxy/cloudflare-worker/README.md` |
| 2 | `.env` → `BuildConfig`, декоратор `SecretStore` с тестами | субагент | `app/build.gradle.kts`, `settings/SecretStore*.kt`, `settings/SettingsModule.kt` |
| 3 | Настройки прокси, `ProxyInterceptor` с тестами, подключение к клиентам и Coil | субагент, после 2 | `settings/*`, `net/ProxyInterceptor.kt`, `catalog/CatalogModule.kt`, `torrents/TorrentsModule.kt`, `stream/StreamModule.kt`, загрузчик Coil |
| 4 | Деплой воркера | пользователь | панель Cloudflare, адрес и ключ в `.env` |
| 5 | Сборка, тесты, приёмка на ТВ: TMDB-каталог и постеры, поиск rutor и скачивание .torrent, скорость `workers.dev` (curl), без прокси — как раньше; протокол, STATUS/BACKLOG | Claude + пользователь | `docs/**` |

## Ограничения

Docker-сборка, `armeabi-v7a`, без сети/файлов на UI-потоке, не глотать `CancellationException`, значения секретов не печатать (ни в логах, ни в выводе сборки). Лимит бесплатного воркера — 100 тыс. запросов в сутки.

## Известно для этапа 2 (rutracker)

Cookie входа: `CookieJar` OkHttp видит адрес воркера, а не `rutracker.org`, — воркеру нужно переписывать `Domain` в `Set-Cookie` или хранить cookie по исходному хосту в приложении.
