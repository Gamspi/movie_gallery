# Gate этапа 4: ML Kit Translate на ТВ (2026-11-02)

Статус gate: **пройден условно** 2026-11-02: загрузка и скорость подтверждены на ТВ; офлайн-перевод и нагрузка с видео перенесены в приёмку задачи 7.

## Сборка и APK (проверено)

- Зависимость: `com.google.mlkit:translate:17.0.3` (bundled-вариант, без Play Services). Версия — `release`/`latest` в `maven-metadata.xml` на `dl.google.com/dl/android/maven2/com/google/mlkit/translate/` (lastUpdated 2024-08-07); других версий новее нет.
- `./scripts/gradle-docker.sh ./gradlew assembleDebug` — BUILD SUCCESSFUL (3m 25s).
- Базовый commit без зависимости: 8ed4124. APK до: 34 457 531 байт; после: 47 367 695 байт; прирост 12 910 164 байт (~12,3 МиБ).
- `lib/` в APK: только `lib/armeabi-v7a/`. Прирост от ML Kit — `libtranslate_jni.so` (11 608 796 байт). Остальные: libtorrent4j.so, libavcodec/avutil/swresample, libffmpegJNI, libandroidx.graphics.path. Каталогов arm64/x86 нет, ABI не изменён.
- Модели переводчика в APK не входят: скачиваются в рантайме через `RemoteModelManager`.

## Прогресс загрузки (по документации)

`RemoteModelManager.download(model, DownloadConditions)` на Android возвращает `Task<Void>` и сообщает только об успехе/ошибке; колбэка прогресса нет (в отличие от iOS, где возвращается `Progress`). Источник: developers.google.com/ml-kit (Managing model downloads, Android: `download(...).addOnSuccessListener / addOnFailureListener`; страница Swift ModelManager.download возвращает Progress). Следствие: в UI будет «Загрузка модели…» без процента. Нужно отразить в спеке; на устройстве поведение не перепроверялось.

## Probe

`TranslateProbeActivity` (debug), тег лога `TranslateProbe`:

```
adb shell am start -n dev.gamspi.videogallery/.translate.TranslateProbeActivity --es command download
adb shell am start -n dev.gamspi.videogallery/.translate.TranslateProbeActivity --es command status
adb shell am start -n dev.gamspi.videogallery/.translate.TranslateProbeActivity --es command translate
adb shell am start -n dev.gamspi.videogallery/.translate.TranslateProbeActivity --es command delete
adb logcat -s TranslateProbe
```

## Результаты на ТВ

- Commit `c6891c8`, APK 47 367 695 байт; Xiaomi MiTV-MOEU1, ABI `armeabi-v7a`, API 34, Wi-Fi, ByeDPI-VPN активен, приложение исключено из него (настройки VPN не менялись).
- download (en, ru) без смены VPN: **да**. en встроена (9 мс), ru скачана за 18,9 с.
- status показывает обе модели: **да**, `[en, ru]`.
- translate офлайн: **не проверено**. Отключение Wi-Fi через `svc wifi disable` обрывает adb и не восстановилось автоматически (Wi-Fi и сетевую отладку пришлось включать вручную); `cmd connectivity set-chain3-enabled` на этом ТВ отсутствует; отключить WAN нельзя — у пользователя единственный выход в интернет. Решение пользователя: пропустить. Команда `translate` не вызывает загрузку; перевод ML Kit по документации выполняется на устройстве.
- Время фразы (20 фраз, после прогрева): en→ru среднее **117 мс** (макс. 162), ru→en среднее **105 мс** (макс. 147). Порог ≤ 300 мс выполнен. Прогрев первого вызова: en→ru 2846 мс, ru→en 612 мс — UI должен показывать «Перевод…» до первого результата.
- Параллельное 1080p-видео, рывки: **не проверено** — probe отдельная activity; перенесено в приёмку задачи 7 на реальном плеере.
- Итог gate: **пройден условно**; этап 4 продолжается. Открыто: офлайн-перевод и нагрузка при воспроизведении (задача 7).
