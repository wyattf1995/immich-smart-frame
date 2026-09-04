#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frameos_root="$repo_root/frameos"

if [[ ! -x "$frameos_root/gradlew" ]]; then
  printf 'missing executable FrameOS Gradle wrapper: %s\n' "$frameos_root/gradlew" >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  java_bin="$(command -v java)"
else
  printf 'FrameOS validation requires JDK 17\n' >&2
  exit 1
fi

java_version="$($java_bin -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ "$java_version" != "17" ]]; then
  printf 'FrameOS validation requires JDK 17, found Java %s via %s\n' \
    "${java_version:-unknown}" "$java_bin" >&2
  exit 1
fi

node --test "$frameos_root/app/src/test/js/photos-playback.test.js"

(
  cd "$frameos_root"
  ./gradlew --no-daemon --stacktrace \
    testDebugUnitTest lintDebug assembleDebug
)

printf 'FrameOS validation passed\n'
