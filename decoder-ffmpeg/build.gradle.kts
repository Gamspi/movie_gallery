plugins {
    alias(libs.plugins.android.library)
}
check(libs.versions.media3.get() == "1.11.1") { "Update pinned FFmpeg renderer sources with Media3" }

val generated = layout.buildDirectory.dir("generated/ffmpeg")
val prepareFfmpeg = tasks.register<Exec>("prepareFfmpeg") {
    inputs.file(rootProject.file("scripts/build-ffmpeg.sh"))
    inputs.file(rootProject.file("scripts/verify-ffmpeg.sh"))
    inputs.file(rootProject.file("decoder-ffmpeg/sources.sha256"))
    inputs.file(rootProject.file("decoder-ffmpeg/CMakeLists.txt"))
    inputs.file(rootProject.file("decoder-ffmpeg/NOTICE.md"))
    inputs.file(rootProject.file("decoder-ffmpeg/truehd-layout.patch"))
    outputs.dir(generated)
    commandLine("bash", rootProject.file("scripts/build-ffmpeg.sh").absolutePath)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    ndkVersion = "26.1.10909125"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "armeabi-v7a" }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            java.directories.add(generated.get().dir("java").asFile.absolutePath)
            jniLibs.directories.add(generated.get().dir("jniLibs").asFile.absolutePath)
            assets.directories.add(generated.get().dir("assets").asFile.absolutePath)
        }
    }
}

tasks.named("preBuild") { dependsOn(prepareFfmpeg) }

dependencies {
    api("androidx.media3:media3-decoder:${libs.versions.media3.get()}")
    implementation(libs.media3.exoplayer)
    compileOnly("androidx.annotation:annotation:1.6.0")
    compileOnly("org.checkerframework:checker-qual:3.13.0")
}
