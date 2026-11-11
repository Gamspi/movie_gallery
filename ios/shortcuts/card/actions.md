# Movie Card — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat / Menu.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для списков (`Add to Variable` копит между оборотами Repeat) и флагов в начале каждого оборота.
- **Меню → `act`**: пункты только ставят `act`, сами действия — ниже отдельными If. Так общие пункты (ссылки, Постер, Похожие…) не дублируются.
- **Скрытие пунктов**: Choose from Menu статичен, поэтому главное меню — **Choose from List** по списку `menuItems`, в который неприменимые пункты не добавляются (If → Add to Variable). Метка → `act` через словарь `menuMap`.
- **Число из словаря** (Data Jar, ответ API, `Repeat Item: поле`) приходит без типа — If не даёт сравнений «больше/меньше». Перед сравнением: `Number [x]` → сравнивать `[Number]`.
- **Вложенный Repeat** внутри `Repeat 50`: iOS может назвать плашки `Repeat Item 2` / `Repeat Index 2` — бери плашку своего Repeat.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If
// синхронизация раз в день (Movie Sync сам решает, нужен ли запрос)
Text "auto"
Run Shortcut "Movie Sync" with [Text]

// ── Шаг 2. Ключ API ──
Get Value for "poiskkino_api_key" in [settings]
Set variable apiKey to [Dictionary Value]
If [apiKey] does not have any value
    Show notification "Нет ключа poiskkino в movies.settings"
    Stop this shortcut
End If

// ── Шаг 3. Теги ──
Get Value for "tags" in [settings]
Set variable tagsList to [Dictionary Value]

// ── Шаг 4. Список жанров для ИИ ──
Get Value for "genres" in [settings]
Combine [Dictionary Value] with Custom ", "
Set variable genreList to [Combined Text]

// ── Шаг 5. Метки типов ──
Get Value for "type_labels" in [settings]
Set variable typeLabels to [Dictionary Value]

// ── Шаг 6. Карта типов ──
Get Value for "type_map" in [settings]
Set variable typeMap to [Dictionary Value]

// ── Шаг 7. ИИ включён? ──
Get Value for "ai_enabled" in [settings]
Set variable aiOn to [Dictionary Value]
// не задан → пусто → проверка «is False» не сработает = ИИ включён

// ── Шаг 8. Метки статусов ──
Dictionary
    unwatched  Text  Не смотрел
    watching   Text  Смотрю
    watched    Text  Смотрел
    dropped    Text  Бросил
    dismissed  Text  Не предлагать
Set variable statusLabels to [Dictionary]

// ── Шаг 8a. Метки статуса сериала ──
Dictionary
    ongoing    Text  Идёт
    completed  Text  Завершён
    cancelled  Text  Отменён
Set variable releaseLabels to [Dictionary]

// ── Шаг 8b. Пункт меню → действие ──
Dictionary
    Оценить            Text  rate
    Статус             Text  status
    Статус сериала     Text  rstatus
    Уведомления: вкл   Text  notify
    Уведомления: выкл  Text  notify
    Теги               Text  tags
    Заметка            Text  note
    Спросить ИИ        Text  ask
    Кинопоиск          Text  kp
    IMDb               Text  imdb
    Трейлер            Text  trailer
    Постер             Text  poster
    Похожие            Text  similar
    Поделиться         Text  share
    Обновить данные    Text  update
    В календарь        Text  calendar
    Удалить            Text  delete
    Назад              Text  back
    Добавить в список  Text  add
    Смотрел + оценка   Text  watched
    Не предлагать      Text  dismiss
Set variable menuMap to [Dictionary]

// ── Шаг 9. Вход ──
Get Value for "key" in [Shortcut Input]
Set variable key to [Dictionary Value]
Get Value for "predicted" in [Shortcut Input]
Set variable predicted to [Dictionary Value]

// ── Шаг 10. Сохранённая запись ──
If [key] has any value
    Get Value for "movies.items.[key]" (Data Jar)
    Set variable rec to [Value]
    If [rec] does not have any value
        Show notification "Запись не найдена"
        Dictionary
            action  Text  back
        Stop and output [Dictionary]
    End If
    Text "yes"
    Set variable saved to [Text]

// ── Шаг 11. Несохранённая запись ──
Otherwise
    Get Value for "record" in [Shortcut Input]
    Set variable rec to [Dictionary Value]
    Text "no"
    Set variable saved to [Text]

    // 11.1 Ленивое обогащение через Lookup
    Get Value for "source" in [rec]
    If [Dictionary Value] is not "poiskkino"
        Get Value for "kinopoisk_id" in [rec]
        If [Dictionary Value] has any value
            Dictionary
                kinopoisk_id  Number  [rec: kinopoisk_id]
            Set variable lkIn to [Dictionary]
        Otherwise
            Dictionary
                title_en  Text    [rec: title_en]
                title_ru  Text    [rec: title_ru]
                year      Number  [rec: year]
                type      Text    [rec: type]
            Set variable lkIn to [Dictionary]
        End If
        Run Shortcut "Movie Lookup" with [lkIn]
        If [Shortcut Result] has any value
            Set variable rec to [Shortcut Result]
        End If
    End If

    // 11.2 Уже в базе?
    Get Value for "key" in [rec]
    Set variable rk to [Dictionary Value]
    If [rk] has any value
        Check if Value Exists for "movies.items.[rk]" (Data Jar)
        If [Value Exists] is True
            Set variable key to [rk]
            Get Value for "movies.items.[key]" (Data Jar)
            Set variable rec to [Value]
            Text "yes"
            Set variable saved to [Text]
        End If
    End If
End If

// ── Шаг 12. Сразу «Похожие»? ──
Get Value for "auto" in [Shortcut Input]
If [Dictionary Value] is "similar"
    Number 1
    Set variable doSimilar to [Number]
End If

// ── Шаг 13. Цикл карточки ──
Repeat 50 times

    // 13.1 Сброс флагов оборота
    Nothing
    Set variable act
    Nothing
    Set variable afterRate
    Nothing
    Set variable saveStatus

    // 13.2 Свежие данные
    If [saved] is "yes"
        Get Value for "movies.items.[key]" (Data Jar)
        Set variable rec to [Value]
    End If

    // ── Шаг 14. Текст карточки + меню (пропуск, если сразу «Похожие») ──
    If [doSimilar] is not 1
        Nothing
        Set variable lines

        // 14.0 Флаги: будущая премьера, сериал
        Number 0
        Set variable isFuture to [Number]
        Number 0
        Set variable isSeries to [Number]
        Get Value for "premiere_date" in [rec]
        Set variable fDate to [Dictionary Value]
        // нет premiere_date → 1 января года выхода (год больше текущего = будущая премьера)
        If [fDate] does not have any value
            Get Value for "year" in [rec]
            If [Dictionary Value] has any value
                Text "[Dictionary Value]-01-01"
                Set variable fDate to [Text]
            End If
        End If
        If [fDate] has any value
            Get dates from [fDate]
            If [Dates] is after [Current Date]
                Number 1
                Set variable isFuture to [Number]
            End If
        End If
        Get Value for "type" in [rec]
        Set variable recType to [Dictionary Value]
        If Any are true: [recType] is "tv_show", [recType] is "animated_series", [recType] is "anime"
            Number 1
            Set variable isSeries to [Number]
        End If

        // 14.1 Названия
        Text "[rec: title_ru] / [rec: title_en]"
        Add [Text] to lines

        // 14.2 Год · тип · длина · возраст
        Nothing
        Set variable parts
        Get Value for "year" in [rec]
        If [Dictionary Value] has any value
            Add [Dictionary Value] to parts
        End If
        Get Value for "type" in [rec]
        If [Dictionary Value] has any value
            Get Value for [Dictionary Value] in [typeLabels]
            If [Dictionary Value] has any value
                Add [Dictionary Value] to parts
            End If
        End If
        Get Value for "length" in [rec]
        If [Dictionary Value] has any value
            Text "[Dictionary Value] мин"
            Add [Text] to parts
        End If
        Get Value for "age_rating" in [rec]
        If [Dictionary Value] has any value
            Text "[Dictionary Value]+"
            Add [Text] to parts
        End If
        If [parts] has any value
            Combine [parts] with Custom " · "
            Add [Combined Text] to lines
        End If

        // 14.3 Жанры
        Get Value for "genres" in [rec]
        If [Dictionary Value] has any value
            Combine [Dictionary Value] with Custom ", "
            Add [Combined Text] to lines
        End If

        // 14.3a В ролях (в записи уже первые 5)
        Get Value for "actors" in [rec]
        If [Dictionary Value] has any value
            Combine [Dictionary Value] with Custom ", "
            Text "В ролях: [Combined Text]"
            Add [Text] to lines
        End If

        // 14.4 Рейтинги
        Nothing
        Set variable parts
        // ratings может не быть — путь "ratings.imdb" тогда падает
        Get Value for "ratings" in [rec]
        Set variable rts to [Dictionary Value]
        If [rts] does not have any value
            Dictionary   (пустой)
            Set variable rts to [Dictionary]
        End If
        Get Value for "imdb" in [rts]
        If [Dictionary Value] has any value
            Text "IMDb [Dictionary Value]"
            Add [Text] to parts
        End If
        Get Value for "kp" in [rts]
        If [Dictionary Value] has any value
            Text "КП [Dictionary Value]"
            Add [Text] to parts
        End If
        Get Value for "my_rating" in [rec]
        If [Dictionary Value] has any value
            Text "мой балл [Dictionary Value]"
            Add [Text] to parts
        End If
        Combine [parts] with Custom " · "
        Set variable rLine to [Combined Text]
        If [predicted] has any value
            Text "[rLine] | прогноз [predicted]"
            Set variable rLine to [Text]
        End If
        If [rLine] has any value
            Add [rLine] to lines
        End If

        // 14.5 Статус
        // у несохранённой (из похожих) status нет → пустой ключ уронит Get Value
        Get Value for "status" in [rec]
        If [Dictionary Value] has any value
            Get Value for [Dictionary Value] in [statusLabels]
            If [Dictionary Value] has any value
                Text "Статус: [Dictionary Value]"
                Add [Text] to lines
            End If
        End If

        // 14.5a Сериал: статус выхода и следующая серия
        If [isSeries] is 1
            Get Value for "release_status" in [rec]
            If [Dictionary Value] has any value
                Get Value for [Dictionary Value] in [releaseLabels]
                If [Dictionary Value] has any value
                    Text "Сериал: [Dictionary Value]"
                    Add [Text] to lines
                End If
            End If
            Get Value for "next_episode" in [rec]
            If [Dictionary Value] has any value
                Text "Следующая: [Dictionary Value]"
                Add [Text] to lines
            End If
            // последняя просмотренная серия (movies.episodes, приходит синхронизацией)
            Check if Value Exists for "movies.episodes.[key]" (Data Jar)
            If [Value Exists] is True
                Get Value for "movies.episodes.[key]" (Data Jar)
                Get All Keys in [Value]
                Number 0
                Set variable epBest to [Number]
                Nothing
                Set variable epKey
                Repeat with each item in [Keys]
                    // ключ c20005 = сезон 2, серия 5
                    Replace "c" with "" in [Repeat Item 2]
                    Number [Updated Text]
                    If [Number] is greater than [epBest]
                        Set variable epBest to [Number]
                        Set variable epKey to [Repeat Item 2]
                    End If
                End Repeat
                If [epKey] has any value
                    Get Value for [epKey] in [Value]
                    Text "Остановился: [Dictionary Value]"
                    Add [Text] to lines
                End If
            End If
        End If

        // 14.6 Теги
        Get Value for "tags" in [rec]
        If [Dictionary Value] has any value
            Combine [Dictionary Value] with Custom ", "
            Text "Теги: [Combined Text]"
            Add [Text] to lines
        End If

        // 14.7 Заметка
        Get Value for "note" in [rec]
        If [Dictionary Value] has any value
            Text "Заметка: [Dictionary Value]"
            Add [Text] to lines
        End If

        // 14.8 Описание
        Get Value for "description" in [rec]
        If [Dictionary Value] has any value
            Add [Dictionary Value] to lines
        End If

        Combine [lines] with New Lines
        Set variable cardText to [Combined Text]

        // 14.9 Пункты меню (неприменимые не добавляются)
        Nothing
        Set variable menuItems
        If [saved] is "yes"
            If [isFuture] is not 1
                Text "Оценить"
                Add [Text] to menuItems
            End If
            Text "Статус"
            Add [Text] to menuItems
            If [isSeries] is 1
                Text "Статус сериала"
                Add [Text] to menuItems
                If [rec: notify (Boolean)] is True
                    Text "Уведомления: вкл"
                    Add [Text] to menuItems
                Otherwise
                    Text "Уведомления: выкл"
                    Add [Text] to menuItems
                End If
            End If
            // каждый пункт: Text "…" → Add [Text] to menuItems
            Теги · Заметка · Спросить ИИ · Кинопоиск · IMDb · Трейлер · Постер · Похожие · Поделиться · Обновить данные
            If [isFuture] is 1
                Text "В календарь"
                Add [Text] to menuItems
            End If
            Удалить · Назад
        Otherwise
            Добавить в список
            If [isFuture] is not 1
                Text "Смотрел + оценка"
                Add [Text] to menuItems
            End If
            Не предлагать · Спросить ИИ · Кинопоиск · IMDb · Трейлер · Постер · Похожие · Поделиться · Назад
        End If

        // 14.10 Выбор пункта → act
        Choose from [menuItems]   (Prompt: [cardText])
        Get Value for [Chosen Item] in [menuMap]
        Set variable act to [Dictionary Value]
        If [act] is "similar"
            Number 1
            Set variable doSimilar to [Number]
        End If
    End If

    // ── Шаг 15. Статус (до «Оценить»: может включить оценку) ──
    If [act] is "status"
        // будущая премьера — только «Не смотрел» и «Не предлагать»
        If [isFuture] is 1
            Choose from Menu   (Prompt: "Статус")
                • Не смотрел
                    Text "unwatched"
                    Set variable newStatus to [Text]
                • Не предлагать
                    Text "dismissed"
                    Set variable newStatus to [Text]
            End Menu
        Otherwise
            Choose from Menu   (Prompt: "Статус")
                • Не смотрел
                    Text "unwatched"
                    Set variable newStatus to [Text]
                • Смотрю
                    Text "watching"
                    Set variable newStatus to [Text]
                • Смотрел
                    Text "watched"
                    Set variable newStatus to [Text]
                • Бросил
                    Text "dropped"
                    Set variable newStatus to [Text]
                • Не предлагать
                    Text "dismissed"
                    Set variable newStatus to [Text]
            End Menu
        End If
        Date   (Current Date)
        Format [Date] with ISO 8601
        Set variable now to [Formatted Date]
        Set Value [newStatus] for "movies.items.[key].status" (Data Jar)
        Set Value [now] for "movies.items.[key].updated_date" (Data Jar)
        If [newStatus] is "watched"
            Get Value for "watched_date" in [rec]
            If [Dictionary Value] does not have any value
                Set Value [now] for "movies.items.[key].watched_date" (Data Jar)
            End If
            Get Value for "my_rating" in [rec]
            If [Dictionary Value] does not have any value
                Choose from Menu   (Prompt: "Оценить сейчас?")
                    • Да
                        Text "rate"
                        Set variable act to [Text]
                    • Нет
                End Menu
            End If
        End If
    End If

    // ── Шаг 15a. Статус сериала ──
    If [act] is "rstatus"
        Choose from Menu   (Prompt: "Статус сериала")
            • Идёт
                Text "ongoing"
                Set variable newRS to [Text]
            • Завершён
                Text "completed"
                Set variable newRS to [Text]
            • Отменён
                Text "cancelled"
                Set variable newRS to [Text]
        End Menu
        Date   (Current Date)
        Format [Date] with ISO 8601
        Set variable now to [Formatted Date]
        Set Value [newRS] for "movies.items.[key].release_status" (Data Jar)
        Set Value [now] for "movies.items.[key].updated_date" (Data Jar)
    End If

    // ── Шаг 15b. Уведомления о сериях (notify): переключить ──
    If [act] is "notify"
        If [rec: notify (Boolean)] is True
            Dictionary
                v  Boolean  False
            Set variable nv to [Dictionary]
        Otherwise
            Dictionary
                v  Boolean  True
            Set variable nv to [Dictionary]
        End If
        Get Value for "v" in [nv]
        Set Value [Dictionary Value] for "movies.items.[key].notify" (Data Jar)
        Date   (Current Date)
        Format [Date] with ISO 8601
        Set Value [Formatted Date] for "movies.items.[key].updated_date" (Data Jar)
    End If

    // ── Шаг 16. Оценить ──
    If [act] is "rate"
        Ask for Number with "Оценка 1–10"
        Set variable r to [Provided Input]
        If Any are true: [r] is less than 1, [r] is greater than 10
            Show notification "1–10"
        Otherwise
            Date   (Current Date)
            Format [Date] with ISO 8601
            Set variable now to [Formatted Date]
            Set Value [r] for "movies.items.[key].my_rating" (Data Jar)
            Set Value "watched" for "movies.items.[key].status" (Data Jar)
            Set Value [now] for "movies.items.[key].watched_date" (Data Jar)
            Set Value [now] for "movies.items.[key].updated_date" (Data Jar)
            Number 1
            Set variable afterRate to [Number]
        End If
    End If

    // ── Шаг 17. Теги ──
    If [act] is "tags"
        Get Value for "tags" in [rec]
        Combine [Dictionary Value] with Custom ", "
        Choose from [tagsList]   (Prompt: "Теги (сейчас: [Combined Text])", Select Multiple: on)
        // один тег ляжет текстом, не списком — это норма (data.md)
        If [Chosen Items] has any value
            Set Value [Chosen Items] for "movies.items.[key].tags" (Data Jar)
        Otherwise
            // сняли все теги
            Delete Value for "movies.items.[key].tags" (Data Jar)
        End If
        Date   (Current Date)
        Format [Date] with ISO 8601
        Set Value [Formatted Date] for "movies.items.[key].updated_date" (Data Jar)
    End If

    // ── Шаг 18. Заметка ──
    If [act] is "note"
        Ask for Text with "Заметка"   (Default Answer: [rec: note])
        Set Value [Provided Input] for "movies.items.[key].note" (Data Jar)
        Date   (Current Date)
        Format [Date] with ISO 8601
        Set Value [Formatted Date] for "movies.items.[key].updated_date" (Data Jar)
    End If

    // ── Шаг 19. Спросить ИИ ──
    If [act] is "ask"
        If [aiOn] is False
            Show notification "ИИ выключен"
        Otherwise
            Ask for Text with "Что спросить?"
            Set variable question to [Provided Input]

            // 19.1 [favorites]: до 10 записей с my_rating ≥ 8
            Nothing
            Set variable favs
            Get Value for "movies.items" (Data Jar)
            Get All Values in [Value]
            Repeat with each item in [Values]
                Count Items in [favs]
                Set variable nFav to [Count]
                Get Value for "my_rating" in [Repeat Item]
                Number [Dictionary Value]
                If All are true: [Number] is greater than or equal to 8, [nFav] is less than 10
                    Text "[Repeat Item: title_en] ([Repeat Item: year]) — [Repeat Item: my_rating] — [Repeat Item: note]"
                    Add [Text] to favs
                End If
            End Repeat
            Combine [favs] with New Lines
            Set variable favorites to [Combined Text]
            Get Value for "genres" in [rec]
            Combine [Dictionary Value] with Custom ", "
            Set variable gText to [Combined Text]

            Use Private Cloud Compute model   (промпт «Спросить ИИ» из README с [rec: title_en], [rec: title_ru], [rec: year], [rec: type], [gText], [rec: description], [favorites], [question]; Output: Text; Follow Up: on)
        End If
    End If

    // ── Шаг 20. Кинопоиск ──
    If [act] is "kp"
        Get Value for "kinopoisk_id" in [rec]
        If [Dictionary Value] has any value
            Text "https://www.kinopoisk.ru/film/[Dictionary Value]/"
            Show web page at [Text]
        Otherwise
            Show notification "Нет id Кинопоиска"
        End If
    End If

    // ── Шаг 21. IMDb ──
    If [act] is "imdb"
        Get Value for "imdb_id" in [rec]
        If [Dictionary Value] has any value
            Text "https://www.imdb.com/title/[Dictionary Value]/"
            Show web page at [Text]
        Otherwise
            Show notification "Нет IMDb id"
        End If
    End If

    // ── Шаг 22. Трейлер ──
    If [act] is "trailer"
        Text "[rec: title_en] [rec: year] trailer"
        URL-encode [Text]
        Text "https://www.youtube.com/results?search_query=[URL Encoded Text]"
        Show web page at [Text]
    End If

    // ── Шаг 23. Постер ──
    If [act] is "poster"
        Get Value for "poster_url" in [rec]
        If [Dictionary Value] has any value
            Get contents of [Dictionary Value]
            Quick Look [Contents of URL]
        Otherwise
            Show notification "Нет постера"
        End If
    End If

    // ── Шаг 24. Поделиться ──
    If [act] is "share"
        Nothing
        Set variable lines
        Text "[rec: title_ru] / [rec: title_en] ([rec: year])"
        Add [Text] to lines
        Nothing
        Set variable parts
        // ratings может не быть — путь "ratings.imdb" тогда падает
        Get Value for "ratings" in [rec]
        Set variable rts to [Dictionary Value]
        If [rts] does not have any value
            Dictionary   (пустой)
            Set variable rts to [Dictionary]
        End If
        Get Value for "imdb" in [rts]
        If [Dictionary Value] has any value
            Text "IMDb [Dictionary Value]"
            Add [Text] to parts
        End If
        Get Value for "kp" in [rts]
        If [Dictionary Value] has any value
            Text "КП [Dictionary Value]"
            Add [Text] to parts
        End If
        If [parts] has any value
            Combine [parts] with Custom " · "
            Add [Combined Text] to lines
        End If
        Get Value for "kinopoisk_id" in [rec]
        If [Dictionary Value] has any value
            Text "https://www.kinopoisk.ru/film/[Dictionary Value]/"
            Add [Text] to lines
        End If
        Get Value for "imdb_id" in [rec]
        If [Dictionary Value] has any value
            Text "https://www.imdb.com/title/[Dictionary Value]/"
            Add [Text] to lines
        End If
        Combine [lines] with New Lines
        Share [Combined Text]
    End If

    // ── Шаг 25. Обновить данные ──
    If [act] is "update"
        Get Value for "kinopoisk_id" in [rec]
        If [Dictionary Value] has any value
            Dictionary
                kinopoisk_id  Number  [rec: kinopoisk_id]
            Set variable lkIn to [Dictionary]
        Otherwise
            // записи с ТВ бывают без title_en → подсказка Lookup по title_ru
            Get Value for "title_en" in [rec]
            Set variable hintEn to [Dictionary Value]
            If [hintEn] does not have any value
                Get Value for "title_ru" in [rec]
                Set variable hintEn to [Dictionary Value]
            End If
            Dictionary
                title_en  Text    [hintEn]
                title_ru  Text    [rec: title_ru]
                year      Number  [rec: year]
                type      Text    [rec: type]
            Set variable lkIn to [Dictionary]
        End If
        Run Shortcut "Movie Lookup" with [lkIn]
        Set variable upd to [Shortcut Result]
        If [upd] has any value
            // cancelled (вручную) автообновление не перезаписывает
            Get Value for "release_status" in [rec]
            Set variable oldRS to [Dictionary Value]
            // только непустые поля, без пользовательских и key
            List
                title_en
                title_ru
                year
                type
                genres
                imdb_id
                kinopoisk_id
                ratings
                length
                age_rating
                premiere_date
                seasons_count
                release_status
                actors
                description
                poster_url
                source
                updated_date
            Repeat with each item in [List]
                Get Value for [Repeat Item] in [upd]
                If [Dictionary Value] has any value
                    Set Value [Dictionary Value] for "movies.items.[key].[Repeat Item]" (Data Jar)
                End If
            End Repeat
            If [oldRS] is "cancelled"
                Text "cancelled"
                Set Value [Text] for "movies.items.[key].release_status" (Data Jar)
            End If

            // 25.1 Сериал: seasons_count + next_episode (+1 запрос poiskkino)
            Get Value for "movies.items.[key]" (Data Jar)
            Set variable frRec to [Value]
            Get Value for "type" in [frRec]
            Set variable frType to [Dictionary Value]
            Get Value for "kinopoisk_id" in [frRec]
            Set variable frKp to [Dictionary Value]
            Number 0
            Set variable isSer to [Number]
            If Any are true: [frType] is "tv_show", [frType] is "animated_series", [frType] is "anime"
                Number 1
                Set variable isSer to [Number]
            End If
            If [isSer] is 1
                If [frKp] has any value
                    Text "https://api.poiskkino.dev/v1.5/season?movieId=[frKp]&limit=50"
                    Get contents of [Text]   (Method: GET, Headers: X-API-KEY = [apiKey])
                    Set variable sr to [Contents of URL]
                    Get Value for "docs" in [sr]
                    Set variable docs to [Dictionary Value]
                    If [docs] has any value
                        // даты сравниваем числом yyyyMMdd
                        Date   (Current Date)
                        Format [Date] with Custom "yyyyMMdd"
                        Number [Formatted Date]
                        Set variable nowN to [Number]
                        Number 0
                        Set variable maxS to [Number]
                        Number 99999999
                        Set variable nextKey to [Number]
                        Number 99999999
                        Set variable seasonKey to [Number]
                        Repeat with each item in [docs]
                            Get Value for "number" in [Repeat Item 2]
                            Number [Dictionary Value]
                            Set variable sn to [Number]
                            Get Value for "airDate" in [Repeat Item 2]
                            If [Dictionary Value] has any value
                                // 2026-08-02T00:00:00.000Z → 2026-08-02 → 20260802
                                Replace "T.*$" with "" in [Dictionary Value]   (Regular Expression: on)
                                Replace "-" with "" in [Updated Text]
                                Number [Updated Text]
                                If [Number] is greater than [nowN]
                                    // будущий сезон — запасной вариант для next_episode
                                    If [Number] is less than [seasonKey]
                                        Set variable seasonKey to [Number]
                                        Text "Сезон [sn] — <airDate без времени>"
                                        Set variable seasonText to [Text]
                                    End If
                                Otherwise
                                    // вышедший — для seasons_count
                                    If [sn] is greater than [maxS]
                                        Set variable maxS to [sn]
                                    End If
                                End If
                            End If
                            Get Value for "episodes" in [Repeat Item 2]
                            If [Dictionary Value] has any value
                                Repeat with each item in [Dictionary Value]
                                    Get Value for "airDate" in [Repeat Item 3]
                                    If [Dictionary Value] has any value
                                        Replace "T.*$" with "" in [Dictionary Value]   (Regular Expression: on)
                                        Replace "-" with "" in [Updated Text]
                                        Number [Updated Text]
                                        If [Number] is greater than [nowN]
                                            If [Number] is less than [nextKey]
                                                Set variable nextKey to [Number]
                                                Get Value for "number" in [Repeat Item 3]
                                                Text "S[sn]E[Dictionary Value] — <airDate без времени>"
                                                Set variable nextText to [Text]
                                            End If
                                        End If
                                    End If
                                End Repeat
                            End If
                        End Repeat
                        // seasons_count — номер последнего вышедшего сезона (> 0); нет — старое не трогаем
                        If [maxS] is greater than 0
                            Set Value [maxS] for "movies.items.[key].seasons_count" (Data Jar)
                        End If
                        // next_episode: серия → иначе сезон → иначе убрать поле
                        If [nextKey] is less than 99999999
                            Set Value [nextText] for "movies.items.[key].next_episode" (Data Jar)
                        Otherwise
                            If [seasonKey] is less than 99999999
                                Set Value [seasonText] for "movies.items.[key].next_episode" (Data Jar)
                            Otherwise
                                Get Value for "next_episode" in [frRec]
                                If [Dictionary Value] has any value
                                    Delete Value for "movies.items.[key].next_episode" (Data Jar)
                                End If
                            End If
                        End If
                    Otherwise
                        // ответ без docs = ошибка API
                        Show notification "poiskkino: [sr: statusCode] [sr: message]"
                    End If
                End If
            End If
            Show notification "Обновлено"
        End If
    End If

    // ── Шаг 26. В календарь ──
    If [act] is "calendar"
        Get Value for "premiere_date" in [rec]
        If [Dictionary Value] has any value
            Get dates from [Dictionary Value]
            Set variable pDate to [Dates]
            If [pDate] is after [Current Date]
                // уже есть событие с таким названием → не дублировать
                Find Calendar Events where Title is "Премьера: [rec: title_ru]"   (Limit: on, 1)
                If [Calendar Events] has any value
                    Show notification "Премьера уже в Календаре"
                Otherwise
                    Add New Event "Премьера: [rec: title_ru]"   (All Day: on, Starts: [pDate], Ends: [pDate], Calendar: по умолчанию)
                End If
            Otherwise
                Show notification "Премьера уже была"
            End If
        Otherwise
            Show notification "Нет даты премьеры"
        End If
    End If

    // ── Шаг 27. Удалить ──
    If [act] is "delete"
        Choose from Menu   (Prompt: "Удалить [rec: title_ru]?")
            • Да
                Delete Value for "movies.items.[key]" (Data Jar)
                // метка удаления в облако (снимок записи — для полной строки)
                Dictionary
                    key      Text     [key]
                    deleted  Boolean  True
                Set "record" to [rec] in [Dictionary]
                Run Shortcut "Movie Sync" with [Dictionary]
                Dictionary
                    action  Text  back
                Stop and output [Dictionary]
            • Нет
        End Menu
    End If

    // ── Шаг 28. Назад ──
    If [act] is "back"
        Dictionary
            action  Text  back
        Stop and output [Dictionary]
    End If

    // ── Шаг 29. Несохранённая: какой статус ──
    If [act] is "add"
        Text "unwatched"
        Set variable saveStatus to [Text]
    End If
    If [act] is "dismiss"
        Text "dismissed"
        Set variable saveStatus to [Text]
    End If
    If [act] is "watched"
        Ask for Number with "Оценка 1–10"
        Set variable r to [Provided Input]
        If Any are true: [r] is less than 1, [r] is greater than 10
            Show notification "1–10"
        Otherwise
            Text "watched"
            Set variable saveStatus to [Text]
        End If
    End If

    // ── Шаг 30. Несохранённая: сохранить ──
    If [saveStatus] has any value

        // 30.1 Ключ: из Lookup или по правилу ai-<slug>-<year>
        Get Value for "key" in [rec]
        Set variable rk to [Dictionary Value]
        If [rk] does not have any value
            Get Value for "title_en" in [rec]
            Set variable src to [Dictionary Value]
            Get Value for "year" in [rec]
            Set variable yrK to [Dictionary Value]
            Match "[a-zA-Z]" in [src]
            If [Matches] has any value
                Change [src] to lowercase
                Replace " " with "-" in [Updated Text]
                Replace "[^a-z0-9-]" with "" in [Updated Text]   (Regular Expression: on)
                Replace "-+" with "-" in [Updated Text]          (Regular Expression: on)
                Replace "^-+|-+$" with "" in [Updated Text]      (Regular Expression: on)
                Text "ai-[Updated Text]"
                Set variable rk to [Text]
            Otherwise
                Date   (Current Date)
                Format [Date] with Custom "yyyyMMddHHmm"
                Text "ai-[Formatted Date]"
                Set variable rk to [Text]
            End If
            If [yrK] has any value
                Text "[rk]-[yrK]"
                Set variable rk to [Text]
            End If
            Set "key" to [rk] in [rec]
            Set variable rec to [Dictionary]
            Text "ai"
            Set "source" to [Text] in [rec]
            Set variable rec to [Dictionary]
        End If

        // 30.2 Пользовательские поля
        Date   (Current Date)
        Format [Date] with ISO 8601
        Set variable now to [Formatted Date]
        Set "added_date" to [now] in [rec]
        Set variable rec to [Dictionary]
        Set "updated_date" to [now] in [rec]
        Set variable rec to [Dictionary]
        Text "recommendation"
        Set "origin" to [Text] in [rec]
        Set variable rec to [Dictionary]
        Set "status" to [saveStatus] in [rec]
        Set variable rec to [Dictionary]
        // tags / note не ставим: пустое значение Set Dictionary Value не принимает
        If [saveStatus] is "watched"
            Set "my_rating" to [r] in [rec]
            Set variable rec to [Dictionary]
            Set "watched_date" to [now] in [rec]
            Set variable rec to [Dictionary]
            Number 1
            Set variable afterRate to [Number]
        End If

        // 30.3 Запись в Data Jar
        Set Value [rec] for "movies.items.[rk]" (Data Jar)
        Set variable key to [rk]
        Text "yes"
        Set variable saved to [Text]

        If [saveStatus] is "dismissed"
            // сразу выходим — отправить до выхода
            Dictionary
                key  Text  [key]
            Run Shortcut "Movie Sync" with [Dictionary]
            Show notification "Больше не предложу"
            Dictionary
                action  Text  back
            Stop and output [Dictionary]
        End If
    End If

    // ── Шаг 31. После оценки ──
    If [afterRate] is 1
        Ask for Text with "Пара слов? (можно пусто)"
        Set variable review to [Provided Input]
        If [review] has any value
            // нет поля в Data Jar → Get Value по пути падает; берём запись целиком
            Get Value for "movies.items.[key]" (Data Jar)
            Get Value for "note" in [Value]
            If [Dictionary Value] has any value
                Text "[Dictionary Value] · [review]"
                Set variable newNote to [Text]
            Otherwise
                Set variable newNote to [review]
            End If
            Set Value [newNote] for "movies.items.[key].note" (Data Jar)
        End If
        If [r] is greater than or equal to 8
            Choose from Menu   (Prompt: "Показать похожие?")
                • Да
                    Number 1
                    Set variable doSimilar to [Number]
                • Нет
            End Menu
        End If
    End If

    // ── Шаг 31a. Была правка в этом обороте → Movie Sync (облако или очередь) ──
    // до «Похожих»: они выходят через Stop and Output
    If Any are true: [act] is "status", [act] is "rstatus", [act] is "rate", [act] is "tags", [act] is "note", [act] is "update", [act] is "notify", [saveStatus] has any value
        Dictionary
            key  Text  [key]
        Run Shortcut "Movie Sync" with [Dictionary]
    End If

    // ── Шаг 32. Похожие ──
    If [doSimilar] is 1
        Nothing
        Set variable items

        // 32.1 similarMovies из poiskkino
        Get Value for "kinopoisk_id" in [rec]
        Set variable simKp to [Dictionary Value]
        If [simKp] has any value
            Text "https://api.poiskkino.dev/v1.5/movie/[simKp]"
            Get contents of [Text]   (Method: GET, Headers: X-API-KEY = [apiKey])
            Set variable sm to [Contents of URL]
            Get Value for "id" in [sm]
            If [Dictionary Value] does not have any value
                Show notification "poiskkino: [sm: statusCode] [sm: message]"
            Otherwise
                Get Value for "similarMovies" in [sm]
                Repeat with each item in [Dictionary Value]
                    Get Value for "type" in [Repeat Item]
                    Get Value for [Dictionary Value] in [typeMap]
                    Set variable simType to [Dictionary Value]
                    If [simType] does not have any value
                        Text "movie"
                        Set variable simType to [Text]
                    End If
                    Dictionary
                        kinopoisk_id  Number  [Repeat Item: id]
                        title_ru      Text    [Repeat Item: name]
                        title_en      Text    [Repeat Item: alternativeName]
                        type          Text    [simType]
                    Set variable lr to [Dictionary]
                    Get Value for "year" in [Repeat Item]
                    If [Dictionary Value] has any value
                        Set "year" to [Dictionary Value] in [lr]
                        Set variable lr to [Dictionary]
                    End If
                    Dictionary   (пустой)
                    Set variable simR to [Dictionary]
                    Get Value for "rating" in [Repeat Item]
                    If [Dictionary Value] has any value
                        Get Value for "kp" in [Dictionary Value]
                        Number [Dictionary Value]
                        If [Number] is greater than 0
                            Round [Number] to Tenths
                            Set "kp" to [Rounded Number] in [simR]
                            Set variable simR to [Dictionary]
                        End If
                    End If
                    If [simR] has any value
                        Set "ratings" to [simR] in [lr]
                        Set variable lr to [Dictionary]
                    End If
                    Add [lr] to items
                End Repeat
            End If
        End If

        // 32.2 Запасной путь — ИИ
        If [items] does not have any value
            If [aiOn] is False
            Otherwise
                Get Value for "genres" in [rec]
                Combine [Dictionary Value] with Custom ", "
                Set variable gText to [Combined Text]
                Use Private Cloud Compute model   (промпт «Похожие» из README с [rec: title_en], [rec: year], [rec: type], [gText], [genreList]; Output: Dictionary)
                Get Value for "similar" in [Response]
                Set variable items to [Dictionary Value]
                If [items] does not have any value
                    Use Private Cloud Compute model   (промпт «Похожие» + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
                    Replace "```json" with "" in [Response]
                    Replace "```" with "" in [Updated Text]
                    Get dictionary from [Updated Text]
                    Get Value for "similar" in [Dictionary]
                    Set variable items to [Dictionary Value]
                    If [items] does not have any value
                        Show notification "ИИ не ответил"
                    End If
                End If
            End If
        End If

        // 32.3 Исключить смотренное / брошенное / скрытое
        Nothing
        Set variable kept
        If [items] has any value
            Nothing
            Set variable seenT
            Nothing
            Set variable seenK
            Get Value for "movies.items" (Data Jar)
            Get All Values in [Value]
            Repeat with each item in [Values]
                If Any are true: [Repeat Item: status] is "watched", [Repeat Item: status] is "dropped", [Repeat Item: status] is "dismissed"
                    Change [Repeat Item: title_en] to lowercase
                    Text "[Updated Text] [Repeat Item: year]"
                    Add [Text] to seenT
                    Get Value for "kinopoisk_id" in [Repeat Item]
                    If [Dictionary Value] has any value
                        Add [Dictionary Value] to seenK
                    End If
                End If
            End Repeat
            Combine [seenT] with Custom "|"
            Text "|[Combined Text]|"
            Set variable seenTxt to [Text]
            Combine [seenK] with Custom "|"
            Text "|[Combined Text]|"
            Set variable seenKTxt to [Text]

            Repeat with each item in [items]
                Number 1
                Set variable keep to [Number]
                Change [Repeat Item: title_en] to lowercase
                Text "|[Updated Text] [Repeat Item: year]|"
                If [seenTxt] contains [Text]
                    Number 0
                    Set variable keep to [Number]
                End If
                Get Value for "kinopoisk_id" in [Repeat Item]
                If [Dictionary Value] has any value
                    Text "|[Dictionary Value]|"
                    If [seenKTxt] contains [Text]
                        Number 0
                        Set variable keep to [Number]
                    End If
                End If
                If [keep] is 1
                    Add [Repeat Item] to kept
                End If
            End Repeat
        End If

        // 32.4 Результат
        If [kept] has any value
            Dictionary
                action  Text  similar
            Set "items" to [kept] in [Dictionary]
            Stop and output [Dictionary]
        Otherwise
            Show notification "Похожих нет"
        End If
        Nothing
        Set variable doSimilar
    End If

// ── Шаг 33 ──
End Repeat

// ── Шаг 34. 50 оборотов прошло ──
Dictionary
    action  Text  back
Stop and output [Dictionary]
```
