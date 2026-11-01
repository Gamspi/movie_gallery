plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "armeabi-v7a" }
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
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
