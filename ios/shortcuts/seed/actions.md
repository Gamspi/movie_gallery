# Movie Seed — действия по порядку

Временный тестовый шорткат — см. [README](README.md).

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat / Menu.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If

// ── Шаг 2. Меню ──
Choose from Menu   (Prompt: "Тестовые данные")
    • Наполнить
        // записи — JSON {"items":[…]}, полный текст — в shortcut.xml / таблица в README
        Text "{"items":[ … 18 записей … ]}"
        Get Dictionary from Input [Text]
        Get Value for "items" in [Dictionary]
        Set variable seed to [Dictionary Value]
        Repeat with each item in [seed]
            Get Value for "key" in [Repeat Item]
            Set variable k to [Dictionary Value]
            Check if Value Exists "movies.items.[k]" (Data Jar)
            If [Value Exists] is true
                // живая запись или прошлый прогон — не перезаписывать
                Add [k] to skipped
            Otherwise
                Set Value [Repeat Item] for "movies.items.[k]" (Data Jar)
                Add [k] to added
            End If
        End Repeat
        // seed_keys = старый список + added; пустое в Data Jar не писать
        If [added] has any value
            Check if Value Exists "movies.seed_keys" (Data Jar)
            If [Value Exists] is true
                Get Value for "movies.seed_keys" (Data Jar)
                Repeat with each item in [Value]
                    Add [Repeat Item] to allKeys
                End Repeat
            End If
            Repeat with each item in [added]
                Add [Repeat Item] to allKeys
            End Repeat
            Set Value [allKeys] for "movies.seed_keys" (Data Jar)
        End If
        Count Items in [added]
        Set variable nAdded to [Count]
        Count Items in [skipped]
        Show notification "Добавлено: [nAdded], пропущено: [Count]"
    • Удалить тестовые
        // только ключи из movies.seed_keys
        Check if Value Exists "movies.seed_keys" (Data Jar)
        If [Value Exists] is true
            Get Value for "movies.seed_keys" (Data Jar)
            Repeat with each item in [Value]
                Text "[Repeat Item]"
                Set variable k to [Text]
                Check if Value Exists "movies.items.[k]" (Data Jar)
                If [Value Exists] is true
                    Delete Value "movies.items.[k]" (Data Jar)
                    Add [k] to deleted
                End If
            End Repeat
            Delete Value "movies.seed_keys" (Data Jar)
            Count Items in [deleted]
            Show notification "Удалено: [Count]"
        Otherwise
            Show notification "Тестовых записей нет"
        End If
End Menu
```
