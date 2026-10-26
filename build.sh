#!/bin/bash
# Пересборка шорткатов movies на Mac: shortcut.xml → подписанный .shortcut → импорт.
# movies/build.sh        — все
# movies/build.sh <имя>  — один шорткат (имя папки, например rate)
# Результат — movies/build/. Имя файла (= имя шортката при импорте) — из заголовка README.md, текст в скобках отрезается.
set -u
cd "$(dirname "$0")"
OUT=build
mkdir -p "$OUT"

# Суб-шорткаты — первыми: Run Shortcut ищет их по имени.
FIRST="lookup card recommend"
all=()
if [ -n "${1:-}" ]; then
    [ -f "$1/shortcut.xml" ] || { echo "Нет $1/shortcut.xml" >&2; exit 1; }
    all+=("$1/shortcut.xml")
else
    for xml in */shortcut.xml; do
        [ -f "$xml" ] && all+=("$xml")
    done
fi
ordered=()
for sub in $FIRST; do
    for xml in "${all[@]}"; do
        [ "$(dirname "$xml")" = "$sub" ] && ordered+=("$xml")
    done
done
for xml in "${all[@]}"; do
    [[ " $FIRST " == *" $(dirname "$xml") "* ]] || ordered+=("$xml")
done

failed=()
for xml in "${ordered[@]}"; do
    dir=$(dirname "$xml")
    name=$(head -1 "$dir/README.md" | sed -E 's/^# *//; s/ *\(.*\)//')
    [ -n "$name" ] || name=$dir
    echo "→ $dir → $name"
    if plutil -convert binary1 "$xml" -o "$OUT/unsigned.shortcut" &&
       shortcuts sign --mode anyone --input "$OUT/unsigned.shortcut" --output "$OUT/$name.shortcut"; then
        open "$OUT/$name.shortcut"
    else
        failed+=("$dir")
    fi
done
rm -f "$OUT/unsigned.shortcut"
# CLI не умеет класть в папку «Команд» — только вручную.
echo "Папки: в «Командах» перетащи новые шорткаты в папку Movies."

if [ ${#failed[@]} -gt 0 ]; then
    echo "Ошибки: ${failed[*]}" >&2
    exit 1
fi
