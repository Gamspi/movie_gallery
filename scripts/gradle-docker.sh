#!/usr/bin/env bash
# Usage: scripts/gradle-docker.sh <command...>   e.g. ./gradlew testDebugUnitTest
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG=videogallery-build
if ! docker image inspect "$TAG" >/dev/null 2>&1; then
  docker build -t "$TAG" "$ROOT/docker"
fi
# host path for docker (Windows-style under Git Bash)
if command -v cygpath >/dev/null 2>&1; then ROOT="$(cygpath -m "$ROOT")"; fi
# videogallery-android-home keeps debug.keystore stable, so debug APKs update in place on the TV
exec docker run --rm -v "$ROOT:/work" -v videogallery-gradle-cache:/root/.gradle -v videogallery-android-home:/root/.android -w /work "$TAG" "$@"
