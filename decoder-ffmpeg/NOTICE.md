# Audio decoder sources and rebuilding

This application includes the AndroidX Media3 FFmpeg audio renderer, version 1.11.1
(Apache License 2.0), and FFmpeg 6.0.1 (LGPL version 2.1 or later).
FFmpeg is built without GPL or nonfree components. The enabled audio decoders are
AAC, AC3, EAC3, DTS (dca), and TrueHD. FFmpeg video decoders are not enabled.

Official sources:

- https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg
- https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz

The exact downloaded files are listed in `sources.sha256`. The build downloads and
checks them before compilation. See `scripts/build-ffmpeg.sh` for all configuration
flags and `decoder-ffmpeg/CMakeLists.txt` for the JNI link recipe. The FFmpeg source
itself is unmodified. The local CMake recipe links the JNI bridge to separate shared
libavcodec, libavutil and libswresample libraries instead of upstream static archives.

The Media3 JNI bridge is modified by `truehd-layout.patch` for the stereo TrueHD
regression reported at https://github.com/androidx/media/issues/1843. It initializes
the known sample rate and channel layout for TrueHD and preserves them when the
existing TrueHD seek workaround recreates the decoder. Unknown or invalid values
are not used to initialize the layout. The original downloaded source and checksum
remain unchanged; the build applies the tracked patch to a fresh working copy.
The patch is bundled in APK license assets and is part of the corresponding source.

To rebuild, run `docker build -t videogallery-build docker`, then
`scripts/gradle-docker.sh ./gradlew assembleDebug` (or the PowerShell equivalent).
Complete downloaded sources remain in `build/ffmpeg-downloads`; extracted FFmpeg,
configuration, object files and intermediate libraries remain in
`decoder-ffmpeg/build/native`. Provide these exact sources and build recipes together
with binaries when redistributing them. License texts are bundled in APK assets under
`licenses/`. This notice is not a substitute for those license terms.

To modify or replace the LGPL libraries, use the extracted source, rebuild with the
same ABI/API and shared-library SONAMEs, and rebuild the APK with the resulting `.so`
files in `decoder-ffmpeg/build/generated/ffmpeg/jniLibs/armeabi-v7a`. Run the Gradle
command with `-x :decoder-ffmpeg:prepareFfmpeg` to retain manually replaced libraries.
Sign and install the modified APK using your own signing key; changing the signer
requires uninstalling the old app (which removes its private data). No restriction
on reverse engineering for debugging modifications to these libraries is imposed
by this project. Hardware capabilities and successful compilation do not guarantee
support for any particular media stream.
