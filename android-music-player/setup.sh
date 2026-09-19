#!/usr/bin/env bash
#
# Builds the forked player from scratch:
#   1. clones Gramophone at the commit this integration was verified against
#   2. copies the :extras module in and wires it up
#   3. leaves you with a tree you can build or open in Android Studio
#
# Requires: git, python3, JDK 21, and the Android SDK (ANDROID_HOME) with NDK.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="${1:-$HERE/build/Gramophone}"

# Pinned rather than tracking `beta`: upstream moves several times a week and
# the integration is anchored on specific lines of its build files. Bump this
# deliberately, then re-run integrate.py and fix anything it reports.
UPSTREAM_URL="https://github.com/AkaneTan/Gramophone.git"
UPSTREAM_COMMIT="e8217339ffe7e70de05d41ad1c2a24ba1cf35946"

if [ ! -d "$CHECKOUT/.git" ]; then
    echo "==> Cloning Gramophone into $CHECKOUT"
    mkdir -p "$(dirname "$CHECKOUT")"
    git clone "$UPSTREAM_URL" "$CHECKOUT"
fi

echo "==> Checking out pinned commit ${UPSTREAM_COMMIT:0:10}"
git -C "$CHECKOUT" fetch --all --tags
git -C "$CHECKOUT" checkout --quiet "$UPSTREAM_COMMIT"

# media3 is a patched fork Gramophone builds from source, and hificore needs
# libusb + farbot. Without these the Gradle build fails at configuration time.
echo "==> Fetching submodules (this pulls a patched Media3, expect a few hundred MB)"
git -C "$CHECKOUT" submodule update --init --recursive

echo "==> Applying the :extras integration"
python3 "$HERE/integrate/integrate.py" "$CHECKOUT"

# The app module reads this for its version suffix; upstream gitignores it.
if [ ! -f "$CHECKOUT/package.properties" ]; then
    echo "releaseType=CI" > "$CHECKOUT/package.properties"
fi

cat <<NEXT

Ready.

  cd $CHECKOUT
  ./gradlew :extras:testDebugUnitTest    # unit tests
  ./gradlew :app:assembleDebug           # per-ABI debug APKs

APKs land in app/build/outputs/apk/debug/. Install the arm64-v8a one:

  adb install -r app/build/outputs/apk/debug/*arm64-v8a*.apk

NEXT
