# Docker-сборка (JDK 17 + Android SDK + Gradle)

Образ собирается автоматически при первом запуске скрипта (тег `videogallery-build`).

- Сборка и тесты: `scripts/gradle-docker.sh ./gradlew testDebugUnitTest assembleDebug` (PowerShell: `scripts/gradle-docker.ps1 ./gradlew ...`).
- Любая команда в контейнере: `scripts/gradle-docker.sh gradle wrapper`, `scripts/gradle-docker.sh sdkmanager --list_installed`.
- Кэш Gradle лежит в томе `videogallery-gradle-cache`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Установка с хоста: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
