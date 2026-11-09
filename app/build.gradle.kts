plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    // Exported schemas are checked in for migration review
    schemaDirectory("$projectDir/schemas")
}

// Local secrets from the root .env, baked into BuildConfig as fallback defaults.
// Read at configuration time via a tracked provider, so edits to .env trigger regeneration.
val envKeys = listOf(
    "KINOPOISK_API_KEY", "TMDB_API_KEY", "POISKKINO_API_KEY",
    "OPENSUBTITLES_API_KEY", "OPENSUBTITLES_USERNAME", "OPENSUBTITLES_PASSWORD",
    "PROXY_URL", "PROXY_KEY", "RUTRACKER_USERNAME", "RUTRACKER_PASSWORD",
    "LIST_URL", "LIST_KEY",
)

val envValues: Map<String, String> = providers
    .fileContents(rootProject.layout.projectDirectory.file(".env"))
    .asText.orNull.orEmpty()
    .removePrefix("﻿")
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
    .associate { line ->
        val key = line.substringBefore('=').trim()
        var value = line.substringAfter('=').trim()
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
            value = value.substring(1, value.length - 1)
        }
        key to value
    }

// Java string literal; non-ASCII and control chars as unicode escapes.
fun javaLiteral(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 || c.code > 0x7e -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

android {
    namespace = "dev.gamspi.videogallery"
    ndkVersion = "26.1.10909125"
    compileSdk = 37
    // pinned to the build-tools installed in the Docker image; AGP default (36.0.0) would be downloaded
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.gamspi.videogallery"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += "armeabi-v7a" }
        envKeys.forEach { key ->
            buildConfigField("String", "ENV_$key", javaLiteral(envValues[key].orEmpty()))
        }
    }

    buildTypes {
        // Fast non-debuggable build for manual TV checks; debug stays for unit tests
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as debug, so release installs over debug without losing data
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions.unitTests.all {
        // The Docker Gradle home is persistent; the container's ~/.m2 is not.
        it.systemProperty("maven.repo.local", "${gradle.gradleUserHomeDir}/caches/robolectric")
    }
}

dependencies {
    implementation(project(":decoder-ffmpeg"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
