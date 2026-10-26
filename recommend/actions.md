# Movie Recommend — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat / Menu.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для списков (`Add to Variable` копит между оборотами Repeat) и флагов.
- **Меню → `act`**: пункты меню только ставят `act`, сами действия — ниже отдельными If.
- **Машина состояний**: `Repeat 200` + `screen`. Блоки экранов идут подряд, каждый обёрнут в `If [screen] is …` — сменил `screen` на экран ниже по тексту → он выполнится в этом же обороте, выше → в следующем.
- **«Есть ли в списке»**: список → `Combine` через `|` → `Text "|…|"` → `If … contains "|x|"`. Условие `contains` по списку ненадёжно, по тексту — работает.
- **Выбор из списка**: метки и элементы — два параллельных списка; выбранную метку ищем по `Repeat Index` → `Get Item at Index`. Не Dictionary «метка → элемент»: в метках есть точки (`IMDb 8.1`), а точка в Get Value — путь.
- **Вложенный Repeat** внутри `Repeat 200`: iOS может назвать плашки `Repeat Item 2` / `Repeat Index 2` — бери плашку своего Repeat.
- **Число из словаря** (Data Jar, ответ API, `Repeat Item: поле`) приходит без типа — If не даёт сравнений «больше/меньше». Перед сравнением: `Number [x]` → сравнивать `[Number]`.
- **Calculate Statistics**: плашка результата — `[Average]` (как назовёт iOS — та и есть).

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If

// ── Шаг 2. Веса скоринга (нет значения → по умолчанию из data.md) ──
Get Value for "weights" in [settings]
Set variable weights to [Dictionary Value]
Get Value for "smoothing_k" in [weights]
Set variable k to [Dictionary Value]
If [k] does not have any value
    Number 3
    Set variable k to [Number]
End If
Get Value for "taste" in [weights]
Set variable wTaste to [Dictionary Value]
If [wTaste] does not have any value
    Number 0.6
    Set variable wTaste to [Number]
End If
Get Value for "external" in [weights]
Set variable wExt to [Dictionary Value]
If [wExt] does not have any value
    Number 0.4
    Set variable wExt to [Number]
End If
Get Value for "min_ratings" in [weights]
Set variable minR to [Dictionary Value]
If [minR] does not have any value
    Number 3
    Set variable minR to [Number]
End If
Number [minR]
Set variable minR to [Number]

// ── Шаг 3. Размер «Мой список» ──
Get Value for "top_n" in [settings]
Set variable topN to [Dictionary Value]
If [topN] does not have any value
    Number 3
    Set variable topN to [Number]
End If
Number [topN]
Set variable topN to [Number]

// ── Шаг 4. ИИ включён? ──
Get Value for "ai_enabled" in [settings]
Set variable aiOn to [Dictionary Value]
// не задан → пусто → проверка «is False» не сработает = ИИ включён

// ── Шаг 5. Справочники ──
Get Value for "genres" in [settings]
Combine [Dictionary Value] with Custom ", "
Set variable genreList to [Combined Text]
Get Value for "type_labels" in [settings]
Set variable typeLabels to [Dictionary Value]
Get Value for "tags" in [settings]
Set variable tagsList to [Dictionary Value]

// ── Шаг 6. База ──
Get Value for "movies.items" (Data Jar)
Get All Values in [Value]
Set variable db to [Values]

// ── Шаг 7. Режим из входа ──
// Get Value из Text ("today") падает: "couldn't convert from Text to Dictionary"
Get Type of [Shortcut Input]
If [Type] is "Dictionary"
    Get Value for "mode" in [Shortcut Input]
    Set variable inMode to [Dictionary Value]
End If

// ── Шаг 8. Голосовой режим (без цикла и экранов) ──
If [inMode] is "voice"

    // 8.1 Вход
    Get Value for "mood" in [Shortcut Input]
    Set variable mood to [Dictionary Value]
    Dictionary   (пустой)
    Set variable flt to [Dictionary]
    Get Value for "types" in [Shortcut Input]
    If [Dictionary Value] has any value
        Set "types" to [Dictionary Value] in [flt]
        Set variable flt to [Dictionary]
    End If

    // 8.2 Кандидаты
    Get Value for "types" in [flt]
    Set variable fTypes to [Dictionary Value]
    Combine [fTypes] with Custom "|"
    Text "|[Combined Text]|"
    Set variable fTypesTxt to [Text]
    Get Value for "genres" in [flt]
    Set variable fGenres to [Dictionary Value]
    Get Value for "maxLen" in [flt]
    Set variable fMax to [Dictionary Value]
    If [fMax] has any value
        Number [fMax]
        Set variable fMax to [Number]
    End If
    Get Value for "tag" in [flt]
    Set variable fTag to [Dictionary Value]
    Nothing
    Set variable cands
    Dictionary   (пустой)
    Set variable candMap to [Dictionary]
    Repeat with each item in [db]
        Number 1
        Set variable ok to [Number]
        Get Value for "status" in [Repeat Item]
        If All are true: [Dictionary Value] is not "unwatched", [Dictionary Value] is not "watching"
            Number 0
            Set variable ok to [Number]
        End If
        If [fTypes] has any value
            Text "|[Repeat Item: type]|"
            If [fTypesTxt] does not contain [Text]
                Number 0
                Set variable ok to [Number]
            End If
        End If
        If [fGenres] has any value
            Get Value for "genres" in [Repeat Item]
            Combine [Dictionary Value] with Custom "|"
            Text "|[Combined Text]|"
            Set variable recGTxt to [Text]
            Number 0
            Set variable gHit to [Number]
            Repeat with each item in [fGenres]
                Text "|[Repeat Item 2]|"
                If [recGTxt] contains [Text]
                    Number 1
                    Set variable gHit to [Number]
                End If
            End Repeat
            If [gHit] is 0
                Number 0
                Set variable ok to [Number]
            End If
        End If
        If [fMax] has any value
            Get Value for "length" in [Repeat Item]
            If [Dictionary Value] has any value
                Number [Dictionary Value]
                If [Number] is greater than [fMax]
                    Number 0
                    Set variable ok to [Number]
                End If
            End If
        End If
        If [fTag] has any value
            Get Value for "tags" in [Repeat Item]
            Combine [Dictionary Value] with Custom "|"
            Text "|[Combined Text]|"
            Set variable recTTxt to [Text]
            Text "|[fTag]|"
            If [recTTxt] does not contain [Text]
                Number 0
                Set variable ok to [Number]
            End If
        End If
        If [ok] is 1
            Add [Repeat Item] to cands
            Set [Repeat Item: key] to [Repeat Item] in [candMap]
            Set variable candMap to [Dictionary]
        End If
    End Repeat
    If [cands] does not have any value
        Text "Your watchlist is empty for that. Add something first."
        Stop and output [Text]
    End If

    // 8.3 Скоринг, шаги 1–4
    // скоринг 1. rated, n
    Nothing
    Set variable rated
    Nothing
    Set variable ratedVals
    Repeat with each item in [db]
        Get Value for "my_rating" in [Repeat Item]
        If [Dictionary Value] has any value
            Add [Repeat Item] to rated
            Add [Dictionary Value] to ratedVals
        End If
    End Repeat
    Count Items in [rated]
    Set variable n to [Count]
    // скоринг 2. mean
    Calculate Average of [ratedVals]   (Calculate Statistics)
    Set variable mean to [Average]
    // скоринг 3. gSum, gCnt
    Dictionary   (пустой)
    Set variable gSum to [Dictionary]
    Dictionary   (пустой)
    Set variable gCnt to [Dictionary]
    Repeat with each item in [rated]
        Get Value for "my_rating" in [Repeat Item]
        Set variable r to [Dictionary Value]
        Get Value for "genres" in [Repeat Item]
        Repeat with each item in [Dictionary Value]
            Get Value for [Repeat Item 2] in [gSum]
            Set variable cur to [Dictionary Value]
            If [cur] does not have any value
                Number 0
                Set variable cur to [Number]
            End If
            Calculate [cur] + [r]
            Set [Repeat Item 2] to [Calculation Result] in [gSum]
            Set variable gSum to [Dictionary]
            Get Value for [Repeat Item 2] in [gCnt]
            Set variable cur to [Dictionary Value]
            If [cur] does not have any value
                Number 0
                Set variable cur to [Number]
            End If
            Calculate [cur] + 1
            Set [Repeat Item 2] to [Calculation Result] in [gCnt]
            Set variable gCnt to [Dictionary]
        End Repeat
    End Repeat
    // скоринг 4. genre_score
    Dictionary   (пустой)
    Set variable gScore to [Dictionary]
    Get All Keys in [gSum]
    Repeat with each item in [Keys]
        Get Value for [Repeat Item] in [gSum]
        Set variable s to [Dictionary Value]
        Get Value for [Repeat Item] in [gCnt]
        Set variable c to [Dictionary Value]
        Calculate Expression "([s] + [k] * [mean]) / ([c] + [k])"
        Set [Repeat Item] to [Calculation Result] in [gScore]
        Set variable gScore to [Dictionary]
    End Repeat
    // [topGenres]: 5 жанров с наибольшим genre_score (перебор максимума)
    Nothing
    Set variable topG
    Repeat 5 times
        Number -1
        Set variable best to [Number]
        Nothing
        Set variable bestId
        Combine [topG] with Custom "|"
        Text "|[Combined Text]|"
        Set variable pickedTxt to [Text]
        Get All Keys in [gScore]
        Repeat with each item in [Keys]
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
            Add [bestId] to topG
        End If
    End Repeat
    If [topG] has any value
        Combine [topG] with Custom ", "
        Set variable topGenres to [Combined Text]
    Otherwise
        Text "none"
        Set variable topGenres to [Text]
    End If

    // 8.4 По настроению (ИИ)
    Nothing
    Set variable vList
    If [mood] has any value
        If [aiOn] is False
        Otherwise
            // [favorites]: до 10 записей с my_rating ≥ 8, по убыванию (перебор максимума)
            Dictionary   (пустой)
            Set variable favScores to [Dictionary]
            Dictionary   (пустой)
            Set variable ratedMap to [Dictionary]
            Repeat with each item in [rated]
                Set [Repeat Item: key] to [Repeat Item] in [ratedMap]
                Set variable ratedMap to [Dictionary]
                Number [Repeat Item: my_rating]
                If [Number] is greater than or equal to 8
                    Set [Repeat Item: key] to [Repeat Item: my_rating] in [favScores]
                    Set variable favScores to [Dictionary]
                End If
            End Repeat
            Nothing
            Set variable favIds
            Repeat 10 times
                Number -1
                Set variable best to [Number]
                Nothing
                Set variable bestId
                Combine [favIds] with Custom "|"
                Text "|[Combined Text]|"
                Set variable pickedTxt to [Text]
                Get All Keys in [favScores]
                Repeat with each item in [Keys]
                    Get Value for [Repeat Item] in [favScores]
                    Number [Dictionary Value]
                    Set variable v to [Number]
                    Text "|[Repeat Item]|"
                    If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                        Set variable best to [v]
                        Set variable bestId to [Repeat Item]
                    End If
                End Repeat
                If [bestId] has any value
                    Add [bestId] to favIds
                End If
            End Repeat
            Nothing
            Set variable favLines
            Repeat with each item in [favIds]
                Get Value for [Repeat Item] in [ratedMap]
                Set variable fr to [Dictionary Value]
                Text "[fr: title_en] ([fr: year]) — [fr: my_rating] — [fr: note]"
                Add [Text] to favLines
            End Repeat
            If [favLines] has any value
                Combine [favLines] with New Lines
                Set variable favorites to [Combined Text]
            Otherwise
                Text "none"
                Set variable favorites to [Text]
            End If
            // [candidates]: key | title_en | year | type | genres | length
            Nothing
            Set variable candLines
            Repeat with each item in [cands]
                Get Value for "genres" in [Repeat Item]
                Combine [Dictionary Value] with Custom ", "
                Text "[Repeat Item: key] | [Repeat Item: title_en] | [Repeat Item: year] | [Repeat Item: type] | [Combined Text] | [Repeat Item: length]"
                Add [Text] to candLines
            End Repeat
            Combine [candLines] with New Lines
            Set variable candText to [Combined Text]
            Use Private Cloud Compute model   (промпт «По настроению» из README с [mood], [answers] = "none", [topGenres], [favorites], [shown] = "none", [feedback] = "none", [candText] + в конце строка "Write \"reason\" in English."; Output: Dictionary)
            Get Value for "picks" in [Response]
            Set variable picks to [Dictionary Value]
            If [picks] does not have any value
                Use Private Cloud Compute model   (тот же промпт + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
                Replace "```json" with "" in [Response]
                Replace "```" with "" in [Updated Text]
                Get dictionary from [Updated Text]
                Get Value for "picks" in [Dictionary]
                Set variable picks to [Dictionary Value]
            End If
            Repeat with each item in [picks]
                Get Value for [Repeat Item: key] in [candMap]
                If [Dictionary Value] has any value
                    Set "reason" to [Repeat Item: reason] in [Dictionary Value]
                    Add [Dictionary] to vList
                End If
            End Repeat
        End If
    End If

    // 8.5 Без настроения / ИИ выключен / ИИ не ответил → топ-3 по вкусу
    If [vList] does not have any value
        // скоринг 5. Final для каждого кандидата → scores (key → балл)
        Dictionary   (пустой)
        Set variable scores to [Dictionary]
        Repeat with each item in [cands]
            // Taste: среднее genre_score по жанрам; жанр без оценок / нет жанров → mean
            Nothing
            Set variable tv
            Get Value for "genres" in [Repeat Item]
            Repeat with each item in [Dictionary Value]
                Get Value for [Repeat Item 2] in [gScore]
                If [Dictionary Value] has any value
                    Add [Dictionary Value] to tv
                Otherwise
                    Add [mean] to tv
                End If
            End Repeat
            If [tv] has any value
                Calculate Average of [tv]   (Calculate Statistics)
                Set variable tasteV to [Average]
            Otherwise
                Set variable tasteV to [mean]
            End If
            // External: среднее имеющихся imdb, kp
            Nothing
            Set variable ev
            // ratings может не быть — путь "ratings.imdb" тогда падает
            Get Value for "ratings" in [Repeat Item]
            Set variable rts to [Dictionary Value]
            If [rts] does not have any value
                Dictionary   (пустой)
                Set variable rts to [Dictionary]
            End If
            Get Value for "imdb" in [rts]
            If [Dictionary Value] has any value
                Add [Dictionary Value] to ev
            End If
            Get Value for "kp" in [rts]
            If [Dictionary Value] has any value
                Add [Dictionary Value] to ev
            End If
            Nothing
            Set variable extV
            If [ev] has any value
                Calculate Average of [ev]   (Calculate Statistics)
                Set variable extV to [Average]
            End If
            // Final
            If [n] is less than [minR]
                If [extV] has any value
                    Set variable final to [extV]
                Otherwise
                    Number 5
                    Set variable final to [Number]
                End If
            Otherwise
                If [extV] has any value
                    Calculate Expression "[wTaste] * [tasteV] + [wExt] * [extV]"
                    Set variable final to [Calculation Result]
                Otherwise
                    Set variable final to [tasteV]
                End If
            End If
            Round [final] to Tenths
            Set [Repeat Item: key] to [Rounded Number] in [scores]
            Set variable scores to [Dictionary]
        End Repeat
        Number 3
        Set variable nTop to [Number]
        // топ-[nTop] из scores перебором максимума → picked (key по убыванию)
        Nothing
        Set variable picked
        Repeat [nTop] times
            Number -1
            Set variable best to [Number]
            Nothing
            Set variable bestId
            Combine [picked] with Custom "|"
            Text "|[Combined Text]|"
            Set variable pickedTxt to [Text]
            Get All Keys in [scores]
            Repeat with each item in [Keys]
                Get Value for [Repeat Item] in [scores]
                Number [Dictionary Value]
                Set variable v to [Number]
                Text "|[Repeat Item]|"
                If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                    Set variable best to [v]
                    Set variable bestId to [Repeat Item]
                End If
            End Repeat
            If [bestId] has any value
                Add [bestId] to picked
            End If
        End Repeat
        Repeat with each item in [picked]
            Get Value for [Repeat Item] in [candMap]
            Add [Dictionary Value] to vList
        End Repeat
    End If

    // 8.6 Ответ для Siri
    Nothing
    Set variable outParts
    Repeat with each item in [vList]
        Get Value for "reason" in [Repeat Item]
        Set variable why to [Dictionary Value]
        // ratings может не быть — путь "ratings.imdb" тогда падает
        Get Value for "ratings" in [Repeat Item]
        Set variable rts to [Dictionary Value]
        If [rts] does not have any value
            Dictionary   (пустой)
            Set variable rts to [Dictionary]
        End If
        Get Value for "imdb" in [rts]
        Set variable vImdb to [Dictionary Value]
        If [why] has any value
            Text "[Repeat Index]. [Repeat Item: title_en] ([Repeat Item: year]) — [why]."
            Set variable line to [Text]
        Otherwise
            If [vImdb] has any value
                Text "[Repeat Index]. [Repeat Item: title_en] ([Repeat Item: year]) — IMDb [vImdb]."
                Set variable line to [Text]
            Otherwise
                Text "[Repeat Index]. [Repeat Item: title_en] ([Repeat Item: year])."
                Set variable line to [Text]
            End If
        End If
        Add [line] to outParts
    End Repeat
    Combine [outParts] with Custom " "
    Text "Try: [Combined Text]"
    Stop and output [Text]
End If

// ── Шаг 9. Старт: today или меню ──
Dictionary   (пустой)
Set variable flt to [Dictionary]
If [Shortcut Input] is "today"
    Text "today"
    Set variable mode to [Text]
    Text "list"
    Set variable screen to [Text]
Otherwise
    Text "menu"
    Set variable screen to [Text]
End If

// ── Шаг 10. Цикл экранов ──
Repeat 200 times

    // ── Шаг 11. screen = menu ──
    If [screen] is "menu"
        Nothing
        Set variable act
        Choose from Menu   (Prompt: "Что смотрим?")
            • Мой список (по вкусу)
                Text "taste"
                Set variable act to [Text]
            • Случайный
                Text "random"
                Set variable act to [Text]
            • По настроению (ИИ)
                Text "mood"
                Set variable act to [Text]
            • Новое (ИИ)
                Text "new"
                Set variable act to [Text]
            • Выход
                Stop this shortcut
        End Menu
        If Any are true: [act] is "mood", [act] is "new"
            If [aiOn] is False
                Show notification "ИИ выключен"
                Nothing
                Set variable act
            End If
        End If
        If [act] has any value
            Set variable mode to [act]
            Nothing
            Set variable listItems
            Nothing
            Set variable feedback
            Nothing
            Set variable shown
            Nothing
            Set variable mood
            Nothing
            Set variable answers
            Text "filters"
            Set variable screen to [Text]
        End If
    End If

    // ── Шаг 12. screen = filters ──
    If [screen] is "filters"
        Nothing
        Set variable act
        Choose from Menu   (Prompt: "Фильтры")
            • Без фильтров
                Text "none"
                Set variable act to [Text]
            • Настроить
                Text "set"
                Set variable act to [Text]
            • ← Назад
                Text "back"
                Set variable act to [Text]
        End Menu

        // 12.1 ← Назад
        If [act] is "back"
            Text "menu"
            Set variable screen to [Text]
        End If

        // 12.2 Без фильтров
        If [act] is "none"
            Dictionary   (пустой)
            Set variable flt to [Dictionary]
            Nothing
            Set variable listItems
            Text "list"
            Set variable screen to [Text]
        End If

        // 12.3 Настроить
        If [act] is "set"
            Dictionary   (пустой)
            Set variable flt to [Dictionary]

            // 12.3.1 Типы (пусто = любой)
            Get All Values in [typeLabels]
            Choose from [Values]   (Prompt: "Тип (ничего = любой)", Select Multiple: on)
            Combine [Chosen Items] with Custom "|"
            Text "|[Combined Text]|"
            Set variable chosenTxt to [Text]
            Nothing
            Set variable selTypes
            Get All Keys in [typeLabels]
            Repeat with each item in [Keys]
                Get Value for [Repeat Item] in [typeLabels]
                Text "|[Dictionary Value]|"
                If [chosenTxt] contains [Text]
                    Add [Repeat Item] to selTypes
                End If
            End Repeat
            If [selTypes] has any value
                Set "types" to [selTypes] in [flt]
                Set variable flt to [Dictionary]
            End If

            // 12.3.2 Жанры (пусто = любой)
            Get Value for "genres" in [settings]
            Choose from [Dictionary Value]   (Prompt: "Жанры (ничего = любой)", Select Multiple: on)
            Set variable selGenres to [Chosen Items]
            If [selGenres] has any value
                Set "genres" to [selGenres] in [flt]
                Set variable flt to [Dictionary]
            End If

            // 12.3.3 Длительность
            Nothing
            Set variable selMax
            Choose from Menu   (Prompt: "Длительность")
                • Любая
                • ≤ 90 мин
                    Number 90
                    Set variable selMax to [Number]
                • ≤ 120 мин
                    Number 120
                    Set variable selMax to [Number]
                • ≤ 150 мин
                    Number 150
                    Set variable selMax to [Number]
            End Menu
            If [selMax] has any value
                Set "maxLen" to [selMax] in [flt]
                Set variable flt to [Dictionary]
            End If

            // 12.3.4 Тег
            List
                Любой
            Set variable tagOpts to [List]
            Repeat with each item in [tagsList]
                Add [Repeat Item] to tagOpts
            End Repeat
            Choose from [tagOpts]   (Prompt: "Тег")
            If [Chosen Item] is not "Любой"
                Set "tag" to [Chosen Item] in [flt]
                Set variable flt to [Dictionary]
            End If

            Nothing
            Set variable listItems
            Text "list"
            Set variable screen to [Text]
        End If
    End If

    // ── Шаг 13. screen = list: строить список? ──
    Nothing
    Set variable build
    If [screen] is "list"
        If [listItems] does not have any value
            Number 1
            Set variable build to [Number]
        End If
    End If

    // ── Шаг 14. Фильтры → переменные ──
    If [build] is 1
        Get Value for "types" in [flt]
        Set variable fTypes to [Dictionary Value]
        Combine [fTypes] with Custom "|"
        Text "|[Combined Text]|"
        Set variable fTypesTxt to [Text]
        Get Value for "genres" in [flt]
        Set variable fGenres to [Dictionary Value]
        Get Value for "maxLen" in [flt]
        Set variable fMax to [Dictionary Value]
        If [fMax] has any value
            Number [fMax]
            Set variable fMax to [Number]
        End If
        Get Value for "tag" in [flt]
        Set variable fTag to [Dictionary Value]
    End If

    // ── Шаг 15. Кандидаты (taste / random / mood / today) ──
    If All are true: [build] is 1, [mode] is not "new", [mode] is not "similar"
        Nothing
        Set variable cands
        Dictionary   (пустой)
        Set variable candMap to [Dictionary]
        Repeat with each item in [db]
            Number 1
            Set variable ok to [Number]
            Get Value for "status" in [Repeat Item]
            If All are true: [Dictionary Value] is not "unwatched", [Dictionary Value] is not "watching"
                Number 0
                Set variable ok to [Number]
            End If
            If [fTypes] has any value
                Text "|[Repeat Item: type]|"
                If [fTypesTxt] does not contain [Text]
                    Number 0
                    Set variable ok to [Number]
                End If
            End If
            If [fGenres] has any value
                Get Value for "genres" in [Repeat Item]
                Combine [Dictionary Value] with Custom "|"
                Text "|[Combined Text]|"
                Set variable recGTxt to [Text]
                Number 0
                Set variable gHit to [Number]
                Repeat with each item in [fGenres]
                    Text "|[Repeat Item 2]|"
                    If [recGTxt] contains [Text]
                        Number 1
                        Set variable gHit to [Number]
                    End If
                End Repeat
                If [gHit] is 0
                    Number 0
                    Set variable ok to [Number]
                End If
            End If
            If [fMax] has any value
                Get Value for "length" in [Repeat Item]
                If [Dictionary Value] has any value
                    Number [Dictionary Value]
                    If [Number] is greater than [fMax]
                        Number 0
                        Set variable ok to [Number]
                    End If
                End If
            End If
            If [fTag] has any value
                Get Value for "tags" in [Repeat Item]
                Combine [Dictionary Value] with Custom "|"
                Text "|[Combined Text]|"
                Set variable recTTxt to [Text]
                Text "|[fTag]|"
                If [recTTxt] does not contain [Text]
                    Number 0
                    Set variable ok to [Number]
                End If
            End If
            If [ok] is 1
                Add [Repeat Item] to cands
                Set [Repeat Item: key] to [Repeat Item] in [candMap]
                Set variable candMap to [Dictionary]
            End If
        End Repeat
        If [cands] does not have any value
            If [mode] is "today"
                Show notification "Список пуст — добавь через Movie Add"
                Stop this shortcut
            Otherwise
                Show notification "Ничего не подходит"
                Text "filters"
                Set variable screen to [Text]
            End If
        End If
    End If

    // ── Шаг 16. Скоринг, шаги 1–4 (все режимы, кроме random / similar) ──
    If All are true: [screen] is "list", [build] is 1, [mode] is not "random", [mode] is not "similar"
        // скоринг 1. rated, n
        Nothing
        Set variable rated
        Nothing
        Set variable ratedVals
        Repeat with each item in [db]
            Get Value for "my_rating" in [Repeat Item]
            If [Dictionary Value] has any value
                Add [Repeat Item] to rated
                Add [Dictionary Value] to ratedVals
            End If
        End Repeat
        Count Items in [rated]
        Set variable n to [Count]
        // скоринг 2. mean
        Calculate Average of [ratedVals]   (Calculate Statistics)
        Set variable mean to [Average]
        // скоринг 3. gSum, gCnt
        Dictionary   (пустой)
        Set variable gSum to [Dictionary]
        Dictionary   (пустой)
        Set variable gCnt to [Dictionary]
        Repeat with each item in [rated]
            Get Value for "my_rating" in [Repeat Item]
            Set variable r to [Dictionary Value]
            Get Value for "genres" in [Repeat Item]
            Repeat with each item in [Dictionary Value]
                Get Value for [Repeat Item 2] in [gSum]
                Set variable cur to [Dictionary Value]
                If [cur] does not have any value
                    Number 0
                    Set variable cur to [Number]
                End If
                Calculate [cur] + [r]
                Set [Repeat Item 2] to [Calculation Result] in [gSum]
                Set variable gSum to [Dictionary]
                Get Value for [Repeat Item 2] in [gCnt]
                Set variable cur to [Dictionary Value]
                If [cur] does not have any value
                    Number 0
                    Set variable cur to [Number]
                End If
                Calculate [cur] + 1
                Set [Repeat Item 2] to [Calculation Result] in [gCnt]
                Set variable gCnt to [Dictionary]
            End Repeat
        End Repeat
        // скоринг 4. genre_score
        Dictionary   (пустой)
        Set variable gScore to [Dictionary]
        Get All Keys in [gSum]
        Repeat with each item in [Keys]
            Get Value for [Repeat Item] in [gSum]
            Set variable s to [Dictionary Value]
            Get Value for [Repeat Item] in [gCnt]
            Set variable c to [Dictionary Value]
            Calculate Expression "([s] + [k] * [mean]) / ([c] + [k])"
            Set [Repeat Item] to [Calculation Result] in [gScore]
            Set variable gScore to [Dictionary]
        End Repeat
        // [topGenres]: 5 жанров с наибольшим genre_score (перебор максимума)
        Nothing
        Set variable topG
        Repeat 5 times
            Number -1
            Set variable best to [Number]
            Nothing
            Set variable bestId
            Combine [topG] with Custom "|"
            Text "|[Combined Text]|"
            Set variable pickedTxt to [Text]
            Get All Keys in [gScore]
            Repeat with each item in [Keys]
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
                Add [bestId] to topG
            End If
        End Repeat
        If [topG] has any value
            Combine [topG] with Custom ", "
            Set variable topGenres to [Combined Text]
        Otherwise
            Text "none"
            Set variable topGenres to [Text]
        End If
    End If

    // ── Шаг 17. [favorites] (mood / new) ──
    If All are true: [screen] is "list", [build] is 1
        If Any are true: [mode] is "mood", [mode] is "new"
            // [favorites]: до 10 записей с my_rating ≥ 8, по убыванию (перебор максимума)
            Dictionary   (пустой)
            Set variable favScores to [Dictionary]
            Dictionary   (пустой)
            Set variable ratedMap to [Dictionary]
            Repeat with each item in [rated]
                Set [Repeat Item: key] to [Repeat Item] in [ratedMap]
                Set variable ratedMap to [Dictionary]
                Number [Repeat Item: my_rating]
                If [Number] is greater than or equal to 8
                    Set [Repeat Item: key] to [Repeat Item: my_rating] in [favScores]
                    Set variable favScores to [Dictionary]
                End If
            End Repeat
            Nothing
            Set variable favIds
            Repeat 10 times
                Number -1
                Set variable best to [Number]
                Nothing
                Set variable bestId
                Combine [favIds] with Custom "|"
                Text "|[Combined Text]|"
                Set variable pickedTxt to [Text]
                Get All Keys in [favScores]
                Repeat with each item in [Keys]
                    Get Value for [Repeat Item] in [favScores]
                    Number [Dictionary Value]
                    Set variable v to [Number]
                    Text "|[Repeat Item]|"
                    If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                        Set variable best to [v]
                        Set variable bestId to [Repeat Item]
                    End If
                End Repeat
                If [bestId] has any value
                    Add [bestId] to favIds
                End If
            End Repeat
            Nothing
            Set variable favLines
            Repeat with each item in [favIds]
                Get Value for [Repeat Item] in [ratedMap]
                Set variable fr to [Dictionary Value]
                Text "[fr: title_en] ([fr: year]) — [fr: my_rating] — [fr: note]"
                Add [Text] to favLines
            End Repeat
            If [favLines] has any value
                Combine [favLines] with New Lines
                Set variable favorites to [Combined Text]
            Otherwise
                Text "none"
                Set variable favorites to [Text]
            End If
        End If
    End If

    // ── Шаг 18. taste / today ──
    If All are true: [screen] is "list", [build] is 1
        If Any are true: [mode] is "taste", [mode] is "today"
            // скоринг 5. Final для каждого кандидата → scores (key → балл)
            Dictionary   (пустой)
            Set variable scores to [Dictionary]
            Repeat with each item in [cands]
                // Taste: среднее genre_score по жанрам; жанр без оценок / нет жанров → mean
                Nothing
                Set variable tv
                Get Value for "genres" in [Repeat Item]
                Repeat with each item in [Dictionary Value]
                    Get Value for [Repeat Item 2] in [gScore]
                    If [Dictionary Value] has any value
                        Add [Dictionary Value] to tv
                    Otherwise
                        Add [mean] to tv
                    End If
                End Repeat
                If [tv] has any value
                    Calculate Average of [tv]   (Calculate Statistics)
                    Set variable tasteV to [Average]
                Otherwise
                    Set variable tasteV to [mean]
                End If
                // External: среднее имеющихся imdb, kp
                Nothing
                Set variable ev
                // ratings может не быть — путь "ratings.imdb" тогда падает
                Get Value for "ratings" in [Repeat Item]
                Set variable rts to [Dictionary Value]
                If [rts] does not have any value
                    Dictionary   (пустой)
                    Set variable rts to [Dictionary]
                End If
                Get Value for "imdb" in [rts]
                If [Dictionary Value] has any value
                    Add [Dictionary Value] to ev
                End If
                Get Value for "kp" in [rts]
                If [Dictionary Value] has any value
                    Add [Dictionary Value] to ev
                End If
                Nothing
                Set variable extV
                If [ev] has any value
                    Calculate Average of [ev]   (Calculate Statistics)
                    Set variable extV to [Average]
                End If
                // Final
                If [n] is less than [minR]
                    If [extV] has any value
                        Set variable final to [extV]
                    Otherwise
                        Number 5
                        Set variable final to [Number]
                    End If
                Otherwise
                    If [extV] has any value
                        Calculate Expression "[wTaste] * [tasteV] + [wExt] * [extV]"
                        Set variable final to [Calculation Result]
                    Otherwise
                        Set variable final to [tasteV]
                    End If
                End If
                Round [final] to Tenths
                Set [Repeat Item: key] to [Rounded Number] in [scores]
                Set variable scores to [Dictionary]
            End Repeat
            If [mode] is "today"
                Number 10
                Set variable nTop to [Number]
            Otherwise
                Set variable nTop to [topN]
            End If
            // топ-[nTop] из scores перебором максимума → picked (key по убыванию)
            Nothing
            Set variable picked
            Repeat [nTop] times
                Number -1
                Set variable best to [Number]
                Nothing
                Set variable bestId
                Combine [picked] with Custom "|"
                Text "|[Combined Text]|"
                Set variable pickedTxt to [Text]
                Get All Keys in [scores]
                Repeat with each item in [Keys]
                    Get Value for [Repeat Item] in [scores]
                    Number [Dictionary Value]
                    Set variable v to [Number]
                    Text "|[Repeat Item]|"
                    If All are true: [pickedTxt] does not contain [Text], [v] is greater than [best]
                        Set variable best to [v]
                        Set variable bestId to [Repeat Item]
                    End If
                End Repeat
                If [bestId] has any value
                    Add [bestId] to picked
                End If
            End Repeat

            // 18.1 today: случайный из топ-10 → сразу карточка
            If [mode] is "today"
                Get Random Item from [picked]
                Set variable selKey to [Item from List]
                Get Value for [selKey] in [candMap]
                Set variable selected to [Dictionary Value]
                Get Value for [selKey] in [scores]
                Set "predicted" to [Dictionary Value] in [selected]
                Set variable selected to [Dictionary]
                Text "card"
                Set variable screen to [Text]

            // 18.2 taste: топ-N → listItems (+ predicted)
            Otherwise
                Nothing
                Set variable listItems
                Repeat with each item in [picked]
                    Get Value for [Repeat Item] in [candMap]
                    Set variable it to [Dictionary Value]
                    Get Value for [Repeat Item] in [scores]
                    Set "predicted" to [Dictionary Value] in [it]
                    Add [Dictionary] to listItems
                End Repeat
            End If
        End If
    End If

    // ── Шаг 19. random ──
    If All are true: [screen] is "list", [build] is 1, [mode] is "random"
        Get Random Item from [cands]
        Set variable selected to [Item from List]
        Text "card"
        Set variable screen to [Text]
    End If

    // ── Шаг 20. mood ──
    If All are true: [screen] is "list", [build] is 1, [mode] is "mood"

        // 20.1 Настроение и уточнения (только при первом заходе; после «↻ Не то…» mood уже есть)
        If [mood] does not have any value
            Ask for Text with "Какое настроение?"
            Set variable mood to [Provided Input]
            Nothing
            Set variable answers
            Use Private Cloud Compute model   (промпт «Уточнение» из README с [mood]; Output: Dictionary)
            Get Value for "questions" in [Response]
            Set variable qs to [Dictionary Value]
            // пустой список questions — нормальный ответ; запасной путь — только если ответ не словарь
            Get All Keys in [Response]
            Count Items in [Keys]
            If [Count] is 0
                Use Private Cloud Compute model   (промпт «Уточнение» + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
                Replace "```json" with "" in [Response]
                Replace "```" with "" in [Updated Text]
                Get dictionary from [Updated Text]
                Get Value for "questions" in [Dictionary]
                Set variable qs to [Dictionary Value]
            End If
            Repeat with each item in [qs]
                Get Value for "options" in [Repeat Item]
                Set variable opts to [Dictionary Value]
                Text "Своё…"
                Add [Text] to opts
                Choose from [opts]   (Prompt: [Repeat Item: q])
                Set variable ans to [Chosen Item]
                If [ans] is "Своё…"
                    Ask for Text with [Repeat Item: q]
                    Set variable ans to [Provided Input]
                End If
                Text "[Repeat Item: q] — [ans]"
                Add [Text] to answers
            End Repeat
        End If

        // 20.2 Тексты для промпта
        If [answers] has any value
            Combine [answers] with New Lines
            Set variable answersT to [Combined Text]
        Otherwise
            Text "none"
            Set variable answersT to [Text]
        End If
        If [feedback] has any value
            Combine [feedback] with Custom "; "
            Set variable feedbackT to [Combined Text]
        Otherwise
            Text "none"
            Set variable feedbackT to [Text]
        End If
        If [shown] has any value
            Combine [shown] with Custom "; "
            Set variable shownT to [Combined Text]
        Otherwise
            Text "none"
            Set variable shownT to [Text]
        End If
        // [candidates]: key | title_en | year | type | genres | length
        Nothing
        Set variable candLines
        Repeat with each item in [cands]
            Get Value for "genres" in [Repeat Item]
            Combine [Dictionary Value] with Custom ", "
            Text "[Repeat Item: key] | [Repeat Item: title_en] | [Repeat Item: year] | [Repeat Item: type] | [Combined Text] | [Repeat Item: length]"
            Add [Text] to candLines
        End Repeat
        Combine [candLines] with New Lines
        Set variable candText to [Combined Text]

        // 20.3 Подбор
        Use Private Cloud Compute model   (промпт «По настроению» из README с [mood], [answersT], [topGenres], [favorites], [shownT], [feedbackT], [candText]; Output: Dictionary)
        Get Value for "picks" in [Response]
        Set variable picks to [Dictionary Value]
        If [picks] does not have any value
            Use Private Cloud Compute model   (промпт «По настроению» + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
            Replace "```json" with "" in [Response]
            Replace "```" with "" in [Updated Text]
            Get dictionary from [Updated Text]
            Get Value for "picks" in [Dictionary]
            Set variable picks to [Dictionary Value]
            If [picks] does not have any value
                Show notification "ИИ не ответил"
                Text "menu"
                Set variable screen to [Text]
            End If
        End If

        // 20.4 picks → записи из cands (+ reason); ключа нет в cands — отбросить
        If [picks] has any value
            Nothing
            Set variable listItems
            Repeat with each item in [picks]
                Get Value for [Repeat Item: key] in [candMap]
                If [Dictionary Value] has any value
                    Set "reason" to [Repeat Item: reason] in [Dictionary Value]
                    Add [Dictionary] to listItems
                End If
            End Repeat
            If [listItems] does not have any value
                Show notification "ИИ не подобрал из списка"
                Text "menu"
                Set variable screen to [Text]
            End If
        End If
    End If

    // ── Шаг 21. similar: кеш опустел ──
    If All are true: [screen] is "list", [build] is 1, [mode] is "similar"
        Show notification "Похожих не осталось"
        Text "menu"
        Set variable screen to [Text]
    End If

    // ── Шаг 22. new ──
    If All are true: [screen] is "list", [build] is 1, [mode] is "new"

        // 22.1 [disliked]: до 5 записей с my_rating ≤ 4 или status = dropped
        Nothing
        Set variable disLines
        Repeat with each item in [db]
            Number 0
            Set variable bad to [Number]
            Get Value for "my_rating" in [Repeat Item]
            If [Dictionary Value] has any value
                Number [Dictionary Value]
                If [Number] is less than or equal to 4
                    Number 1
                    Set variable bad to [Number]
                End If
            End If
            If [Repeat Item: status] is "dropped"
                Number 1
                Set variable bad to [Number]
            End If
            Count Items in [disLines]
            If All are true: [bad] is 1, [Count] is less than 5
                Text "[Repeat Item: title_en] ([Repeat Item: year]) — [Repeat Item: my_rating] — [Repeat Item: note]"
                Add [Text] to disLines
            End If
        End Repeat
        If [disLines] has any value
            Combine [disLines] with New Lines
            Set variable disliked to [Combined Text]
        Otherwise
            Text "none"
            Set variable disliked to [Text]
        End If

        // 22.2 [exclude] и dbTxt (title_en lowercase + year) для фильтра ответа
        Nothing
        Set variable excl
        Nothing
        Set variable dbT
        Repeat with each item in [db]
            Text "[Repeat Item: title_en] ([Repeat Item: year])"
            Add [Text] to excl
            Change [Repeat Item: title_en] to lowercase
            Text "[Updated Text] [Repeat Item: year]"
            Add [Text] to dbT
        End Repeat
        Combine [excl] with Custom "; "
        Set variable exclude to [Combined Text]
        Combine [dbT] with Custom "|"
        Text "|[Combined Text]|"
        Set variable dbTxt to [Text]

        // 22.3 [filters]
        Nothing
        Set variable fParts
        If [fTypes] has any value
            Combine [fTypes] with Custom ", "
            Text "types: [Combined Text]"
            Add [Text] to fParts
        End If
        If [fGenres] has any value
            Combine [fGenres] with Custom ", "
            Text "genres: [Combined Text]"
            Add [Text] to fParts
        End If
        If [fMax] has any value
            Text "max length: [fMax] min"
            Add [Text] to fParts
        End If
        If [fTag] has any value
            Text "tag: [fTag]"
            Add [Text] to fParts
        End If
        If [fParts] has any value
            Combine [fParts] with Custom "; "
            Set variable filtersT to [Combined Text]
        Otherwise
            Text "none"
            Set variable filtersT to [Text]
        End If

        // 22.4 [feedback], [shown]
        If [feedback] has any value
            Combine [feedback] with Custom "; "
            Set variable feedbackT to [Combined Text]
        Otherwise
            Text "none"
            Set variable feedbackT to [Text]
        End If
        If [shown] has any value
            Combine [shown] with Custom "; "
            Set variable shownT to [Combined Text]
        Otherwise
            Text "none"
            Set variable shownT to [Text]
        End If

        // 22.5 Подбор
        Use Private Cloud Compute model   (промпт «Новое» из README с [favorites], [disliked], [topGenres], [filtersT], [exclude], [shownT], [feedbackT], [genreList]; Output: Dictionary)
        Get Value for "items" in [Response]
        Set variable newItems to [Dictionary Value]
        If [newItems] does not have any value
            Use Private Cloud Compute model   (промпт «Новое» + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
            Replace "```json" with "" in [Response]
            Replace "```" with "" in [Updated Text]
            Get dictionary from [Updated Text]
            Get Value for "items" in [Dictionary]
            Set variable newItems to [Dictionary Value]
            If [newItems] does not have any value
                Show notification "ИИ не ответил"
                Text "menu"
                Set variable screen to [Text]
            End If
        End If

        // 22.6 Отбросить то, что уже в базе (ИИ может проигнорировать exclude)
        If [newItems] has any value
            Nothing
            Set variable listItems
            Repeat with each item in [newItems]
                Change [Repeat Item: title_en] to lowercase
                Text "|[Updated Text] [Repeat Item: year]|"
                If [dbTxt] does not contain [Text]
                    Add [Repeat Item] to listItems
                End If
            End Repeat
            If [listItems] does not have any value
                Show notification "Всё предложенное уже в базе"
                Text "menu"
                Set variable screen to [Text]
            End If
        End If
    End If

    // ── Шаг 23. Показ списка ──
    If [screen] is "list"
        Nothing
        Set variable labels
        Repeat with each item in [listItems]
            Text "[Repeat Item: title_ru] / [Repeat Item: title_en] ([Repeat Item: year])"
            Set variable label to [Text]

            // 23.1 Балл: predicted, иначе IMDb, иначе КП
            Nothing
            Set variable sc
            Get Value for "predicted" in [Repeat Item]
            If [Dictionary Value] has any value
                Set variable sc to [Dictionary Value]
            Otherwise
                // ratings может не быть — путь "ratings.imdb" тогда падает
                Get Value for "ratings" in [Repeat Item]
                Set variable rts to [Dictionary Value]
                If [rts] does not have any value
                    Dictionary   (пустой)
                    Set variable rts to [Dictionary]
                End If
                Get Value for "imdb" in [rts]
                If [Dictionary Value] has any value
                    Text "IMDb [Dictionary Value]"
                    Set variable sc to [Text]
                Otherwise
                    Get Value for "kp" in [rts]
                    If [Dictionary Value] has any value
                        Text "КП [Dictionary Value]"
                        Set variable sc to [Text]
                    End If
                End If
            End If
            If [sc] has any value
                Text "[label] — [sc]"
                Set variable label to [Text]
            End If

            // 23.2 Причина (mood / new)
            Get Value for "reason" in [Repeat Item]
            If [Dictionary Value] has any value
                Text "[label] · [Dictionary Value]"
                Set variable label to [Text]
            End If
            Add [label] to labels
        End Repeat

        // 23.3 Служебные пункты (в конце — индексы тайтлов не сдвигаются)
        If Any are true: [mode] is "mood", [mode] is "new"
            Text "↻ Не то…"
            Add [Text] to labels
        End If
        Text "← Назад"
        Add [Text] to labels

        // ── Шаг 24. Выбор ──
        Choose from [labels]   (Prompt: "Что смотрим?")
        Set variable chosen to [Chosen Item]

        // 24.1 ← Назад
        If [chosen] is "← Назад"
            Nothing
            Set variable listItems
            Nothing
            Set variable feedback
            Nothing
            Set variable shown
            Nothing
            Set variable mood
            Nothing
            Set variable answers
            Text "menu"
            Set variable screen to [Text]
        Otherwise

            // 24.2 ↻ Не то… → следующий оборот заново вызовет ИИ (тот же mood и answers)
            If [chosen] is "↻ Не то…"
                Ask for Text with "Что не так? (слишком старое, хочу смешнее…)"
                Add [Provided Input] to feedback
                Repeat with each item in [listItems]
                    Add [Repeat Item: title_en] to shown
                End Repeat
                Nothing
                Set variable listItems

            // 24.3 Тайтл → карточка
            Otherwise
                Repeat with each item in [labels]
                    If [Repeat Item] is [chosen]
                        Get Item at Index [Repeat Index] from [listItems]
                        Set variable selected to [Item from List]
                    End If
                End Repeat
                Text "card"
                Set variable screen to [Text]
            End If
        End If
    End If

    // ── Шаг 25. screen = card: вход для Movie Card ──
    If [screen] is "card"
        Get Value for "key" in [selected]
        Set variable selKey to [Dictionary Value]
        Get Value for "predicted" in [selected]
        Set variable selPred to [Dictionary Value]
        Nothing
        Set variable exists
        If [selKey] has any value
            Check if Value Exists for "movies.items.[selKey]" (Data Jar)
            Set variable exists to [Value Exists]
        End If
        If [exists] is True
            Dictionary
                key  Text  [selKey]
            Set variable cardIn to [Dictionary]
        Otherwise
            Dictionary
                saved  Boolean  False
            Set "record" to [selected] in [Dictionary]
            Set variable cardIn to [Dictionary]
        End If
        If [selPred] has any value
            Set "predicted" to [selPred] in [cardIn]
            Set variable cardIn to [Dictionary]
        End If

        // ── Шаг 26. Карточка ──
        Run Shortcut "Movie Card" with [cardIn]
        Set variable res to [Shortcut Result]
        Get Value for "action" in [res]
        Set variable resAct to [Dictionary Value]

        // ── Шаг 27. similar → список похожих ──
        If [resAct] is "similar"
            Get Value for "items" in [res]
            Set variable listItems to [Dictionary Value]
            Text "similar"
            Set variable mode to [Text]
            Text "list"
            Set variable screen to [Text]

        // ── Шаг 28. back или пусто (Card остановилась сама) ──
        Otherwise

            // 28.1 Перечитать базу (статус мог смениться)
            Get Value for "movies.items" (Data Jar)
            Get All Values in [Value]
            Set variable db to [Values]

            // 28.2 today → конец
            If [mode] is "today"
                Stop this shortcut
            End If

            // 28.3 random
            If [mode] is "random"
                Choose from Menu   (Prompt: "Дальше?")
                    • Ещё случайный
                        Nothing
                        Set variable listItems
                        Text "list"
                        Set variable screen to [Text]
                    • В меню
                        Text "menu"
                        Set variable screen to [Text]
                End Menu
            End If

            // 28.4 taste → пересчитать
            If [mode] is "taste"
                Nothing
                Set variable listItems
                Text "list"
                Set variable screen to [Text]
            End If

            // 28.5 mood / new / similar → кеш без смотренного / брошенного / скрытого
            If Any are true: [mode] is "mood", [mode] is "new", [mode] is "similar"
                Nothing
                Set variable seenT
                Nothing
                Set variable seenK
                Repeat with each item in [db]
                    If Any are true: [Repeat Item: status] is "watched", [Repeat Item: status] is "dropped", [Repeat Item: status] is "dismissed"
                        Change [Repeat Item: title_en] to lowercase
                        Text "[Updated Text] [Repeat Item: year]"
                        Add [Text] to seenT
                        Add [Repeat Item: key] to seenK
                    End If
                End Repeat
                Combine [seenT] with Custom "|"
                Text "|[Combined Text]|"
                Set variable seenTxt to [Text]
                Combine [seenK] with Custom "|"
                Text "|[Combined Text]|"
                Set variable seenKTxt to [Text]

                Nothing
                Set variable kept
                Repeat with each item in [listItems]
                    Number 1
                    Set variable keep to [Number]
                    Change [Repeat Item: title_en] to lowercase
                    Text "|[Updated Text] [Repeat Item: year]|"
                    If [seenTxt] contains [Text]
                        Number 0
                        Set variable keep to [Number]
                    End If
                    Get Value for "key" in [Repeat Item]
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
                Set variable listItems to [kept]
                Text "list"
                Set variable screen to [Text]
            End If
        End If
    End If

// ── Шаг 29 ──
End Repeat

// ── Шаг 30. 200 оборотов прошло ──
Stop this shortcut
```

## Movie Tonight

Отдельный шорткат для виджета на Home Screen. Всё остальное (скоринг, топ-10, случайный, карточка, «Список пуст — добавь через Movie Add») — в Movie Recommend, режим `today` (шаги 9, 15, 18, 25–28).

```
// ── Шаг 1. Режим ──
Text "today"

// ── Шаг 2. Recommend ──
Run Shortcut "Movie Recommend" with [Text]
```
