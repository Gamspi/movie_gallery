#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="$ROOT/decoder-ffmpeg/build/generated/ffmpeg"
READELF="${ANDROID_HOME:?}/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
REPORT="$OUTPUT/elf-report.txt"
: > "$REPORT"
for name in avcodec avutil swresample ffmpegJNI; do
    info="$("$READELF" -h -d "$OUTPUT/jniLibs/armeabi-v7a/lib$name.so")"
    grep -Eq 'Class:[[:space:]]+ELF32' <<< "$info"
    grep -Eq 'Machine:[[:space:]]+ARM' <<< "$info"
    if grep -Eq '\((RPATH|RUNPATH)\)' <<< "$info"; then echo "Unexpected runtime path in $name" >&2; exit 1; fi
    printf '%s\n%s\n' "lib$name.so" "$info" >> "$REPORT"
done
info="$("$READELF" -d "$OUTPUT/jniLibs/armeabi-v7a/libffmpegJNI.so")"
for name in avcodec avutil swresample; do
    grep -F "Shared library: [lib$name.so]" <<< "$info" >/dev/null
done
grep -F '#define CONFIG_GPL 0' "$ROOT/decoder-ffmpeg/build/native/ffmpeg-6.0.1/config.h" >/dev/null
grep -F '#define CONFIG_NONFREE 0' "$ROOT/decoder-ffmpeg/build/native/ffmpeg-6.0.1/config.h" >/dev/null
echo 'Verified: four ELF32 ARM libraries, unversioned shared FFmpeg dependencies, no runtime path, GPL/nonfree disabled.'
