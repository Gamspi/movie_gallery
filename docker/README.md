# Docker-сборка (JDK 17 + Android SDK + Gradle)

Образ собирается автоматически при первом запуске скрипта (тег `videogallery-build`).

- Сборка и тесты: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug` (PowerShell: `scripts/gradle-docker.ps1 ./gradlew ...`).
- Любая команда в контейнере: `scripts/gradle-docker.sh gradle wrapper`, `scripts/gradle-docker.sh sdkmanager --list_installed`.
- Кэш Gradle лежит в томе `videogallery-gradle-cache`.
- Debug keystore сохраняется в томе `videogallery-android-home`, чтобы APK можно было обновлять поверх установленного. Не удаляйте том для обычной пересборки.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Установка с хоста: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Требуется запущенный Docker с Linux-контейнерами. SDK/JDK на хосте для сборки не нужны; ADB для установки вызывается отдельно с хоста. Ошибка доступа к Docker pipe означает, что сначала нужно восстановить доступ к Docker, а не переключаться на случайный локальный SDK.

Образ автоматически создаётся только при отсутствии тега. После изменения `Dockerfile` пересоберите его явно:

```text
docker build -t videogallery-build docker
```

Текущая конфигурация: JDK 17, Gradle 9.8.2, Android SDK 37, build-tools 37.0.0. Источники значений — `Dockerfile`, `gradle/wrapper/gradle-wrapper.properties` и `app/build.gradle.kts`; обновляйте их согласованно.

Для локального модуля `decoder-ffmpeg` образ также устанавливает NDK `26.1.10909125` (r26b), CMake `3.22.1` и C/C++ build tools. После обновления Dockerfile требуется явная пересборка образа командой выше. Первый `preBuild` проверяет SHA256 исходников, собирает FFmpeg и JNI; последующие запуски используют результаты Gradle. Очистка `decoder-ffmpeg/build` потребует повторной нативной сборки, скачанные исходники сохраняются в корневом `build/ffmpeg-downloads`.

Нативные выходы и лицензии: `decoder-ffmpeg/build/generated/ffmpeg`. Состав, ссылки на исходники и порядок замены shared-библиотек — [NOTICE](../decoder-ffmpeg/NOTICE.md). При изменении версии Media3 также нужно обновить закреплённые исходники расширения.

Текущий APK содержит JNI только для `armeabi-v7a`. Результаты тестов: `app/build/test-results/testDebugUnitTest/`; подробные команды, ограничения и приёмка — в [TESTING](../docs/TESTING.md).
