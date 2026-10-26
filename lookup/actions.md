# Movie Lookup — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.
Число из словаря (ответ API, Data Jar) приходит без типа — If не даёт «больше/меньше». Перед сравнением: `Number [x]` → сравнивать `[Number]`.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If

// ── Шаг 2. Ключ API ──
Get Value for "poiskkino_api_key" in [settings]
Set variable apiKey to [Dictionary Value]
If [apiKey] does not have any value
    Show notification "Нет ключа poiskkino в movies.settings"
    Stop this shortcut
End If

// ── Шаг 3. Карта жанров ──
Get Value for "genre_map" in [settings]
Set variable genreMap to [Dictionary Value]

// ── Шаг 4. Список жанров для ИИ ──
Get Value for "genres" in [settings]
Combine [Dictionary Value] with Custom ", "
Set variable genreList to [Combined Text]

// ── Шаг 5. Карта типов ──
Get Value for "type_map" in [settings]
Set variable typeMap to [Dictionary Value]

// ── Шаг 6. Тип входа ──
// Get Value из Text падает: "couldn't convert from Text to Dictionary"
Get Type of [Shortcut Input]
If [Type] is "Text"

    // ── Шаг 7. Вход Text (Add) ──
    Set variable query to [Shortcut Input]

// ── Шаг 8. Вход Dictionary ──
Otherwise
    Get Value for "kinopoisk_id" in [Shortcut Input]
    Set variable kpId to [Dictionary Value]
    Get Value for "title_en" in [Shortcut Input]
    Set variable hintTitle to [Dictionary Value]
    Get Value for "query" in [Shortcut Input]
    Set variable query to [Dictionary Value]
    Get Value for "voice" in [Shortcut Input]
    Set variable voice to [Dictionary Value]

// ── Шаг 9 ──
End If

// ── Шаг 10. Тексты интерфейса ──
If [voice] has any value
    Dictionary
        which      Text  Which one?
        none       Text  None of these
        not_found  Text  Not found. Save as is?
        yes        Text  Yes
        no         Text  No
        year       Text  Which year?
        type       Text  What type?
    Set variable ui to [Dictionary]
    Get Value for "type_labels_en" in [settings]
    Set variable typeLabels to [Dictionary Value]
Otherwise
    Dictionary
        which      Text  Что именно?
        none       Text  Нет в списке
        not_found  Text  Не найдено. Сохранить как есть?
        yes        Text  Да
        no         Text  Нет
        year       Text  Год?
        type       Text  Тип?
    Set variable ui to [Dictionary]
    Get Value for "type_labels" in [settings]
    Set variable typeLabels to [Dictionary Value]
End If

// ── Шаг 11. Ветка A: вход {kinopoisk_id} ──
If [kpId] has any value
    Set variable foundId to [kpId]

// ── Шаг 12 ──
Otherwise

    // ── Шаг 13. Ветка B: подсказка ──
    If [hintTitle] has any value
        Set variable cand to [Shortcut Input]

    // ── Шаг 14. Ветка C: текст ──
    Otherwise

        // ── Шаг 15. Спросить ИИ ──
        Get Value for "ai_enabled" in [settings]
        If [Dictionary Value] is False
        Otherwise
            Use Private Cloud Compute model   (промпт 1 с [query] и [genreList], Output: Dictionary)
            Get Value for "candidates" in [Response]
            Set variable cands to [Dictionary Value]
            If [cands] does not have any value
                Use Private Cloud Compute model   (промпт 1 + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
                Replace "```json" with "" in [Response]
                Replace "```" with "" in [Updated Text]
                Get dictionary from [Updated Text]
                Get Value for "candidates" in [Dictionary]
                Set variable cands to [Dictionary Value]
                If [cands] does not have any value
                    Show notification "ИИ не ответил"
                End If
            End If
        End If

        // ── Шаг 16. Выбор кандидата ──
        If [cands] has any value
            Count Items in [cands]
            Set variable nCands to [Count]
            Get First Item from [cands]
            Get Value for "confidence" in [Item from List]
            Set variable conf1 to [Dictionary Value]
            If All are true: [nCands] is 1, [conf1] is "high"
                Get First Item from [cands]
                Set variable cand to [Item from List]
            Otherwise
                Dictionary   (пустой)
                Set variable labelMap to [Dictionary]
                Repeat with each item in [cands]
                    If [voice] has any value
                        Text "[Repeat Item: title_en] ([Repeat Item: year], [Repeat Item: type])"
                        Set variable label to [Text]
                    Otherwise
                        Text "[Repeat Item: title_ru] / [Repeat Item: title_en] ([Repeat Item: year], [Repeat Item: type])"
                        Set variable label to [Text]
                    End If
                    Add [label] to labels
                    Set [label] to [Repeat Item] in [labelMap]
                    Set variable labelMap to [Dictionary]
                End Repeat
                Get Value for "none" in [ui]
                Set variable noneT to [Dictionary Value]
                Add [noneT] to labels
                Get Value for "which" in [ui]
                Set variable whichT to [Dictionary Value]
                Choose from [labels]   (Prompt: [whichT])
                If [Chosen Item] is not [noneT]
                    Get Value for [Chosen Item] in [labelMap]
                    Set variable cand to [Dictionary Value]
                End If
            End If

        // ── Шаг 17 ──
        End If

    // ── Шаг 18 ──
    End If

    // ── Шаг 19. Список запросов ──
    Get Value for "title_ru" in [cand]
    If [Dictionary Value] has any value
        Add [Dictionary Value] to queries
    End If
    Get Value for "title_en" in [cand]
    If [Dictionary Value] has any value
        Add [Dictionary Value] to queries
    End If
    If [query] has any value
        Add [query] to queries
    End If

    // ── Шаг 20. Поиск в poiskkino ──
    Repeat with each item in [queries]
        If All are true: [docsMatched] does not have any value, [apiErr] is not 1
            URL-encode [Repeat Item]
            Text "https://api.poiskkino.dev/v1.5/movie/search?query=[URL Encoded Text]&limit=5"
            Get contents of [Text]   (Method: GET, Headers: X-API-KEY = [apiKey])
            Set variable resp to [Contents of URL]
            Get Value for "statusCode" in [resp]
            If [Dictionary Value] has any value
                Get Value for "message" in [resp]
                Show notification "poiskkino: [resp: statusCode] [resp: message]"
                Number 1
                Set variable apiErr to [Number]
            Otherwise
                Get Value for "docs" in [resp]
                Repeat with each item in [Dictionary Value]
                    If [cand] has any value
                        Get Value for "type" in [Repeat Item 2]
                        Get Value for [Dictionary Value] in [typeMap]
                        Set variable docType to [Dictionary Value]
                        If [docType] does not have any value
                            Text "movie"
                            Set variable docType to [Text]
                        End If
                        Get Value for "type" in [cand]
                        Set variable candType to [Dictionary Value]
                        If [candType] is "documentary"
                            Text "movie"
                            Set variable candType to [Text]
                        End If
                        Calculate [Repeat Item 2: year] − [cand: year]
                        Set variable dy to [Calculation Result]
                        If All are true: [docType] is [candType], [dy] is between -1 and 1
                            Add [Repeat Item 2] to docsMatched
                        End If
                    Otherwise
                        Add [Repeat Item 2] to docsMatched
                    End If
                End Repeat
            End If
        End If
    End Repeat

    // ── Шаг 21. Выбрать foundId ──
    Count Items in [docsMatched]
    Set variable nDocs to [Count]
    If [nDocs] is 1
        Get First Item from [docsMatched]
        Get Value for "id" in [Item from List]
        Set variable foundId to [Dictionary Value]
    Otherwise
        If [nDocs] is greater than 1
            Dictionary   (пустой)
            Set variable docMap to [Dictionary]
            Repeat with each item in [docsMatched]
                If [voice] has any value
                    Text "[Repeat Item: alternativeName] ([Repeat Item: year], [Repeat Item: type])"
                    Set variable label to [Text]
                Otherwise
                    Text "[Repeat Item: name] / [Repeat Item: alternativeName] ([Repeat Item: year], [Repeat Item: type])"
                    Set variable label to [Text]
                End If
                Add [label] to docLabels
                Set [label] to [Repeat Item: id] in [docMap]
                Set variable docMap to [Dictionary]
            End Repeat
            Get Value for "which" in [ui]
            Choose from [docLabels]   (Prompt: [Dictionary Value])
            Get Value for [Chosen Item] in [docMap]
            Set variable foundId to [Dictionary Value]
        End If
    End If

// ── Шаг 22 ──
End If

// ── Шаг 23. Карточка из poiskkino ──
If [foundId] has any value
    Text "https://api.poiskkino.dev/v1.5/movie/[foundId]"
    Get contents of [Text]   (Method: GET, Headers: X-API-KEY = [apiKey])
    Set variable m to [Contents of URL]
    Get Value for "id" in [m]
    Set variable mId to [Dictionary Value]
    If [mId] does not have any value
        Show notification "poiskkino: [m: statusCode] [m: message]"
        If [kpId] has any value
            Stop this shortcut
        End If
    End If

// ── Шаг 24 ──
End If

// ── Шаг 25. Запись из poiskkino ──
If [mId] has any value

    // 25.1 title_en
    Get Value for "enName" in [m]
    If [Dictionary Value] has any value
        Set variable title_en to [Dictionary Value]
    Otherwise
        Get Value for "alternativeName" in [m]
        Set variable altName to [Dictionary Value]
        Match "^[\p{Latin}\d\s\p{P}\p{S}]+$" in [altName]
        If [Matches] has any value
            Set variable title_en to [altName]
        Otherwise
            Get Value for "title_en" in [cand]
            Set variable title_en to [Dictionary Value]
        End If
    End If

    // 25.2 title_ru
    Get Value for "name" in [m]
    If [Dictionary Value] has any value
        Set variable title_ru to [Dictionary Value]
    Otherwise
        Get Value for "title_ru" in [cand]
        Set variable title_ru to [Dictionary Value]
    End If

    // 25.3 genres
    Dictionary   (пустой)
    Set variable genreSet to [Dictionary]
    Get Value for "genres" in [m]
    Repeat with each item in [Dictionary Value]
        Get Value for "name" in [Repeat Item]
        Get Value for [Dictionary Value] in [genreMap]
        Set variable canon to [Dictionary Value]
        If [canon] has any value
            If [canon] is not "drop"
                Set [canon] to "1" in [genreSet]
                Set variable genreSet to [Dictionary]
            End If
        End If
    End Repeat
    Get All Keys in [genreSet]
    Set variable genres to [Keys]

    // 25.4 type
    Get Value for "type" in [m]
    Get Value for [Dictionary Value] in [typeMap]
    Set variable type to [Dictionary Value]
    If [type] does not have any value
        Text "movie"
        Set variable type to [Text]
    End If
    If [type] is "movie"
        Get Value for "Documentary" in [genreSet]
        If [Dictionary Value] has any value
            Text "documentary"
            Set variable type to [Text]
        End If
    End If

    // 25.5 ratings
    Dictionary   (пустой)
    Set variable ratings to [Dictionary]
    Get Value for "rating" in [m]
    If [Dictionary Value] has any value
        Get Value for "imdb" in [Dictionary Value]
        If [Dictionary Value → Number] is greater than 0
            Round [Dictionary Value → Number] to Tenths
            Set "imdb" to [Rounded Number] in [ratings]
            Set variable ratings to [Dictionary]
        End If
    End If
    Get Value for "rating" in [m]
    If [Dictionary Value] has any value
        Get Value for "kp" in [Dictionary Value]
        If [Dictionary Value → Number] is greater than 0
            Round [Dictionary Value → Number] to Tenths
            Set "kp" to [Rounded Number] in [ratings]
            Set variable ratings to [Dictionary]
        End If
    End If

    // 25.6 length
    Get Value for "seriesLength" in [m]
    If [Dictionary Value → Number] is greater than 0
        Set variable length to [Dictionary Value → Number]
    Otherwise
        Get Value for "movieLength" in [m]
        If [Dictionary Value → Number] is greater than 0
            Set variable length to [Dictionary Value → Number]
        End If
    End If

    // 25.7 premiere_date
    Get Value for "premiere" in [m]
    If [Dictionary Value] has any value
        Get Value for "world" in [Dictionary Value]
        Set variable pd to [Dictionary Value]
    End If
    If [pd] does not have any value
        Get Value for "premiere" in [m]
        If [Dictionary Value] has any value
            Get Value for "russia" in [Dictionary Value]
            Set variable pd to [Dictionary Value]
        End If
    End If
    If [pd] has any value
        Get dates from [pd]
        Format [Dates] with Custom "yyyy-MM-dd"
        Set variable premiere_date to [Formatted Date]
    End If

    // 25.8 seasons_count
    Get Value for "seasonsInfo" in [m]
    If [Dictionary Value] has any value
        Count Items in [Dictionary Value]
        Set variable seasons_count to [Count]
    End If

    // 25.9 description
    Get Value for "shortDescription" in [m]
    If [Dictionary Value] has any value
        Set variable description to [Dictionary Value]
    Otherwise
        Get Value for "description" in [m]
        If [Dictionary Value] has any value
            Split [Dictionary Value] by Custom ". "
            Get First Item from [Split Text]
            Text "[Item from List]."
            Set variable description to [Text]
        End If
    End If

    // 25.10 key
    Get Value for "externalId" in [m]
    If [Dictionary Value] has any value
        Get Value for "imdb" in [Dictionary Value]
        If [Dictionary Value] has any value
            Set variable imdb_id to [Dictionary Value]
            Set variable key to [Dictionary Value]
        Otherwise
            Text "kp-[mId]"
            Set variable key to [Text]
        End If
    Otherwise
        Text "kp-[mId]"
        Set variable key to [Text]
    End If

    // 25.11 запись
    Date   (Current Date)
    Format [Date] with ISO 8601
    Set variable now to [Formatted Date]
    Dictionary
        key           Text    [key]
        title_en      Text    [title_en]
        title_ru      Text    [title_ru]
        year          Number  [m: year]
        type          Text    [type]
        kinopoisk_id  Number  [mId]
        source        Text    poiskkino
        updated_date  Text    [now]
    Set variable rec to [Dictionary]
    If [genres] has any value
        Set "genres" to [genres] in [rec]
        Set variable rec to [Dictionary]
    End If
    If [ratings] has any value
        Set "ratings" to [ratings] in [rec]
        Set variable rec to [Dictionary]
    End If

    // 25.12 необязательные поля
    If [imdb_id] has any value
        Set "imdb_id" to [imdb_id] in [rec]
        Set variable rec to [Dictionary]
    End If
    If [length] has any value
        Set "length" to [length] in [rec]
        Set variable rec to [Dictionary]
    End If
    If [premiere_date] has any value
        Set "premiere_date" to [premiere_date] in [rec]
        Set variable rec to [Dictionary]
    End If
    If [seasons_count] has any value
        Set "seasons_count" to [seasons_count] in [rec]
        Set variable rec to [Dictionary]
    End If
    If [description] has any value
        Set "description" to [description] in [rec]
        Set variable rec to [Dictionary]
    End If
    Get Value for "ageRating" in [m]
    If [Dictionary Value] has any value
        Set "age_rating" to [Dictionary Value] in [rec]
        Set variable rec to [Dictionary]
    End If
    Get Value for "status" in [m]
    If [Dictionary Value] has any value
        Set "release_status" to [Dictionary Value] in [rec]
        Set variable rec to [Dictionary]
    End If
    Get Value for "poster" in [m]
    If [Dictionary Value] has any value
        Get Value for "url" in [Dictionary Value]
        If [Dictionary Value] has any value
            Set "poster_url" to [Dictionary Value] in [rec]
            Set variable rec to [Dictionary]
        End If
    End If

// ── Шаг 26. Ветка D: не найдено ──
Otherwise
    Date   (Current Date)
    Format [Date] with ISO 8601
    Set variable now to [Formatted Date]

    // 26.1 Есть кандидат от ИИ
    If [cand] has any value
        Get Value for "title_en" in [cand]
        Set variable src to [Dictionary Value]
        Get Value for "year" in [cand]
        Set variable yrK to [Dictionary Value]
        Match "[a-zA-Z]" in [src]
        If [Matches] has any value
            Change [src] to lowercase
            Replace " " with "-" in [Updated Text]
            Replace "[^a-z0-9-]" with "" in [Updated Text]   (Regular Expression: on)
            Replace "-+" with "-" in [Updated Text]          (Regular Expression: on)
            Replace "^-+|-+$" with "" in [Updated Text]      (Regular Expression: on)
            Text "ai-[Updated Text]"
            Set variable key to [Text]
        Otherwise
            Date   (Current Date)
            Format [Date] with Custom "yyyyMMddHHmm"
            Text "ai-[Formatted Date]"
            Set variable key to [Text]
        End If
        If [yrK] has any value
            Text "[key]-[yrK]"
            Set variable key to [Text]
        End If
        Dictionary
            key           Text    [key]
            title_en      Text    [cand: title_en]
            title_ru      Text    [cand: title_ru]
            year          Number  [cand: year]
            type          Text    [cand: type]
            source        Text    ai
            updated_date  Text    [now]
        Set variable rec to [Dictionary]
        Get Value for "genres" in [cand]
        If [Dictionary Value] has any value
            Set "genres" to [Dictionary Value] in [rec]
            Set variable rec to [Dictionary]
        End If

    // 26.2 Нет кандидата — «Сохранить как есть?»
    Otherwise
        Get Value for "yes" in [ui]
        Set variable yesT to [Dictionary Value]
        Get Value for "no" in [ui]
        Set variable noT to [Dictionary Value]
        Get Value for "not_found" in [ui]
        Set variable askT to [Dictionary Value]
        List
            [yesT]
            [noT]
        Choose from [List]   (Prompt: [askT])
        If [Chosen Item] is [noT]
            Stop this shortcut
        End If
        Get Value for "year" in [ui]
        Ask for Number with [Dictionary Value]
        Set variable yr to [Provided Input]
        Get Value for "type" in [ui]
        Set variable typeT to [Dictionary Value]
        Get All Values in [typeLabels]
        Choose from [Values]   (Prompt: [typeT])
        Set variable picked to [Chosen Item]
        Get All Keys in [typeLabels]
        Repeat with each item in [Keys]
            Get Value for [Repeat Item] in [typeLabels]
            If [Dictionary Value] is [picked]
                Set variable type to [Repeat Item]
            End If
        End Repeat
        Set variable src to [query]
        Set variable yrK to [yr]
        Match "[a-zA-Z]" in [src]
        If [Matches] has any value
            Change [src] to lowercase
            Replace " " with "-" in [Updated Text]
            Replace "[^a-z0-9-]" with "" in [Updated Text]   (Regular Expression: on)
            Replace "-+" with "-" in [Updated Text]          (Regular Expression: on)
            Replace "^-+|-+$" with "" in [Updated Text]      (Regular Expression: on)
            Text "ai-[Updated Text]"
            Set variable key to [Text]
        Otherwise
            Date   (Current Date)
            Format [Date] with Custom "yyyyMMddHHmm"
            Text "ai-[Formatted Date]"
            Set variable key to [Text]
        End If
        If [yrK] has any value
            Text "[key]-[yrK]"
            Set variable key to [Text]
        End If
        Dictionary
            key           Text    [key]
            title_en      Text    [query]
            title_ru      Text    [query]
            type          Text    [type]
            source        Text    ai
            updated_date  Text    [now]
        Set variable rec to [Dictionary]
        If [yr] has any value
            Set "year" to [yr] in [rec]
            Set variable rec to [Dictionary]
        End If
        // genres / ratings не ставим: пустое значение Set Dictionary Value не принимает

    // закрыл cand
    End If

// ── Шаг 27. Закрыл mId ──
End If

// ── Шаг 28 ──
Stop and output [rec]
```
