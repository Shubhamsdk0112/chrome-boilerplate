#!/usr/bin/env bash
#
# Builds the forked player from scratch:
#   1. clones Gramophone at the commit this integration was verified against
#   2. copies the :extras module in and wires it up
#   3. leaves you with a tree you can build or open in Android Studio
#
# Requires: git, Python 3, JDK 21, and the Android SDK (ANDROID_HOME) with NDK.
#
# On Windows, run this from Git Bash (ships with Git for Windows) or WSL, then
# build with gradlew.bat.
set -euo pipefail

# Windows installs Python as `python`, most other places as `python3`. On
# Windows `python3` is often a Microsoft Store stub that prints an install
# prompt and exits non-zero, so pick the first name that actually runs.
PYTHON=""
for candidate in python3 python py; do
    if "$candidate" -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >/dev/null 2>&1; then
        PYTHON=$candidate
        break
    fi
done
if [ -z "$PYTHON" ]; then
    echo "error: Python 3.8+ is required but was not found on PATH" >&2
    exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="${1:-$HERE/build/Gramophone}"

# Pinned rather than tracking `beta`: upstream moves several times a week and
# the integration is anchored on specific lines of its build files. Bump this
# deliberately, then re-run integrate.py and fix anything it reports.
UPSTREAM_URL="https://github.com/AkaneTan/Gramophone.git"
UPSTREAM_COMMIT="e8217339ffe7e70de05d41ad1c2a24ba1cf35946"

# Media3's test assets have paths past Windows' 260-character limit. Without
# this the submodule checkout fails with "Filename too long".
git config --global core.longpaths true 2>/dev/null || true

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
# --force: always run the checkout even when the recorded commit already
# matches, so a checkout that died halfway (long paths on Windows) is repaired
# on the next run instead of silently leaving a tree with files missing.
git -C "$CHECKOUT" submodule update --init --recursive --force
if [ ! -f "$CHECKOUT/media3/settings.gradle.kts" ]; then
    echo "error: media3 submodule is incomplete (no settings.gradle.kts)" >&2
    exit 1
fi

echo "==> Applying the :extras integration"
"$PYTHON" "$HERE/integrate/integrate.py" "$CHECKOUT"

# The app module reads this for its version suffix; upstream gitignores it.
if [ ! -f "$CHECKOUT/package.properties" ]; then
    echo "releaseType=CI" > "$CHECKOUT/package.properties"
fi

cat <<NEXT

Ready.

  cd $CHECKOUT
  ./gradlew :extras:testDebugUnitTest    # unit tests   (Windows: gradlew.bat)
  ./gradlew :app:assembleDebug           # per-ABI debug APKs

APKs land in app/build/outputs/apk/debug/. Install the arm64-v8a one:

  adb install -r app/build/outputs/apk/debug/*arm64-v8a*.apk

NEXT
