FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS_VERSION=15859902
ARG ANDROID_PLATFORM=android-37.0
ARG BUILD_TOOLS_VERSION=37.0.0
ARG GRADLE_VERSION=9.8.2

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle
ENV PATH=${PATH}:${GRADLE_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip git \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && curl -fsSLo /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip \
 && unzip -q /tmp/cmdline.zip -d /tmp/cmdline \
 && mv /tmp/cmdline/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm -rf /tmp/cmdline /tmp/cmdline.zip \
 && (yes | sdkmanager --licenses >/dev/null) \
 && sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${BUILD_TOOLS_VERSION}"

RUN curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 --speed-limit 50000 --speed-time 30 -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv /opt/gradle-${GRADLE_VERSION} ${GRADLE_HOME} \
 && rm /tmp/gradle.zip

ARG NDK_VERSION=26.1.10909125
ARG CMAKE_VERSION=3.22.1
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential pkg-config xz-utils \
 && rm -rf /var/lib/apt/lists/* \
 && sdkmanager "ndk;${NDK_VERSION}" "cmake;${CMAKE_VERSION}"

WORKDIR /work
