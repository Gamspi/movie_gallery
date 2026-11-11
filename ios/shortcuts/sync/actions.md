# Movie Sync — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`. `(Boolean)` / `(Text)` — в плашке Type.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Нет try/catch**: без сети Get Contents of URL роняет шорткат и вызывающего. Поэтому правка сначала пишется в очередь, а запрос идёт только при непустом локальном IP.
- **Тело POST — текст**: словарь со списком из одной строки Shortcuts кладёт не массивом, воркеру нужен массив `titles`.
- **Вложенный Repeat** внутри `Repeat 20`: плашки `Repeat Item 2`.

```
// ── Шаг 1. Настройки (молча: вызывающий сам проверяет настройки) ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Stop this shortcut
End If

// ── Шаг 2. Режим ──
Text "auto"
Set variable mode to [Text]
Get Type of [Shortcut Input]
If [Type] is "Text"
    If [Shortcut Input (Text)] is "now"
        Text "now"
        Set variable mode to [Text]
    End If
End If
If [Type] is "Dictionary"
    Text "push"
    Set variable mode to [Text]
End If

// ── Шаг 3. Правка → очередь (до любых запросов) ──
If [mode] is "push"
    Get Value for "key" in [Shortcut Input]
    Set variable key to [Dictionary Value]
    If [key] does not have any value
        Stop this shortcut
    End If
    // время правки — мс от 1970-01-01 UTC
    Text "1970-01-01T00:00:00Z"
    Get dates from [Text]
    Date   (Current Date)
    Get time between [Dates] and [Date] in Seconds
    Calculate [Time Between Dates] × 1000
    Round [Calculation Result] to Ones Place
    Set variable nowMs to [Rounded Number]
    Get Value for "deleted" in [Shortcut Input]
    If [Dictionary Value (Boolean)] is True
        // удаление: снимок записи передал вызывающий
        Get Value for "record" in [Shortcut Input]
        Set variable rec to [Dictionary Value]
        If [rec] does not have any value
            Dictionary
                key  Text  [key]
            Set variable rec to [Dictionary]
        End If
        Dictionary
            key         Text     [key]
            updated_at  Number   [nowMs]
            deleted     Boolean  True
        Set variable row to [Dictionary]
    Otherwise
        Check if Value Exists for "movies.items.[key]" (Data Jar)
        If [Value Exists] is False
            Stop this shortcut
        End If
        Get Value for "movies.items.[key]" (Data Jar)
        Set variable rec to [Value]
        Set Value [nowMs] for "movies.items.[key].updated_ms" (Data Jar)
        Set "updated_ms" to [nowMs] in [rec]
        Set variable rec to [Dictionary]
        Dictionary
            key         Text     [key]
            updated_at  Number   [nowMs]
            deleted     Boolean  False
        Set variable row to [Dictionary]
    End If
    Set "data" to [rec] in [row]
    Set variable row to [Dictionary]
    Set Value [row] for "movies.sync.queue.[key]" (Data Jar)
End If

// ── Шаг 4. Включено? Выключено — ни одного запроса ──
Number 0
Set variable on to [Number]
If [settings: sync_enabled (Boolean)] is True
    Number 1
    Set variable on to [Number]
End If
If [on] is not 1
    If [mode] is "now"
        Show notification "Синхронизация выключена: movies.settings.sync_enabled"
    End If
    Stop this shortcut
End If
Get Value for "sync_url" in [settings]
Set variable url to [Dictionary Value]
Get Value for "sync_key" in [settings]
Set variable skey to [Dictionary Value]
If Any are true: [url] does not have any value, [skey] does not have any value
    Show notification "Movie Sync: нет sync_url или sync_key в movies.settings"
    Stop this shortcut
End If

// ── Шаг 5. Сеть ──
Get Current IP Address   (Local, IPv4)
If [Current IP Address] does not have any value
    If [mode] is "now"
        Show notification "Нет сети — правки в очереди"
    End If
    Stop this shortcut
End If

// ── Шаг 6. Отправка очереди ──
Check if Value Exists for "movies.sync.queue" (Data Jar)
If [Value Exists] is True
    Get Value for "movies.sync.queue" (Data Jar)
    Get All Values in [Value]
    Set variable qRows to [Dictionary Value]
    Nothing
    Set variable parts
    Repeat with each item in [qRows]
        Text "[Repeat Item]"
        Add [Text] to parts
    End Repeat
    Combine [parts] with Custom ","
    Text "{"titles":[[Combined Text]]}"
    Set variable body to [Text]
    Text "[url]/v1/batch"
    Get contents of [Text]   (Method: POST, Headers: X-VG-List-Key = [skey], Request Body: File = [body])
    Set variable resp to [Contents of URL]
    Number 0
    Set variable ok to [Number]
    Get Type of [resp]
    If [Type] is "Dictionary"
        Get Value for "seq" in [resp]
        If [Dictionary Value] has any value
            Number 1
            Set variable ok to [Number]
        End If
    End If
    If [ok] is 1
        Delete Value for "movies.sync.queue" (Data Jar)
    Otherwise
        Show notification "Movie Sync: отправка не удалась — [resp]"
        Stop this shortcut
    End If
End If
If [mode] is "push"
    Stop this shortcut
End If

// ── Шаг 7. Раз в день ──
Date   (Current Date)
Format [Date] with Custom "yyyy-MM-dd"
Set variable today to [Formatted Date]
Dictionary   (пустой)
Set variable syncD to [Dictionary]
Check if Value Exists for "movies.sync" (Data Jar)
If [Value Exists] is True
    Get Value for "movies.sync" (Data Jar)
    Set variable syncD to [Value]
End If
If [mode] is "auto"
    If [syncD: last_date (Text)] is [today]
        Stop this shortcut
    End If
End If
Get Value for "cursor" in [syncD]
Set variable cursor to [Dictionary Value]
If [cursor] does not have any value
    Number 0
    Set variable cursor to [Number]
End If

// ── Шаг 8. Изменения с сервера ──
Number 0
Set variable nT to [Number]
Number 0
Set variable nE to [Number]
Nothing
Set variable done
Repeat 20 times
    If [done] is not 1
        Text "[url]/v1/changes?since=[cursor]&limit=500"
        Get contents of [Text]   (Method: GET, Headers: X-VG-List-Key = [skey])
        Set variable page to [Contents of URL]
        Number 0
        Set variable ok to [Number]
        Get Type of [page]
        If [Type] is "Dictionary"
            Get Value for "next" in [page]
            If [Dictionary Value] has any value
                Number 1
                Set variable ok to [Number]
            End If
        End If
        If [ok] is not 1
            Show notification "Movie Sync: изменения не получены — [page]"
            Stop this shortcut
        End If
        Get Value for "movies.items" (Data Jar)
        Set variable items to [Value]

        // 8.1 Тайтлы: LWW (updated_at сервера против updated_ms записи)
        Get Value for "titles" in [page]
        Repeat with each item in [Dictionary Value]
            Set variable row to [Repeat Item 2]
            Get Value for "key" in [row]
            Set variable rKey to [Dictionary Value]
            Get Value for "updated_at" in [row]
            Number [Dictionary Value]
            Set variable rT to [Number]
            Number 0
            Set variable lT to [Number]
            Get Value for [rKey] in [items]
            Set variable local to [Dictionary Value]
            If [local] has any value
                Get Value for "updated_ms" in [local]
                If [Dictionary Value] has any value
                    Number [Dictionary Value]
                    Set variable lT to [Number]
                End If
            End If
            If [rT] is greater than [lT]
                Get Value for "deleted" in [row]
                If [Dictionary Value (Boolean)] is True
                    If [local] has any value
                        Delete Value for "movies.items.[rKey]" (Data Jar)
                        Calculate [nT] + 1
                        Set variable nT to [Calculation Result]
                    End If
                Otherwise
                    Get Value for "data" in [row]
                    Set "updated_ms" to [rT] in [Dictionary Value]
                    Set "key" to [rKey] in [Dictionary]
                    Set Value [Dictionary] for "movies.items.[rKey]" (Data Jar)
                    Calculate [nT] + 1
                    Set variable nT to [Calculation Result]
                End If
            End If
        End Repeat

        // 8.2 Серии → movies.episodes.<key>.c<сезон·10000+серия> = "S2E5"
        Get Value for "episodes" in [page]
        Repeat with each item in [Dictionary Value]
            Set variable ep to [Repeat Item 2]
            Get Value for "title_key" in [ep]
            Set variable tKey to [Dictionary Value]
            Calculate Expression "[ep: season] * 10000 + [ep: episode]"
            Set variable code to [Calculation Result]
            Number 0
            Set variable keep to [Number]
            If [ep: watched (Boolean)] is True
                Number 1
                Set variable keep to [Number]
            End If
            If [ep: deleted (Boolean)] is True
                Number 0
                Set variable keep to [Number]
            End If
            If [keep] is 1
                Text "S[ep: season]E[ep: episode]"
                Set Value [Text] for "movies.episodes.[tKey].c[code]" (Data Jar)
            Otherwise
                Check if Value Exists for "movies.episodes.[tKey].c[code]" (Data Jar)
                If [Value Exists] is True
                    Delete Value for "movies.episodes.[tKey].c[code]" (Data Jar)
                End If
            End If
            Calculate [nE] + 1
            Set variable nE to [Calculation Result]
        End Repeat

        // 8.3 Курсор — next этой страницы
        Get Value for "next" in [page]
        Set Value [Dictionary Value] for "movies.sync.cursor" (Data Jar)
        Set variable cursor to [Dictionary Value]
        Get Value for "more" in [page]
        If [Dictionary Value (Boolean)] is True
        Otherwise
            Number 1
            Set variable done to [Number]
        End If
    End If
End Repeat

// ── Шаг 9. Готово ──
If [done] is 1
    Set Value [today] for "movies.sync.last_date" (Data Jar)
End If
If [mode] is "now"
    Show notification "Синхронизировано: тайтлов [nT], отметок серий [nE]"
End If
```
