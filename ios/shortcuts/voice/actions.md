# Movies (голосовой вход) — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для `Add to Variable` и для ветки без ИИ, где `mood` / `types` должны быть пусты.
- **Ответ — только Stop and Output**: всё, что произносит Siri, — по-английски и через `Stop and output`. Уведомления в голосовом пути не читаются, экранных действий (Show Web Page, Quick Look, Follow Up, Add New Event, Share) нет.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Text "Movies settings are missing. Open the Shortcuts app."
    Stop and output [Text]
End If

// ── Шаг 2. ИИ и метки типов ──
Get Value for "ai_enabled" in [settings]
Set variable aiOn to [Dictionary Value]
// не задан → пусто → проверка «is False» не сработает = ИИ включён
Get Value for "type_labels_en" in [settings]
Set variable typeLabels to [Dictionary Value]

// ── Шаг 3. Фраза ──
If [Shortcut Input] has any value
    Set variable phrase to [Shortcut Input]
Otherwise
    Ask for Text with "What do you want? Add a title, or ask what to watch."
    Set variable phrase to [Provided Input]
End If

// ── Шаг 4. Намерение ──
Nothing
Set variable intent
Nothing
Set variable title
Nothing
Set variable mood
Nothing
Set variable types

// 4.1 Без ИИ — по шаблону «add …»
If [aiOn] is False
    Match "^add\s+(.+)" in [phrase]   (Case Sensitive: off)
    If [Matches] has any value
        Get Group at Index 1 in [Matches]
        Set variable title to [Text]
        Text "add"
        Set variable intent to [Text]
    Otherwise
        Text "recommend"
        Set variable intent to [Text]
    End If

// 4.2 С ИИ
Otherwise
    Use Private Cloud Compute model   (промпт «Намерение» из README с [phrase]; Output: Dictionary)
    Set variable ai to [Response]
    Get Value for "intent" in [ai]
    Set variable intent to [Dictionary Value]
    If [intent] does not have any value
        Use Private Cloud Compute model   (промпт «Намерение» + "Return ONLY valid JSON, no markdown, no explanations.", Output: Text)
        Replace "```json" with "" in [Response]
        Replace "```" with "" in [Updated Text]
        Get dictionary from [Updated Text]
        Set variable ai to [Dictionary]
        Get Value for "intent" in [ai]
        Set variable intent to [Dictionary Value]
        If [intent] does not have any value
            Text "Sorry, I didn't get that."
            Stop and output [Text]
        End If
    End If
    Get Value for "title" in [ai]
    Set variable title to [Dictionary Value]
    Get Value for "mood" in [ai]
    Set variable mood to [Dictionary Value]
    Get Value for "types" in [ai]
    Set variable types to [Dictionary Value]
End If

// ── Шаг 5. intent = add: поиск ──
If [intent] is "add"
    Replace "[.!?]+$" with "" in [title]   (Regular Expression: on)
    Set variable title to [Updated Text]
    If [title] does not have any value
        Text "Say add and a title, or ask what to watch."
        Stop and output [Text]
    End If
    Dictionary
        query  Text     [title]
        voice  Boolean  True
    Run Shortcut "Movie Lookup" with [Dictionary]
    Set variable rec to [Shortcut Result]
    If [rec] does not have any value
        Text "Nothing added. Check the phone for details."
        Stop and output [Text]
    End If
    Get Value for "key" in [rec]
    Set variable key to [Dictionary Value]

    // ── Шаг 6. Дубль по ключу ──
    Nothing
    Set variable dupKey
    Check if Value Exists for "movies.items.[key]" (Data Jar)
    If [Value Exists] is True
        Set variable dupKey to [key]
    End If

    // ── Шаг 7. Дубль по названию и году ──
    If [dupKey] does not have any value
        Change [rec: title_en] to lowercase
        Text "[Updated Text] [rec: year]"
        Set variable recSig to [Text]
        Get Value for "movies.items" (Data Jar)
        Get All Values in [Value]
        Repeat with each item in [Values]
            Change [Repeat Item: title_en] to lowercase
            Text "[Updated Text] [Repeat Item: year]"
            If [Text] is [recSig]
                Set variable dupKey to [Repeat Item: key]
            End If
        End Repeat
    End If

    // ── Шаг 8. Уже в списке ──
    If [dupKey] has any value
        Text "Already in your list: [rec: title_en] ([rec: year])."
        Stop and output [Text]
    End If

    // ── Шаг 9. Пользовательские поля и запись ──
    Date   (Current Date)
    Format [Date] with ISO 8601
    Set variable now to [Formatted Date]
    Text "unwatched"
    Set "status" to [Text] in [rec]
    Set variable rec to [Dictionary]
    Text "manual"
    Set "origin" to [Text] in [rec]
    Set variable rec to [Dictionary]
    Set "added_date" to [now] in [rec]
    Set variable rec to [Dictionary]
    Set "updated_date" to [now] in [rec]
    Set variable rec to [Dictionary]
    // tags / note не ставим: пустое значение Set Dictionary Value не принимает
    Set Value [rec] for "movies.items.[key]" (Data Jar)
    // новая запись → облако (без сети — в очередь); забор изменений голос не делает
    Dictionary
        key  Text  [key]
    Run Shortcut "Movie Sync" with [Dictionary]

    // ── Шаг 10. Ответ ──
    // 10.1 Название, год, тип
    Nothing
    Set variable parts
    Add [rec: title_en] to parts
    Get Value for "year" in [rec]
    If [Dictionary Value] has any value
        Add [Dictionary Value] to parts
    End If
    Get Value for "type" in [rec]
    Get Value for [Dictionary Value] in [typeLabels]
    If [Dictionary Value] has any value
        Add [Dictionary Value] to parts
    End If
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
    Combine [parts] with Custom ", "
    Text "Added: [Combined Text]."
    Set variable answer to [Text]

    // 10.2 Источник ИИ
    Get Value for "source" in [rec]
    If [Dictionary Value] is "ai"
        Text "[answer] Not found on Kinopoisk, saved from AI data."
        Set variable answer to [Text]
    End If

    // 10.3 Премьера в будущем
    Get Value for "premiere_date" in [rec]
    Set variable pd to [Dictionary Value]
    If [pd] has any value
        Get dates from [pd]
        If [Dates] is after [Current Date]
            Text "[answer] Premieres on [pd]."
            Set variable answer to [Text]
        End If
    End If

    Stop and output [answer]
End If

// ── Шаг 11. intent = recommend ──
If [intent] is "recommend"
    Dictionary
        mode  Text  voice
        mood  Text  [mood]
    Set variable recIn to [Dictionary]
    If [types] has any value
        Set "types" to [types] in [recIn]
        Set variable recIn to [Dictionary]
    End If
    Run Shortcut "Movie Recommend" with [recIn]
    If [Shortcut Result] has any value
        Stop and output [Shortcut Result]
    End If
    Text "No recommendations. Check the phone for details."
    Stop and output [Text]
End If

// ── Шаг 12. intent = unknown (или любое другое значение) ──
Text "Say add and a title, or ask what to watch."
Stop and output [Text]
```
