# Movie Stats — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat / Menu.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
`↵` в `Text "…"` — перенос строки (Return) внутри поля Text.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для списков (`Add to Variable` копит между оборотами Repeat) и для `bestId` в начале каждого оборота топа.
- **Счётчик в словаре** (`d[x] += 1`): Get Value `x` из `d` → пусто = 0 → Calculate +1 → Set Dictionary Value → Set variable `d` (Set Dictionary Value не меняет переменную сам).
- **Топ без сортировки** ([data.md](../data.md#топ-без-сортировки)): уже выбранные id копятся в тексте `pickedTxt` = `|a|b|`; проверка «не выбран» — `pickedTxt` does not contain `|id|`.
- **Меню → `act`**: пункт меню только ставит `act`, действие — ниже отдельным If.
- **Число из словаря** (Data Jar, ответ API, `Repeat Item: поле`) приходит без типа — If не даёт сравнений «больше/меньше». Перед сравнением: `Number [x]` → сравнивать `[Number]`.
- **Вложенный Repeat**: iOS может назвать плашки `Repeat Item 2` / `Repeat Index 2` — бери плашку своего Repeat. Поэтому запись внешнего цикла сразу кладётся в `it`.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If
// ключ poiskkino не нужен — Stats в API не ходит

// ── Шаг 2. Сглаживание k ──
Get Value for "weights.smoothing_k" in [settings]
Set variable k to [Dictionary Value]
If [k] does not have any value
    Number 3
    Set variable k to [Number]
End If

// ── Шаг 3. ИИ включён? ──
Get Value for "ai_enabled" in [settings]
Set variable aiOn to [Dictionary Value]
// не задан → пусто → проверка «is False» не сработает = ИИ включён

// ── Шаг 4. Метки типов и статусов ──
Get Value for "type_labels" in [settings]
Set variable typeLabels to [Dictionary Value]
Dictionary
    unwatched  Text  Не смотрел
    watching   Text  Смотрю
    watched    Text  Смотрел
    dropped    Text  Бросил
    dismissed  Text  Не предлагать
Set variable statusLabels to [Dictionary]

// ── Шаг 5. База ──
Get Value for "movies.items" (Data Jar)
Set variable all to [Value]
If [all] does not have any value
    Show notification "База пуста"
    Stop this shortcut
End If
Get All Values in [all]
Set variable db to [Values]
Count Items in [db]
Set variable total to [Count]

// ── Шаг 6. Пустые счётчики ──
Dictionary   (пустой)
Set variable byStatus to [Dictionary]
Dictionary   (пустой)
Set variable byType to [Dictionary]
Dictionary   (пустой)
Set variable byYear to [Dictionary]
Dictionary   (пустой)
Set variable gSum to [Dictionary]
Dictionary   (пустой)
Set variable gCnt to [Dictionary]
Dictionary   (пустой)
Set variable rScore to [Dictionary]
Nothing
Set variable ratings

// ── Шаг 7. Проход по базе ──
Repeat with each item in [db]
    Set variable it to [Repeat Item]

    // 7.1 byStatus[status] += 1
    Get Value for "status" in [it]
    Set variable st to [Dictionary Value]
    If [st] has any value
        Get Value for [st] in [byStatus]
        Set variable cur to [Dictionary Value]
        If [cur] does not have any value
            Number 0
            Set variable cur to [Number]
        End If
        Calculate [cur] + 1
        Set [st] to [Calculation Result] in [byStatus]
        Set variable byStatus to [Dictionary]
    End If

    // 7.2 byType[type] += 1
    Get Value for "type" in [it]
    Set variable ty to [Dictionary Value]
    If [ty] has any value
        Get Value for [ty] in [byType]
        Set variable cur to [Dictionary Value]
        If [cur] does not have any value
            Number 0
            Set variable cur to [Number]
        End If
        Calculate [cur] + 1
        Set [ty] to [Calculation Result] in [byType]
        Set variable byType to [Dictionary]
    End If

    // 7.3 byYear[год watched_date] += 1
    Get Value for "watched_date" in [it]
    If [Dictionary Value] has any value
        Get dates from [Dictionary Value]
        Format [Dates] with Custom "yyyy"
        Set variable yr to [Formatted Date]
        Get Value for [yr] in [byYear]
        Set variable cur to [Dictionary Value]
        If [cur] does not have any value
            Number 0
            Set variable cur to [Number]
        End If
        Calculate [cur] + 1
        Set [yr] to [Calculation Result] in [byYear]
        Set variable byYear to [Dictionary]
    End If

    // 7.4 Оценка: ratings, rScore, жанры
    Get Value for "my_rating" in [it]
    Set variable mr to [Dictionary Value]
    If [mr] has any value
        Add [mr] to ratings
        Set [it: key] to [mr] in [rScore]
        Set variable rScore to [Dictionary]
        Get Value for "genres" in [it]
        Repeat with each item in [Dictionary Value]
            Get Value for [Repeat Item] in [gSum]
            Set variable cur to [Dictionary Value]
            If [cur] does not have any value
                Number 0
                Set variable cur to [Number]
            End If
            Calculate [cur] + [mr]
            Set [Repeat Item] to [Calculation Result] in [gSum]
            Set variable gSum to [Dictionary]
            Get Value for [Repeat Item] in [gCnt]
            Set variable cur to [Dictionary Value]
            If [cur] does not have any value
                Number 0
                Set variable cur to [Number]
            End If
            Calculate [cur] + 1
            Set [Repeat Item] to [Calculation Result] in [gCnt]
            Set variable gCnt to [Dictionary]
        End Repeat
    End If
End Repeat

// ── Шаг 8. Строка статусов (порядок как в отчёте) ──
Nothing
Set variable stParts
List
    watched
    watching
    unwatched
    dropped
    dismissed
Repeat with each item in [List]
    Get Value for [Repeat Item] in [byStatus]
    Set variable cnt to [Dictionary Value]
    If [cnt] has any value
        Get Value for [Repeat Item] in [statusLabels]
        Text "[Dictionary Value] [cnt]"
        Add [Text] to stParts
    End If
End Repeat
Combine [stParts] with Custom " · "
Set variable stTxt to [Combined Text]

// ── Шаг 9. Строка типов ──
Nothing
Set variable tyParts
Get All Keys in [byType]
Repeat with each item in [Keys]
    Get Value for [Repeat Item] in [byType]
    Set variable cnt to [Dictionary Value]
    Get Value for [Repeat Item] in [typeLabels]
    Set variable lbl to [Dictionary Value]
    If [lbl] does not have any value
        Set variable lbl to [Repeat Item]
    End If
    Text "[lbl] [cnt]"
    Add [Text] to tyParts
End Repeat
Combine [tyParts] with Custom " · "
Set variable tyTxt to [Combined Text]

// ── Шаг 10. Оценки: n, mean ──
Count Items in [ratings]
Set variable n to [Count]
Nothing
Set variable genreLines
Nothing
Set variable topLines
If [n] is greater than 0
    Calculate Average of [ratings]   (Calculate Statistics)
    Set variable mean to [Result]
    Round [mean] to Tenths
    Set variable meanR to [Rounded Number]

    // ── Шаг 11. genre_score = (gSum + k·mean) / (gCnt + k) ──
    Dictionary   (пустой)
    Set variable gScore to [Dictionary]
    Calculate [k] × [mean]
    Set variable kMean to [Calculation Result]
    Get All Keys in [gCnt]
    Repeat with each item in [Keys]
        Get Value for [Repeat Item] in [gSum]
        Calculate [Dictionary Value] + [kMean]
        Set variable num to [Calculation Result]
        Get Value for [Repeat Item] in [gCnt]
        Calculate [Dictionary Value] + [k]
        Set variable den to [Calculation Result]
        Calculate [num] ÷ [den]
        Set [Repeat Item] to [Calculation Result] in [gScore]
        Set variable gScore to [Dictionary]
    End Repeat

    // ── Шаг 12. Любимые жанры: топ-5 по gScore ──
    Text "|"
    Set variable pickedTxt to [Text]
    Get All Keys in [gScore]
    Set variable gKeys to [Keys]
    Repeat 5 times
        Number -1
        Set variable best to [Number]
        Nothing
        Set variable bestId
        Repeat with each item in [gKeys]
            Get Value for [Repeat Item] in [gScore]
            Number [Dictionary Value]
            Set variable v to [Number]
            Text "|[Repeat Item]|"
            If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                Set variable best to [v]
                Set variable bestId to [Repeat Item]
            End If
        End Repeat
        If [bestId] has any value
            Text "[pickedTxt][bestId]|"
            Set variable pickedTxt to [Text]
            Round [best] to Tenths
            Set variable bestR to [Rounded Number]
            Get Value for [bestId] in [gCnt]
            Text "[bestId] — [bestR] ([Dictionary Value])"
            Add [Text] to genreLines
        End If
    End Repeat

    // ── Шаг 13. Топ-5 по my_rating ──
    Text "|"
    Set variable pickedTxt to [Text]
    Get All Keys in [rScore]
    Set variable rKeys to [Keys]
    Repeat 5 times
        Number -1
        Set variable best to [Number]
        Nothing
        Set variable bestId
        Repeat with each item in [rKeys]
            Get Value for [Repeat Item] in [rScore]
            Number [Dictionary Value]
            Set variable v to [Number]
            Text "|[Repeat Item]|"
            If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                Set variable best to [v]
                Set variable bestId to [Repeat Item]
            End If
        End Repeat
        If [bestId] has any value
            Text "[pickedTxt][bestId]|"
            Set variable pickedTxt to [Text]
            Get Value for [bestId] in [all]
            Set variable rr to [Dictionary Value]
            Text "★[best] [rr: title_ru] ([rr: year])"
            Add [Text] to topLines
        End If
    End Repeat
End If

// ── Шаг 14. Посмотрено по годам ──
Nothing
Set variable yearLines
Get All Keys in [byYear]
Set variable yKeys to [Keys]
If [yKeys] has any value
    Calculate Minimum of [yKeys]   (Calculate Statistics)
    Set variable minY to [Result]
    Calculate Maximum of [yKeys]   (Calculate Statistics)
    Set variable maxY to [Result]
    Calculate [maxY] − [minY]
    Calculate [Calculation Result] + 1
    Set variable span to [Calculation Result]
    Repeat [span] times
        Calculate [Repeat Index] − 1
        Calculate [minY] + [Calculation Result]
        Set variable y to [Calculation Result]
        Get Value for [y] in [byYear]
        If [Dictionary Value] has any value
            Text "[y]: [Dictionary Value]"
            Add [Text] to yearLines
        End If
    End Repeat
End If

// ── Шаг 15. Текст отчёта ──
Nothing
Set variable lines
Text "Всего: [total]"
Add [Text] to lines
Text "Статусы: [stTxt]"
Add [Text] to lines
Text "Типы: [tyTxt]"
Add [Text] to lines
If [n] is 0
    Text "Оценок нет"
    Add [Text] to lines
Otherwise
    Text "Оценено: [n], средняя [meanR]"
    Add [Text] to lines
End If
If [genreLines] has any value
    Combine [genreLines] with New Lines
    Text "↵Любимые жанры:↵[Combined Text]"
    Add [Text] to lines
End If
If [yearLines] has any value
    Combine [yearLines] with New Lines
    Text "↵Посмотрено по годам:↵[Combined Text]"
    Add [Text] to lines
End If
If [topLines] has any value
    Combine [topLines] with New Lines
    Text "↵Топ-5:↵[Combined Text]"
    Add [Text] to lines
End If
Combine [lines] with New Lines
Set variable report to [Combined Text]

// ── Шаг 16. Показ ──
Quick Look [report]

// ── Шаг 17. Дальше? ──
Nothing
Set variable act
Choose from Menu   (Prompt: "Дальше?")
    • Разбор вкуса (ИИ)
        Text "taste"
        Set variable act to [Text]
    • Готово
End Menu

// ── Шаг 18. Разбор вкуса: проверки ──
If [act] is "taste"
    If [aiOn] is False
        Show notification "ИИ выключен"
        Stop this shortcut
    End If
    If [n] is less than 3
        Show notification "Мало оценок"
        Stop this shortcut
    End If

    // ── Шаг 19. [top]: топ-10 по my_rating ──
    Nothing
    Set variable topL
    Text "|"
    Set variable pickedTxt to [Text]
    Get All Keys in [rScore]
    Set variable rKeys to [Keys]
    Repeat 10 times
        Number -1
        Set variable best to [Number]
        Nothing
        Set variable bestId
        Repeat with each item in [rKeys]
            Get Value for [Repeat Item] in [rScore]
            Number [Dictionary Value]
            Set variable v to [Number]
            Text "|[Repeat Item]|"
            If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                Set variable best to [v]
                Set variable bestId to [Repeat Item]
            End If
        End Repeat
        If [bestId] has any value
            Text "[pickedTxt][bestId]|"
            Set variable pickedTxt to [Text]
            Get Value for [bestId] in [all]
            Set variable rr to [Dictionary Value]
            Text "[rr: title_en] ([rr: year], [rr: type]) — [rr: my_rating] — [rr: note]"
            Add [Text] to topL
        End If
    End Repeat
    Combine [topL] with New Lines
    Set variable top to [Combined Text]

    // ── Шаг 20. [low]: до 10 с my_rating ≤ 5 или status = dropped ──
    Nothing
    Set variable lowL
    Repeat with each item in [db]
        Set variable it to [Repeat Item]
        Number 0
        Set variable isLow to [Number]
        Get Value for "status" in [it]
        If [Dictionary Value] is "dropped"
            Number 1
            Set variable isLow to [Number]
        End If
        Get Value for "my_rating" in [it]
        Set variable mr to [Dictionary Value]
        If [mr] has any value
            Number [mr]
            If [Number] is less than or equal to 5
                Number 1
                Set variable isLow to [Number]
            End If
        End If
        Count Items in [lowL]
        If All are true: [isLow] is 1, [Count] is less than 10
            Text "[it: title_en] ([it: year], [it: type]) — [it: my_rating] — [it: note]"
            Add [Text] to lowL
        End If
    End Repeat
    Combine [lowL] with New Lines
    Set variable low to [Combined Text]

    // ── Шаг 21. Чат ──
    Use Private Cloud Compute model   (промпт «Разбор вкуса» из README с [report], [top], [low]; Output: Text; Follow Up: on)
End If
```
