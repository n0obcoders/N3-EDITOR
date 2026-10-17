#!/usr/bin/env bash
# Builds the debug APK, the release APK (signed when keystore.properties or the
# NOTEBOOK_KEYSTORE_* variables are present, otherwise unsigned) and the release AAB.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease

echo
echo "Outputs:"
ls -1 app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk app/build/outputs/bundle/release/*.aab 2>/dev/null
