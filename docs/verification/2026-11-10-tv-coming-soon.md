# ТВ: «Скоро» — проверки 2026-11-10

База: `f87f4a0`, изменения без коммита. [План](../superpowers/plans/2026-11-10-tv-coming-soon.md), [статус](../STATUS.md).

## Результат

Добавлены 34 unit-теста, но новые тесты и сборка не выполнены. Обе команды завершились с кодом 1 до запуска Gradle:

```powershell
./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest --tests 'dev.gamspi.videogallery.catalog.model.ReleaseDatesTest'
./scripts/gradle-docker.ps1 ./gradlew testDebugUnitTest assembleDebug --rerun-tasks
```

Причина: `Access is denied` при чтении Docker `config.json` и `buildx/instances` в профиле пользователя. Проверенный падающий TDD-тест не заявляется: Gradle не запускался. Локальный SDK не использовался.

XML в `app/build/test-results/testDebugUnitTest/` прочитаны: 82 набора, 847 тестов, 0 failures, 0 errors, 0 skipped. Время файлов: 2026-11-10 14:55:51 на хосте. Это результаты до задачи; новых отчётов ReleaseDatesTest/EpisodeAvailabilityTest нет. Они не подтверждают изменения. Новый APK не собран.

Статические проверки: `git diff --check`, декодирование изменённых файлов как UTF-8 без BOM, локальные Markdown-ссылки — без ошибок. Код плеера/трекеров/субтитров, зависимости и ABI не менялись. Чужой неотслеживаемый `2026-11-07-card-home-quickplay-design.md` сохранён.

## Покрытие написанных тестов

- Дата вчера/сегодня/завтра, приоритет даты перед годом, пустая/невалидная дата, ISO timestamp, переход года, русские подписи.
- TMDB: два discover во «Все», один для выбранного типа, дата от завтра, сортировка, маппинг первого эфира/сезона.
- PoiskKino: один запрос со смешанными типами, premiere.world, без обхода курсора и запросов деталей; повтор из HTTP-кэша; отмена до заголовков и во время тела отменяет Call.
- Кинопоиск: неподдерживаемый ряд без запросов/ключа, WORLD_PREMIER без региональных дат/повторного проката, первый эфир из первой серии, отказ необязательных метаданных.
- Главная: второй ряд, смешанные типы и одинаковые id, сортировка, фильтр, кэш вкладок, тихая ошибка и отмена при смене вкладки.
- Детали: будущий тайтл и серия не дают PlayTarget/поиск сезона, сохранённая раздача не обходит запрет; будущая серия видна; будущий сезон с нулём серий, проверка следующего сезона, сохранение доступного продолжения при смене вкладки.
- Личный список: premiere_date переживает запись/перезапуск, notify сохраняет unwatched, правка премьеры сохраняет пользовательские поля, меню различает фильм/сериал TMDB с одинаковым id.

## Открытая приёмка

- [x] Полный Docker-прогон тестов и assembleDebug, свежие XML.
- [ ] Живые TMDB/PoiskKino/Кинопоиск, дата/сортировка/кэш/число запросов.
- [ ] ТВ: все сценарии пульта из плана, включая возврат после последней вышедшей серии, когда следующее продолжение отсутствует.

Устройство, ABI/API и результаты сценариев не записаны: ТВ и adb не использовались по условию задачи. После проверки дополнить протокол commit, моделью/ABI/API и результатами, не переносить сюда прежнюю приёмку этапа 2.

## Изменённые файлы

Пути Kotlin относительно `app/src/main/java/dev/gamspi/videogallery/`:

- `catalog/CatalogRepository.kt`, `catalog/SwitchingCatalogRepository.kt`.
- `catalog/model/Movie.kt`, новый `catalog/model/ReleaseDates.kt`.
- `catalog/tmdb/TmdbApi.kt`, `TmdbCatalogRepository.kt`, `TmdbDto.kt`, `TmdbMapper.kt`.
- `catalog/poiskkino/PoiskKinoApi.kt`, `PoiskKinoCatalogRepository.kt`, `PoiskKinoDto.kt`, `PoiskKinoMapper.kt`.
- `catalog/kinopoisk/KinopoiskApi.kt`, `KinopoiskCatalogRepository.kt`, `KinopoiskDto.kt`, `KinopoiskMapper.kt`.
- `details/DetailsScreen.kt`, `DetailsViewModel.kt`, `SeriesDetails.kt`, новый `EpisodeAvailability.kt`.
- `gallery/GalleryViewModel.kt`, `SavedListContent.kt`.
- `ui/components/CardBadges.kt`, `MovieCard.kt`.
- `watchlist/ListActions.kt`, `LocalListStore.kt`.

Пути тестов относительно `app/src/test/java/dev/gamspi/videogallery/`:

- `catalog/FakeCatalogRepository.kt`, `SwitchingCatalogRepositoryTest.kt`.
- Новый `catalog/model/ReleaseDatesTest.kt`.
- `catalog/tmdb/TmdbCatalogRepositoryTest.kt`.
- `catalog/poiskkino/PoiskKinoCatalogRepositoryTest.kt`.
- `catalog/kinopoisk/KinopoiskCatalogRepositoryTest.kt`.
- `details/DetailsViewModelTest.kt`, новый `EpisodeAvailabilityTest.kt`.
- `gallery/GalleryViewModelTest.kt`.
- `watchlist/ListFirstEditTest.kt`.

Документы: `docs/STATUS.md`, `docs/BACKLOG.md`, новые `docs/superpowers/plans/2026-11-10-tv-coming-soon.md` и этот протокол.

## Docker вне песочницы, 2026-11-10

Docker `testDebugUnitTest assembleDebug --rerun-tasks` 2026-11-10 (запуск вне песочницы): BUILD SUCCESSFUL, XML — 881 тест, 0 failures/errors/skipped; APK собран.
