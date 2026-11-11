# Movie Add — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat / Menu.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для списков (`Add to Variable` копит между оборотами Repeat) и флагов в начале каждого оборота.
- **Выход из Repeat**: break в Shortcuts нет — тело `Repeat 5` обёрнуто в `If [ok] is not 1`, после «Да, это он» остальные обороты пустые.
- **Число из словаря** (Data Jar, ответ API, `Repeat Item: поле`) приходит без типа — If не даёт сравнений «больше/меньше». Перед сравнением: `Number [x]` → сравнивать `[Number]`.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If
// ключ poiskkino проверяет Movie Lookup — Add в API не ходит
// синхронизация раз в день (Movie Sync сам решает, нужен ли запрос)
Text "auto"
Run Shortcut "Movie Sync" with [Text]

// ── Шаг 2. Теги ──
Get Value for "tags" in [settings]
Set variable tagsList to [Dictionary Value]

// ── Шаг 3. Метки типов ──
Get Value for "type_labels" in [settings]
Set variable typeLabels to [Dictionary Value]

// ── Шаг 4. Запрос ──
// Share Sheet: в настройках шортката Receive Text/URLs
If [Shortcut Input] has any value
    Set variable query to [Shortcut Input]
Otherwise
    Ask for Text with "Что добавить?"
    Set variable query to [Provided Input]
End If

// ── Шаг 5. Поиск с превью (до 5 попыток) ──
Repeat 5 times
    If [ok] is not 1

        // 5.1 Lookup
        Run Shortcut "Movie Lookup" with [query]
        If [Shortcut Result] does not have any value
            Stop this shortcut
        End If
        Set variable rec to [Shortcut Result]

        // 5.2 Превью
        Nothing
        Set variable lines
        Text "[rec: title_ru] / [rec: title_en]"
        Add [Text] to lines

        Nothing
        Set variable parts
        Get Value for "year" in [rec]
        If [Dictionary Value] has any value
            Add [Dictionary Value] to parts
        End If
        Get Value for "type" in [rec]
        Get Value for [Dictionary Value] in [typeLabels]
        If [Dictionary Value] has any value
            Add [Dictionary Value] to parts
        End If
        Get Value for "length" in [rec]
        If [Dictionary Value] has any value
            Text "[Dictionary Value] мин"
            Add [Text] to parts
        End If
        If [parts] has any value
            Combine [parts] with Custom " · "
            Add [Combined Text] to lines
        End If

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

        // actors — Array of Text; один актёр может лежать Text (data.md)
        Get Value for "actors" in [rec]
        If [Dictionary Value] has any value
            Get Items in Range 1–3 from [Dictionary Value]
            Combine [Items from List] with Custom ", "
            Text "В ролях: [Combined Text]"
            Add [Text] to lines
        End If

        Get Value for "description" in [rec]
        If [Dictionary Value] has any value
            Add [Dictionary Value] to lines
        End If

        Get Value for "source" in [rec]
        If [Dictionary Value] is "ai"
            Text "(нет в poiskkino)"
            Add [Text] to lines
        End If

        Combine [lines] with New Lines
        Set variable preview to [Combined Text]

        // 5.3 Это он?
        // «Постер» / «Трейлер» после показа возвращают в то же меню: внутренний Repeat 10 + флаг pvDone
        // (выход — «Да» / «Нет» ставят pvDone = 1; «Отмена» — Stop this shortcut)
        Number 0
        Set variable pvDone to [Number]
        Repeat 10 times
            If [pvDone] is 0
                Choose from Menu   (Prompt: [preview])
                    • Да, это он
                        Number 1
                        Set variable ok to [Number]
                        Number 1
                        Set variable pvDone to [Number]
                    • Нет, искать другой
                        Ask for Text with "Уточни запрос (год, актёр, сериал/фильм)"   (Default Answer: [query])
                        Set variable query to [Provided Input]
                        Number 1
                        Set variable pvDone to [Number]
                    • Постер
                        Get Value for "poster_url" in [rec]
                        If [Dictionary Value] has any value
                            Get contents of [Dictionary Value]
                            Quick Look [Contents of URL]
                        Otherwise
                            Show notification "Нет постера"
                        End If
                    • Трейлер
                        // title_en, иначе title_ru (формат — data.md, «Ссылки карточки»)
                        Get Value for "title_en" in [rec]
                        If [Dictionary Value] has any value
                            Set variable tName to [Dictionary Value]
                        Otherwise
                            Get Value for "title_ru" in [rec]
                            Set variable tName to [Dictionary Value]
                        End If
                        Text "[tName] [rec: year] trailer"
                        URL-encode [Text]
                        Text "https://www.youtube.com/results?search_query=[URL Encoded Text]"
                        Show web page at [Text]
                    • Отмена
                        Stop this shortcut
                End Menu
            End If
        End Repeat
    End If
End Repeat

// ── Шаг 6. 5 попыток без «Да» ──
If [ok] is not 1
    Stop this shortcut
End If

// ── Шаг 7. Ключ ──
Get Value for "key" in [rec]
Set variable key to [Dictionary Value]

// ── Шаг 8. Дубль по ключу ──
Check if Value Exists for "movies.items.[key]" (Data Jar)
If [Value Exists] is True
    Set variable dupKey to [key]
End If

// ── Шаг 9. Дубль по названию + году ──
If [dupKey] does not have any value
    Get Value for "title_en" in [rec]
    If [Dictionary Value] has any value
        Change [Dictionary Value] to lowercase
        Text "[Updated Text]|[rec: year]"
        Set variable recSig to [Text]
        Get Value for "movies.items" (Data Jar)
        Get All Values in [Value]
        Repeat with each item in [Values]
            Change [Repeat Item: title_en] to lowercase
            Text "[Updated Text]|[Repeat Item: year]"
            If [Text] is [recSig]
                Get Value for "key" in [Repeat Item]
                Set variable dupKey to [Dictionary Value]
            End If
        End Repeat
    End If
End If

// ── Шаг 9a. Дубль по русскому названию + году (записи с ТВ: ключ tmdb-…, без title_en) ──
If [dupKey] does not have any value
    Get Value for "title_ru" in [rec]
    If [Dictionary Value] has any value
        Change [Dictionary Value] to lowercase
        Text "[Updated Text]|[rec: year]"
        Set variable recSigRu to [Text]
        Get Value for "movies.items" (Data Jar)
        Get All Values in [Value]
        Repeat with each item in [Values]
            Change [Repeat Item: title_ru] to lowercase
            Text "[Updated Text]|[Repeat Item: year]"
            If [Text] is [recSigRu]
                Get Value for "key" in [Repeat Item]
                Set variable dupKey to [Dictionary Value]
            End If
        End Repeat
    End If
End If

// ── Шаг 10. Уже в списке → карточка ──
If [dupKey] has any value
    Get Value for "movies.items.[dupKey].title_ru" (Data Jar)
    Show notification "Уже в списке: [Value]"
    Dictionary
        key  Text  [dupKey]
    Run Shortcut "Movie Card" with [Dictionary]
    Set variable res to [Shortcut Result]

    // 10.1 Карточка вернула похожие
    Get Value for "action" in [res]
    If [Dictionary Value] is "similar"
        Dictionary   (пустой)
        Set variable simMap to [Dictionary]
        Get Value for "items" in [res]
        Repeat with each item in [Dictionary Value]
            Text "[Repeat Item: title_ru] / [Repeat Item: title_en] ([Repeat Item: year])"
            Set variable label to [Text]
            // ratings может не быть — путь "ratings.imdb" тогда падает
            Get Value for "ratings" in [Repeat Item]
            Set variable rts to [Dictionary Value]
            If [rts] does not have any value
                Dictionary   (пустой)
                Set variable rts to [Dictionary]
            End If
            Get Value for "imdb" in [rts]
            If [Dictionary Value] has any value
                Text "[label] — IMDb [Dictionary Value]"
                Set variable label to [Text]
            Otherwise
                Get Value for "kp" in [rts]
                If [Dictionary Value] has any value
                    Text "[label] — КП [Dictionary Value]"
                    Set variable label to [Text]
                End If
            End If
            Add [label] to simLabels
            Set [label] to [Repeat Item] in [simMap]
            Set variable simMap to [Dictionary]
        End Repeat
        Text "← Назад"
        Add [Text] to simLabels
        // «Назад» из карточки похожего → снова список; выход — «← Назад» в списке
        Number 0
        Set variable simDone to [Number]
        Repeat 20 times
            If [simDone] is 0
                Choose from [simLabels]   (Prompt: "Похожие")
                If [Chosen Item] is "← Назад"
                    Number 1
                    Set variable simDone to [Number]
                Otherwise
                    Get Value for [Chosen Item] in [simMap]
                    Set variable simItem to [Dictionary Value]
                    Dictionary
                        saved  Boolean  False
                    Set "record" to [simItem] in [Dictionary]
                    Run Shortcut "Movie Card" with [Dictionary]
                End If
            End If
        End Repeat
    End If
    Stop this shortcut
End If

// ── Шаг 11. Уже смотрел? ──
Date   (Current Date)
Format [Date] with ISO 8601
Set variable now to [Formatted Date]
// премьера впереди → future = 1, меню «Уже смотрел?» не показывать
// pDate задан только при future = 1 — шаг 15 читает его только внутри If [future] is 1
Get Value for "premiere_date" in [rec]
If [Dictionary Value] has any value
    Get dates from [Dictionary Value]
    Set variable pDate to [Dates]
    If [pDate] is after [Current Date]
        Number 1
        Set variable future to [Number]
    End If
End If
If [future] is 1
    Text "unwatched"
    Set "status" to [Text] in [rec]
    Set variable rec to [Dictionary]
    Text "Не смотрел"
    Set variable statusT to [Text]
Otherwise
    Choose from Menu   (Prompt: "Уже смотрел?")
        • Нет
            Text "unwatched"
            Set "status" to [Text] in [rec]
            Set variable rec to [Dictionary]
            Text "Не смотрел"
            Set variable statusT to [Text]
        • Смотрю
            Text "watching"
            Set "status" to [Text] in [rec]
            Set variable rec to [Dictionary]
            Text "Смотрю"
            Set variable statusT to [Text]
        • Смотрел
            Text "watched"
            Set "status" to [Text] in [rec]
            Set variable rec to [Dictionary]
            Text "Смотрел"
            Set variable statusT to [Text]
            Ask for Number with "Оценка 1–10"
            Set variable r to [Provided Input]
            If All are true: [r] is greater than or equal to 1, [r] is less than or equal to 10
                Set variable myR to [r]
                Set "my_rating" to [myR] in [rec]
                Set variable rec to [Dictionary]
            End If
            Set "watched_date" to [now] in [rec]
            Set variable rec to [Dictionary]
            Ask for Text with "Пара слов? (можно пусто)"
            Set variable noteT to [Provided Input]
    End Menu
End If

// ── Шаг 12. Теги ──
// Cancel в Choose from List прерывает шорткат — поэтому сначала меню
Choose from Menu   (Prompt: "Теги?")
    • Добавить
        Choose from [tagsList]   (Prompt: "Теги", Select Multiple: on)
        // один тег ляжет текстом, не списком — это норма (data.md)
        If [Chosen Items] has any value
            Set "tags" to [Chosen Items] in [rec]
            Set variable rec to [Dictionary]
        End If
    • Пропустить
        // ничего: нет тегов = нет поля tags (пустой List в Set Dictionary Value не передать)
End Menu

// ── Шаг 13. Пользовательские поля ──
Text "manual"
Set "origin" to [Text] in [rec]
Set variable rec to [Dictionary]
Set "added_date" to [now] in [rec]
Set variable rec to [Dictionary]
Set "updated_date" to [now] in [rec]
Set variable rec to [Dictionary]
If [noteT] has any value
    Set "note" to [noteT] in [rec]
    Set variable rec to [Dictionary]
End If

// ── Шаг 14. Запись в Data Jar ──
Set Value [rec] for "movies.items.[key]" (Data Jar)
// правка → облако (без сети — в очередь)
Dictionary
    key  Text  [key]
Run Shortcut "Movie Sync" with [Dictionary]

// ── Шаг 15. Премьера в календарь ──
// future и pDate — из шага 11
If [future] is 1
    // уже есть событие с таким названием → не дублировать
    Find Calendar Events where Title is "Премьера: [rec: title_ru]"   (Limit: on, 1)
    If [Calendar Events] has any value
        Show notification "Премьера уже в Календаре"
    Otherwise
        Choose from Menu   (Prompt: "Добавить премьеру в Календарь?")
            • Да
                Add New Event "Премьера: [rec: title_ru]"   (All Day: on, Starts: [pDate], Ends: [pDate], Calendar: по умолчанию)
            • Нет
        End Menu
    End If
End If

// ── Шаг 16. Готово ──
Get Value for "year" in [rec]
If [Dictionary Value] has any value
    Show notification "Добавлено: [rec: title_ru] ([Dictionary Value]) · [statusT]"
Otherwise
    Show notification "Добавлено: [rec: title_ru] · [statusT]"
End If

// ── Шаг 17. Оценка ≥ 8 → похожие ──
// myR пуст, если не «Смотрел» или оценка вне 1–10 → условие не сработает
If [myR] is greater than or equal to 8
    Choose from Menu   (Prompt: "Показать похожие?")
        • Да
            Dictionary
                key   Text  [key]
                auto  Text  similar
            Run Shortcut "Movie Card" with [Dictionary]
            Set variable res to [Shortcut Result]

            // 17.1 Список похожих
            Get Value for "action" in [res]
            If [Dictionary Value] is "similar"
                Nothing
                Set variable simLabels
                Dictionary   (пустой)
                Set variable simMap to [Dictionary]
                Get Value for "items" in [res]
                Repeat with each item in [Dictionary Value]
                    Text "[Repeat Item: title_ru] / [Repeat Item: title_en] ([Repeat Item: year])"
                    Set variable label to [Text]
                    // ratings может не быть — путь "ratings.imdb" тогда падает
                    Get Value for "ratings" in [Repeat Item]
                    Set variable rts to [Dictionary Value]
                    If [rts] does not have any value
                        Dictionary   (пустой)
                        Set variable rts to [Dictionary]
                    End If
                    Get Value for "imdb" in [rts]
                    If [Dictionary Value] has any value
                        Text "[label] — IMDb [Dictionary Value]"
                        Set variable label to [Text]
                    Otherwise
                        Get Value for "kp" in [rts]
                        If [Dictionary Value] has any value
                            Text "[label] — КП [Dictionary Value]"
                            Set variable label to [Text]
                        End If
                    End If
                    Add [label] to simLabels
                    Set [label] to [Repeat Item] in [simMap]
                    Set variable simMap to [Dictionary]
                End Repeat
                Text "← Назад"
                Add [Text] to simLabels
                // «Назад» из карточки похожего → снова список; выход — «← Назад» в списке
                Number 0
                Set variable simDone to [Number]
                Repeat 20 times
                    If [simDone] is 0
                        Choose from [simLabels]   (Prompt: "Похожие")
                        If [Chosen Item] is "← Назад"
                            Number 1
                            Set variable simDone to [Number]
                        Otherwise
                            Get Value for [Chosen Item] in [simMap]
                            Set variable simItem to [Dictionary Value]
                            Dictionary
                                saved  Boolean  False
                            Set "record" to [simItem] in [Dictionary]
                            Run Shortcut "Movie Card" with [Dictionary]
                        End If
                    End If
                End Repeat
            End If
        • Нет
    End Menu
End If
```
