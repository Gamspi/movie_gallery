# Movie Seasons — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для списков (`Add to Variable` копит между оборотами Repeat).
- **Условие «A и (B или C)»**: в одном If нельзя смешать All / Any — флаг `ok` = 1, каждое невыполненное условие ставит `ok` = 0.
- **«Входит в набор»**: Text `|a|b|c|` contains `|[x]|` — вместо If с Any на каждое значение.
- **Ротация без сортировки**: подходящие сериалы → Dictionary `scores` (ключ → секунды с `seasons_checked`, нет поля → 999999999), затем [топ без сортировки](../data.md#топ-без-сортировки) N = 30 — первыми проверяются самые давно проверенные; каждый успешный ответ пишет `seasons_checked`, поэтому очередь крутится; лимит 30 запросов соблюдается сам, отдельный `counter` не нужен.
- **Вложенный Repeat**: iOS может назвать плашки `Repeat Item 2` — бери плашку своего Repeat.
- **Число из словаря** (Data Jar, ответ API, `Repeat Item: поле`) приходит без типа — If не даёт сравнений «больше/меньше». Перед сравнением: `Number [x]` → сравнивать `[Number]`.

## Автоматизация

Shortcuts → Automation → **+** → Time of Day: 19:00, Repeat Weekly, Sunday → Run Immediately.

```
Run Shortcut "Movie Seasons"
```

## Шорткат Movie Seasons

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Movie Seasons: нет movies.settings — см. README, Установка"
    Stop this shortcut
End If

// ── Шаг 2. Ключ API ──
Get Value for "poiskkino_api_key" in [settings]
Set variable apiKey to [Dictionary Value]
If [apiKey] does not have any value
    Show notification "Movie Seasons: нет ключа poiskkino"
    Stop this shortcut
End If

// ── Шаг 3. База и счётчики ──
Get Value for "movies.items" (Data Jar)
Set variable items to [Value]
If [items] does not have any value
    Stop this shortcut
End If
Number 0
Set variable okCount to [Number]
Number 0
Set variable errCount to [Number]
Nothing
Set variable lastErr
Date   (Current Date)
Set variable nowDate to [Date]
Format [nowDate] with ISO 8601
Set variable now to [Formatted Date]

// ── Шаг 4. Отбор сериалов → scores (ключ → давность в секундах) ──
Dictionary   (пустой)
Set variable scores to [Dictionary]
Get All Values in [items]
Repeat with each item in [Values]
    Number 1
    Set variable ok to [Number]

    // 4.1 type ∈ {tv_show, animated_series, anime}
    Text "|tv_show|animated_series|anime|"
    If [Text] does not contain "|[Repeat Item: type]|"
        Number 0
        Set variable ok to [Number]
    End If

    // 4.2 status ∈ {watching, watched}
    Text "|watching|watched|"
    If [Text] does not contain "|[Repeat Item: status]|"
        Number 0
        Set variable ok to [Number]
    End If

    // 4.3 kinopoisk_id есть
    Get Value for "kinopoisk_id" in [Repeat Item]
    If [Dictionary Value] does not have any value
        Number 0
        Set variable ok to [Number]
    End If

    // 4.4 completed и seasons_count есть → больше не проверять
    Get Value for "seasons_count" in [Repeat Item]
    If [Dictionary Value] has any value
        Get Value for "release_status" in [Repeat Item]
        If [Dictionary Value] is "completed"
            Number 0
            Set variable ok to [Number]
        End If
    End If

    // 4.5 Давность
    If [ok] is 1
        // по умолчанию — самый давний; дата не распозналась → остаётся 999999999
        Number 999999999
        Set variable age to [Number]
        Get Value for "seasons_checked" in [Repeat Item]
        If [Dictionary Value] has any value
            Get dates from [Dictionary Value]
            Get time between [Dates] and [nowDate] in Seconds
            If [Time Between Dates] has any value
                Set variable age to [Time Between Dates]
            End If
        End If
        Set [Repeat Item: key] to [age] in [scores]
        Set variable scores to [Dictionary]
    End If
End Repeat

// ── Шаг 5. Топ-30 по давности ──
Nothing
Set variable picked
Text "|"
Set variable pickedTxt to [Text]
Get All Keys in [scores]
Set variable scoreKeys to [Keys]
Repeat 30 times
    Number -1
    Set variable best to [Number]
    Nothing
    Set variable bestId
    Repeat with each item in [scoreKeys]
        Get Value for [Repeat Item 2] in [scores]
        Number [Dictionary Value]
        Set variable sc to [Number]
        If [pickedTxt] does not contain "|[Repeat Item 2]|"
            If [sc] is greater than [best]
                Set variable best to [sc]
                Set variable bestId to [Repeat Item 2]
            End If
        End If
    End Repeat
    If [bestId] has any value
        Add [bestId] to picked
        Text "[pickedTxt][bestId]|"
        Set variable pickedTxt to [Text]
    End If
End Repeat

// ── Шаг 6. Проверка сезонов ──
Repeat with each item in [picked]
    Set variable key to [Repeat Item]
    Get Value for [key] in [items]
    Set variable rec to [Dictionary Value]
    Get Value for "seasons_count" in [rec]
    Set variable oldCount to [Dictionary Value]

    // 6.1 Запрос
    Text "https://api.poiskkino.dev/v1.5/season?movieId=[rec: kinopoisk_id]&limit=50&selectFields=number&selectFields=episodesCount"
    Get contents of [Text]   (Method: GET, Headers: X-API-KEY = [apiKey])
    Set variable m to [Contents of URL]

    // 6.2 Ответ
    Get Value for "statusCode" in [m]
    If [Dictionary Value] has any value
        // ошибка API — запись не трогать
        Calculate [errCount] + 1
        Set variable errCount to [Calculation Result]
        Text "[m: statusCode] [m: message]"
        Set variable lastErr to [Text]
    Otherwise
        Get Value for "docs" in [m]
        Set variable docs to [Dictionary Value]
        If [docs] has any value
            Calculate [okCount] + 1
            Set variable okCount to [Calculation Result]
            // Ответ валиден → отметка проверки (ротация), даже без изменений
            Set Value [now] for "movies.items.[key].seasons_checked" (Data Jar)

            // 6.3 Номера сезонов (0 — спецвыпуски)
            Nothing
            Set variable nums
            Repeat with each item in [docs]
                Get Value for "number" in [Repeat Item 2]
                Number [Dictionary Value]
                If [Number] is greater than 0
                    Add [Number] to nums
                End If
            End Repeat

            If [nums] has any value
                Calculate Statistics Maximum of [nums]
                Set variable newCount to [Statistics Result]

                // 6.4 Уведомление (первый запуск — без него)
                If [oldCount] has any value
                    // oldCount не преобразуем при Set variable — нужна проверка «has any value»
                    Number [oldCount]
                    If [newCount] is greater than [Number]
                        Show notification "Новый сезон: [rec: title_ru] — сезон [newCount]"
                    End If
                End If

                // 6.5 Запись (только при изменении — updated_date = «запись изменилась»)
                If [oldCount] does not have any value
                    Set Value [newCount] for "movies.items.[key].seasons_count" (Data Jar)
                    Set Value [now] for "movies.items.[key].updated_date" (Data Jar)
                Otherwise
                    If [newCount] is not [oldCount]
                        Set Value [newCount] for "movies.items.[key].seasons_count" (Data Jar)
                        Set Value [now] for "movies.items.[key].updated_date" (Data Jar)
                    End If
                End If
            End If
        End If
    End If
End Repeat

// ── Шаг 7. Итог: все запросы упали → уведомить ──
If [errCount] is greater than 0
    If [okCount] is 0
        If [lastErr] does not have any value
            Text "нет ответа"
            Set variable lastErr to [Text]
        End If
        Show notification "Movie Seasons — poiskkino: [lastErr]"
    End If
End If
```
