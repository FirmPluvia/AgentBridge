#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
: "${ANDROID_HOME:?Install Android SDK and set ANDROID_HOME}"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
mkdir -p ../artifacts
cp app/build/outputs/apk/debug/app-debug.apk ../artifacts/AgentBridge-debug.apk
cd ../artifacts
sha256sum AgentBridge-debug.apk > SHA256SUMS
