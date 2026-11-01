#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOWNLOADS="$ROOT/build/ffmpeg-downloads"
OUTPUT="$ROOT/decoder-ffmpeg/build/generated/ffmpeg"
WORK="$ROOT/decoder-ffmpeg/build/native"
NDK="${ANDROID_HOME:?}/ndk/26.1.10909125"
CMAKE="${ANDROID_HOME}/cmake/3.22.1/bin/cmake"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
JAVA_DEST="$OUTPUT/java/androidx/media3/decoder/ffmpeg"
mkdir -p "$DOWNLOADS/media3"
BASE=https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/decoder_ffmpeg
fetch() {
    local relative="$1" url="$2"
    if [[ ! -f "$DOWNLOADS/$relative" ]]; then
        curl -fsSL --retry 3 --connect-timeout 30 "$url" -o "$DOWNLOADS/$relative.part"
        mv "$DOWNLOADS/$relative.part" "$DOWNLOADS/$relative"
    fi
}
for file in FfmpegAudioDecoder.java FfmpegAudioRenderer.java FfmpegDecoderException.java FfmpegLibrary.java; do
    fetch "media3/$file" "$BASE/src/main/java/androidx/media3/decoder/ffmpeg/$file"
done
for file in ffmpeg_jni.cc CMakeLists.txt; do fetch "media3/$file" "$BASE/src/main/jni/$file"; done
fetch media3/LICENSE https://raw.githubusercontent.com/androidx/media/1.11.1/LICENSE
fetch media3/README.md "$BASE/README.md"
fetch ffmpeg-6.0.1.tar.xz https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz
(cd "$DOWNLOADS" && sha256sum -c "$ROOT/decoder-ffmpeg/sources.sha256")
test -x "$CMAKE" && test -x "$TOOLCHAIN/armv7a-linux-androideabi26-clang" || {
    echo 'Rebuild the Docker image: NDK r26b and CMake 3.22.1 are required.' >&2; exit 1;
}
mkdir -p "$WORK" "$JAVA_DEST" "$OUTPUT/jniLibs/armeabi-v7a" "$OUTPUT/assets/licenses"
cp "$DOWNLOADS/media3/ffmpeg_jni.cc" "$WORK/ffmpeg_jni.cc"
patch --batch --fuzz=0 -p1 -d "$WORK" < "$ROOT/decoder-ffmpeg/truehd-layout.patch"
if [[ ! -f "$WORK/ffmpeg-6.0.1/configure" ]]; then tar -xJf "$DOWNLOADS/ffmpeg-6.0.1.tar.xz" -C "$WORK"; fi
PREFIX="$WORK/install"
cd "$WORK/ffmpeg-6.0.1"
./configure --prefix="$PREFIX" --target-os=android --arch=arm --cpu=armv7-a \
    --enable-cross-compile --cc="$TOOLCHAIN/armv7a-linux-androideabi26-clang" \
    --cxx="$TOOLCHAIN/armv7a-linux-androideabi26-clang++" --ar="$TOOLCHAIN/llvm-ar" \
    --nm="$TOOLCHAIN/llvm-nm" --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip" \
    --enable-shared --disable-static --enable-pic --disable-asm --disable-autodetect \
    --disable-everything --disable-programs --disable-doc --disable-network --disable-debug --enable-small \
    --disable-avformat --disable-avdevice --disable-avfilter --disable-swscale --disable-postproc \
    --enable-avcodec --enable-avutil --enable-swresample \
    --enable-decoder=aac,ac3,eac3,dca,truehd --disable-gpl --disable-nonfree \
    --extra-cflags='-O2 -fPIC -march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16'
make -j"${FFMPEG_JOBS:-4}"
make install
"$CMAKE" -S "$ROOT/decoder-ffmpeg" -B "$WORK/jni" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ANDROID_HOME}/cmake/3.22.1/bin/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DFFMPEG_PREFIX="$PREFIX" \
    -DFFMPEG_JNI_SOURCE="$WORK/ffmpeg_jni.cc"
"$CMAKE" --build "$WORK/jni" --parallel 4
cp "$DOWNLOADS"/media3/*.java "$JAVA_DEST/"
cp "$PREFIX"/lib/*.so "$OUTPUT/jniLibs/armeabi-v7a/"
cp "$WORK/jni/libffmpegJNI.so" "$OUTPUT/jniLibs/armeabi-v7a/"
cp COPYING.LGPLv2.1 "$OUTPUT/assets/licenses/FFmpeg-LGPL-2.1.txt"
cp "$DOWNLOADS/media3/LICENSE" "$OUTPUT/assets/licenses/Media3-Apache-2.0.txt"
cp "$ROOT/decoder-ffmpeg/NOTICE.md" "$OUTPUT/assets/licenses/FFmpeg-NOTICE.md"
cp "$ROOT/decoder-ffmpeg/sources.sha256" "$OUTPUT/assets/licenses/FFmpeg-sources.sha256"
cp "$ROOT/decoder-ffmpeg/truehd-layout.patch" "$OUTPUT/assets/licenses/Media3-TrueHD-layout.patch"
echo 'FFmpeg 6.0.1 (LGPL), Media3 1.11.1, armeabi-v7a, AAC/AC3/EAC3/DTS/TrueHD' > "$OUTPUT/build-info.txt"
bash "$ROOT/scripts/verify-ffmpeg.sh"
