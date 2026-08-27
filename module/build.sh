#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

MODULE_DIR="$(pwd)"
BUILD_TMP="${MODULE_DIR}/.build-tmp"
mkdir -p "${BUILD_TMP}/xdg-data"

export JAVA_HOME="${MODULE_DIR}/../tools/jdk"
export GRADLE_USER_HOME="${MODULE_DIR}/.gradle-home"
export XDG_DATA_HOME="${BUILD_TMP}/xdg-data"
export ANDROID_USER_HOME="${MODULE_DIR}/../gboard_probe/.android-home"

GRADLE="${MODULE_DIR}/.gradle-home/wrapper/dists/gradle-9.5.0-bin/local/gradle-9.5.0/bin/gradle"

if [ ! -f "$GRADLE" ]; then
    echo "Error: Gradle 9.5.0 not found at $GRADLE"
    exit 1
fi

"$GRADLE" \
    -Djava.io.tmpdir="${BUILD_TMP}" \
    -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 \
    -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 \
    :app:assembleDebug \
    --no-daemon 2>&1 | grep -v "^To honour\|^Daemon\|^Observed\|^$"

echo "=== Build exit code: $? ==="
