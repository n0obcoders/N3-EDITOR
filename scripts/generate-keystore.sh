#!/usr/bin/env bash
# Creates a release signing key. Keep release.jks and its passwords out of git
# (both are ignored by .gitignore) and store a copy somewhere safe: an APK can only
# be updated by a build signed with the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
keytool -genkeypair -v \
  -keystore release.jks \
  -alias notebook \
  -keyalg RSA -keysize 4096 -validity 10000
echo "Now copy keystore.properties.sample to keystore.properties and fill in the passwords."
