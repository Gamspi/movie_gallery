# Movie Rate — действия по порядку

Как в редакторе Shortcuts: одна строка = одно действие, отступ = внутри If / Otherwise / Repeat / Menu.
`[x]` — синяя плашка переменной `x`. `[x: key]` — плашка `x`, тап → Get Value for Key `key`.
Строки `// …` — пояснения, в шорткат их не добавлять.

Приёмы:

- **Сброс переменной**: `Nothing` → `Set variable x` (поле «to» пустое). Нужен для списков (`Add to Variable` копит между оборотами Repeat) — в начале каждого оборота.
- **Вложенный Repeat** внутри `Repeat 100`: iOS может назвать плашки `Repeat Item 2` / `Repeat Index 2` — бери плашку своего Repeat.
- **Выхода из Repeat нет**: внутренний цикл похожих гасится флагом `simDone` — оставшиеся обороты проходят вхолостую.
- **Возврата (goto) нет**: меню «Показать» — внутри `Repeat 100` под флагом `menuDone`. Пустой → оборот начинается с меню; «← К фильтрам» и «Пусто» сбрасывают флаг.

```
// ── Шаг 1. Настройки ──
Get Value for "movies.settings" (Data Jar)
Set variable settings to [Value]
If [settings] does not have any value
    Show notification "Нет movies.settings — см. README, Установка"
    Stop this shortcut
End If

// ── Шаг 2. Цикл: меню «Показать» → список → карточка ──
Nothing
Set variable menuDone
Repeat 100 times

    // 2.1 Фильтр (menuDone пуст — первый оборот, «← К фильтрам» или «Пусто»; Cancel = выход)
    If [menuDone] is not 1
        Choose from Menu   (Prompt: "Показать")
            • Несмотренные
                Text "unwatched"
                Set variable filter to [Text]
                Text "Несмотренные"
                Set variable filterT to [Text]
            • Смотрю
                Text "watching"
                Set variable filter to [Text]
                Text "Смотрю"
                Set variable filterT to [Text]
            • Посмотренные
                Text "watched"
                Set variable filter to [Text]
                Text "Посмотренные"
                Set variable filterT to [Text]
            • Бросил
                Text "dropped"
                Set variable filter to [Text]
                Text "Бросил"
                Set variable filterT to [Text]
            • Скрытые
                Text "dismissed"
                Set variable filter to [Text]
                Text "Скрытые"
                Set variable filterT to [Text]
            • Все
                Text "all"
                Set variable filter to [Text]
                Text "Все"
                Set variable filterT to [Text]
        End Menu
        Number 1
        Set variable menuDone to [Number]
    End If

    // 3.1 Сброс переменных оборота (база перечитывается — статус мог измениться)
    Nothing
    Set variable items
    Nothing
    Set variable labels
    Dictionary   (пустой)
    Set variable labelMap to [Dictionary]
    Nothing
    Set variable res

    // ── Шаг 4. Записи по фильтру ──
    Get Value for "movies.items" (Data Jar)
    Get All Values in [Value]
    Set variable all to [Values]
    Repeat with each item in [all]
        If [filter] is "all"
            If [Repeat Item: status] is not "dismissed"
                Add [Repeat Item] to items
            End If
        Otherwise
            If [Repeat Item: status] is [filter]
                Add [Repeat Item] to items
            End If
        End If
    End Repeat
    If [items] does not have any value
        Show notification "Пусто"
        Nothing
        Set variable menuDone
        // следующий оборот — снова меню «Показать»
    Otherwise

        // ── Шаг 5. Метки списка ──
        // без сортировки — порядок, как отдаёт Data Jar
        // первый пункт — возврат к меню; метка тайтла всегда с « / » — не совпадёт
        Text "← К фильтрам"
        Add [Text] to labels
        Repeat with each item in [items]
            Text "[Repeat Item: title_ru] / [Repeat Item: title_en]"
            Set variable label to [Text]
            Get Value for "year" in [Repeat Item]
            If [Dictionary Value] has any value
                Text "[label] ([Dictionary Value])"
                Set variable label to [Text]
            End If
            Get Value for "my_rating" in [Repeat Item]
            If [Dictionary Value] has any value
                Text "[label] ★ [Dictionary Value]"
                Set variable label to [Text]
            End If
            If All are true: [Repeat Item: status] is "watching", [filter] is "all"
                Text "[label] ▶"
                Set variable label to [Text]
            End If
            Get Value for [label] in [labelMap]
            If [Dictionary Value] has any value
                Text "[label] · [Repeat Item: key]"
                Set variable label to [Text]
            End If
            Add [label] to labels
            Set [label] to [Repeat Item: key] in [labelMap]
            Set variable labelMap to [Dictionary]
        End Repeat

        // ── Шаг 6. Выбор тайтла (Cancel = выход из шортката) ──
        Count Items in [items]
        Choose from [labels]   (Prompt: "[filterT]: [Count]")
        If [Chosen Item as Text] is "← К фильтрам"
            Nothing
            Set variable menuDone
            // следующий оборот — снова меню «Показать»
        Otherwise
            Get Value for [Chosen Item] in [labelMap]
            Set variable key to [Dictionary Value]

            // ── Шаг 7. Карточка ──
            Dictionary
                key  Text  [key]
            Run Shortcut "Movie Card" with [Dictionary]
            Set variable res to [Shortcut Result]
            // пусто (карточка остановилась сама) или back → следующий оборот

            // ── Шаг 8. Похожие ──
            Nothing
            Set variable simDone
            Repeat 20 times
                If [simDone] is not 1
                    Get Value for "action" in [res]
                    If [Dictionary Value] is "similar"
                        Nothing
                        Set variable simLabels
                        Dictionary   (пустой)
                        Set variable simMap to [Dictionary]

                        // 8.1 Метки похожих
                        Get Value for "items" in [res]
                        Repeat with each item in [Dictionary Value]
                            Text "[Repeat Item 2: title_ru] / [Repeat Item 2: title_en]"
                            Set variable sLabel to [Text]
                            Get Value for "year" in [Repeat Item 2]
                            If [Dictionary Value] has any value
                                Text "[sLabel] ([Dictionary Value])"
                                Set variable sLabel to [Text]
                            End If
                            // ratings может не быть — путь "ratings.imdb" тогда падает
                            Get Value for "ratings" in [Repeat Item 2]
                            Set variable rts to [Dictionary Value]
                            If [rts] does not have any value
                                Dictionary   (пустой)
                                Set variable rts to [Dictionary]
                            End If
                            Get Value for "imdb" in [rts]
                            If [Dictionary Value] has any value
                                Text "[sLabel] — IMDb [Dictionary Value]"
                                Set variable sLabel to [Text]
                            Otherwise
                                Get Value for "kp" in [rts]
                                If [Dictionary Value] has any value
                                    Text "[sLabel] — КП [Dictionary Value]"
                                    Set variable sLabel to [Text]
                                End If
                            End If
                            Get Value for [sLabel] in [simMap]
                            If [Dictionary Value] has any value
                                Text "[sLabel] · [Repeat Index 2]"
                                Set variable sLabel to [Text]
                            End If
                            Add [sLabel] to simLabels
                            Set [sLabel] to [Repeat Item 2] in [simMap]
                            Set variable simMap to [Dictionary]
                        End Repeat
                        Text "← Назад"
                        Add [Text] to simLabels

                        // 8.2 Выбор похожего
                        Choose from [simLabels]   (Prompt: "Похожие")
                        If [Chosen Item as Text] is "← Назад"
                            Number 1
                            Set variable simDone to [Number]
                        Otherwise
                            Get Value for [Chosen Item] in [simMap]
                            Set variable simRec to [Dictionary Value]
                            Dictionary
                                saved  Boolean  False
                            Set "record" to [simRec] in [Dictionary]
                            Run Shortcut "Movie Card" with [Dictionary]
                            Set variable res to [Shortcut Result]
                            // снова similar → следующий оборот покажет новые похожие; иначе выход
                        End If
                    Otherwise
                        Number 1
                        Set variable simDone to [Number]
                    End If
                End If
            End Repeat
        End If
    End If

// ── Шаг 9 ──
End Repeat
```
