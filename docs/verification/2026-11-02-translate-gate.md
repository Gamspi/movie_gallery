# Gate этапа 4: ML Kit Translate на ТВ (2026-11-02)

Статус gate: **не выполнено** (проверка на ТВ не проводилась).

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

- Commit, модель/ABI/API ТВ: не выполнено
- download (en, ru) без смены VPN: не выполнено
- status показывает обе модели: не выполнено
- translate офлайн (сеть выключена): не выполнено
- Время фразы en→ru / ru→en, среднее (порог ≤ 300 мс): не выполнено
- Параллельное 1080p-видео, рывки: не выполнено
- Итог gate: не выполнено
