#!/usr/bin/env bash
#
# Verifies the :extras module WITHOUT an Android SDK.
#
# Why this exists: `./gradlew :extras:testDebugUnitTest` is the real check, but
# it needs the Android SDK from dl.google.com, which some CI and sandboxed
# environments cannot reach. This script gets equivalent coverage for the parts
# that matter using only Maven Central:
#
#   * a real android.jar (Robolectric publishes full framework jars there)
#   * the actual youtubedl-android classes, pulled from its published .aar
#   * tiny local stubs for the two androidx symbols used, since androidx is
#     published only on Google's Maven
#
# It compiles and runs every unit test, and syntax-checks the Compose and
# Service files that cannot be type-checked without androidx.
#
# Usage: ./verify.sh [cache-dir]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="${1:-${TMPDIR:-/tmp}/extras-verify}"
SRC="$HERE/extras/src"
M=https://repo1.maven.org/maven2

KOTLIN_VERSION=2.1.21
ANDROID_ALL=17-robolectric-15733970
YTDLP_VERSION=0.18.1

mkdir -p "$CACHE/libs"
cd "$CACHE"

fetch() { # url dest
    [ -s "$2" ] && return 0
    echo "  fetching $(basename "$2")"
    # Maven Central rate-limits large artifacts (429), so back off and retry
    # rather than failing the whole run.
    local attempt
    for attempt in 1 2 3 4 5; do
        if curl -sSLf -o "$2" "$1" --max-time 600; then
            [ -s "$2" ] && return 0
        fi
        rm -f "$2"
        echo "    attempt $attempt failed, retrying in $((attempt * 5))s"
        sleep $((attempt * 5))
    done
    echo "  ERROR: could not download $1" >&2
    return 1
}

echo "==> Dependencies (cached in $CACHE)"
if [ ! -x kotlinc/bin/kotlinc ]; then
    fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" kotlin-compiler.zip
    unzip -qq -o kotlin-compiler.zip
fi
fetch "$M/org/robolectric/android-all/$ANDROID_ALL/android-all-$ANDROID_ALL.jar" libs/android-all.jar
fetch "$M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar" libs/coroutines.jar
fetch "$M/junit/junit/4.13.2/junit-4.13.2.jar" libs/junit.jar
fetch "$M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" libs/hamcrest.jar
for m in jackson-databind jackson-core jackson-annotations; do
    fetch "$M/com/fasterxml/jackson/core/$m/2.18.2/$m-2.18.2.jar" "libs/$m.jar"
done
# The youtubedl-android API comes out of its published .aar. Only `library` is
# downloaded: the `ffmpeg` aar is 133 MB and the only thing we need from it is
# a single one-method object, stubbed below instead.
if [ ! -s libs/ytdlp-library.jar ]; then
    fetch "$M/io/github/junkfood02/youtubedl-android/library/$YTDLP_VERSION/library-$YTDLP_VERSION.aar" library.aar
    rm -rf aar-library && mkdir aar-library
    (cd aar-library && unzip -qq ../library.aar classes.jar)
    mv aar-library/classes.jar libs/ytdlp-library.jar
fi

# androidx is only on Google's Maven; these two symbols are all we use.
mkdir -p stubs
cat > stubs/AndroidxStubs.kt <<'STUB'
package androidx.preference
import android.content.Context
import android.content.SharedPreferences
object PreferenceManager {
    @JvmStatic
    fun getDefaultSharedPreferences(context: Context): SharedPreferences =
        throw UnsupportedOperationException("stub")
}
STUB
cat > stubs/FfmpegStub.kt <<'STUB'
package com.yausername.ffmpeg
import android.content.Context
object FFmpeg {
    @JvmStatic fun getInstance(): FFmpeg = this
    fun init(context: Context) = Unit
}
STUB
# The repository starts the foreground service when it resumes interrupted
# work; the service itself needs androidx.core's NotificationCompat.
cat > stubs/DownloadServiceStub.kt <<'STUB'
package org.akanework.gramophone.extras.importer
import android.content.Context
class DownloadService {
    companion object {
        fun ensureRunning(context: Context) = Unit
    }
}
STUB
cat > stubs/CoreKtxStubs.kt <<'STUB'
package androidx.core.content
import android.content.SharedPreferences
inline fun SharedPreferences.edit(
    commit: Boolean = false,
    action: SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit(); action(editor)
    if (commit) editor.commit() else editor.apply()
}
STUB

# The JVM's classpath separator is ';' on Windows and ':' everywhere else.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac
CP=$(ls libs/*.jar | tr '\n' "$SEP")
KOTLINC=kotlinc/bin/kotlinc
export JAVA_TOOL_OPTIONS=""
# Android Studio installs ship a JDK but do not put it on PATH; kotlinc finds
# it through JAVA_HOME, so the test runner should too.
JAVA=java
if ! command -v java >/dev/null 2>&1 && [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi

echo "==> Compiling module + tests"
rm -rf out
set +e
"$KOTLINC" stubs/*.kt \
    "$SRC/main/java/org/akanework/gramophone/extras/filter/"*.kt \
    "$SRC/main/java/org/akanework/gramophone/extras/importer/"{YtDlp,TrackMetadata,ArtworkFinder,Ffmpeg,MusicImporter,DownloadRepository,DownloadError,ImportIndex,JobStore,Pacing,Cookies}.kt     "$SRC/main/java/org/akanework/gramophone/extras/podcast/"{Feed,PodcastSearch,PodcastStore,EpisodeDownloader}.kt \
    "$SRC/test/java/org/akanework/gramophone/extras/"*/*.kt \
    -classpath "$CP" -d out > compile.log 2>&1
compile_rc=$?
set -e
grep -v "Picked up" compile.log || true
if [ "$compile_rc" -ne 0 ] || grep -q "error:" compile.log; then
    echo "COMPILE FAILED" >&2; exit 1
fi

echo "==> Running tests"
"$JAVA" -cp "out${SEP}${CP}kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore \
    org.akanework.gramophone.extras.filter.JunkHeuristicsTest \
    org.akanework.gramophone.extras.filter.AiClassifierTest \
    org.akanework.gramophone.extras.filter.VerdictTest \
    org.akanework.gramophone.extras.importer.MetadataProbeTest \
    org.akanework.gramophone.extras.importer.DownloadErrorTest \
    org.akanework.gramophone.extras.importer.CookiesTest \
    org.akanework.gramophone.extras.importer.ArtworkMatchTest \
    org.akanework.gramophone.extras.importer.PickOutputTest \
    org.akanework.gramophone.extras.importer.AudioFormatTest     org.akanework.gramophone.extras.podcast.RssParserTest 2>&1 | grep -v "Picked up"

# These pull in Compose and androidx.core, which cannot be resolved here, so
# only their syntax can be checked. Resolution errors are expected and ignored;
# a syntax error is not.
echo "==> Syntax-checking the files that need androidx"
status=0
for f in "$SRC/main/java/org/akanework/gramophone/extras/importer/ui/DownloaderActivity.kt" \
         "$SRC/main/java/org/akanework/gramophone/extras/filter/ui/FilterActivity.kt" \
         "$SRC/main/java/org/akanework/gramophone/extras/importer/DownloadService.kt"; do
    found=$("$KOTLINC" "$f" -d "$CACHE/syntax-check" 2>&1 \
        | grep "error:" | grep -iE "syntax error|expecting|unexpected token" || true)
    if [ -n "$found" ]; then
        echo "  SYNTAX ERROR in $(basename "$f")"; echo "$found"; status=1
    else
        echo "  ok  $(basename "$f")"
    fi
done

echo
if [ "$status" -eq 0 ]; then
    echo "All checks passed."
    echo "NOTE: this is not a substitute for ./gradlew :app:assembleDebug."
    echo "Compose type-checking and resource/manifest merging still need the SDK."
fi
exit "$status"
