#!/usr/bin/env python3
"""
Wires the :extras module into a Gramophone checkout.

Every edit below is anchored on a distinctive line of upstream source. If an
anchor has moved the script stops and says which one, rather than silently
producing a half-patched tree — upstream's `beta` branch moves fast and a
quiet failure here would surface much later as a confusing build error.

Re-running is safe: each step checks whether it has already been applied.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE_SRC = HERE.parent / "extras"


class AnchorMissing(RuntimeError):
    pass


def patch(path: Path, anchor: str, replacement: str, *, marker: str) -> str:
    """Replace `anchor` with `replacement`, unless `marker` is already present."""
    text = path.read_text()
    if marker in text:
        return f"  = {path.name}: already patched"
    if anchor not in text:
        raise AnchorMissing(
            f"{path}: could not find the anchor below. Upstream has changed; "
            f"apply this edit by hand.\n--- anchor ---\n{anchor}\n--------------"
        )
    path.write_text(text.replace(anchor, replacement, 1))
    return f"  + {path.name}: patched"


def main(root: Path) -> int:
    if not (root / "app" / "build.gradle.kts").is_file():
        print(f"error: {root} does not look like a Gramophone checkout", file=sys.stderr)
        return 1

    steps = []

    # ------------------------------------------------------------------
    # 1. Copy the module in.
    # ------------------------------------------------------------------
    destination = root / "extras"
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(MODULE_SRC, destination)
    steps.append("  + extras/: module copied")

    # ------------------------------------------------------------------
    # 2. Register the module with Gradle.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "settings.gradle.kts",
        anchor='include(":app")',
        replacement='include(":extras")\ninclude(":app")',
        marker='include(":extras")',
    ))

    app_gradle = root / "app" / "build.gradle.kts"

    steps.append(patch(
        app_gradle,
        anchor='    implementation(project(":hificore"))',
        replacement=(
            '    implementation(project(":hificore"))\n'
            '    implementation(project(":extras"))'
        ),
        marker='project(":extras")',
    ))

    # ------------------------------------------------------------------
    # 3. Native libraries must be extracted on install.
    #
    # This is the one change that is not optional. youtubedl-android does not
    # load its payload with System.loadLibrary(); it *executes* it, resolving
    # CPython and ffmpeg as real files under applicationInfo.nativeLibraryDir.
    # With Gramophone's default (uncompressed, mapped straight out of the APK)
    # those paths do not exist and every download dies at startup.
    # ------------------------------------------------------------------
    steps.append(patch(
        app_gradle,
        anchor="""        jniLibs {
            useLegacyPackaging = false""",
        replacement="""        jniLibs {
            // Must stay true: the :extras module executes CPython and ffmpeg as
            // binaries from nativeLibraryDir, which requires them to be
            // extracted at install time rather than mapped from the APK.
            useLegacyPackaging = true""",
        marker="Must stay true",
    ))

    # ------------------------------------------------------------------
    # 4. Split by ABI. A universal APK carrying four copies of CPython and
    #    ffmpeg is ~220 MB; one ABI is ~65 MB.
    # ------------------------------------------------------------------
    steps.append(patch(
        app_gradle,
        anchor="""    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21""",
        replacement="""    // The yt-dlp payload is per-architecture, so ship one APK per ABI rather
    // than a universal build that carries three unusable copies of it.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21""",
        marker="splits {",
    ))

    # ------------------------------------------------------------------
    # 5. aboutLibraries runs in strict mode and fails the release build on any
    #    licence not on its allow-list. yt-dlp's Android wrapper is GPL-3.0,
    #    which is compatible with Gramophone's own GPL-3.0 licence but is not
    #    listed upstream because upstream has no GPL dependencies.
    # ------------------------------------------------------------------
    steps.append(patch(
        app_gradle,
        anchor='allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "LGPL-2.1-or-later")',
        replacement=(
            'allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", '
            '"LGPL-2.1-or-later", "GPL-3.0-or-later", "GPL-3.0", "Unlicense")'
        ),
        marker='"GPL-3.0-or-later"',
    ))

    # ------------------------------------------------------------------
    # 6. Settings entry point.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "xml" / "settings_top.xml",
        anchor="""    <Preference
        android:icon="@drawable/ic_info"
        android:key="about\"""",
        replacement="""    <Preference
        android:icon="@drawable/ic_ytdlp_download"
        android:key="downloader"
        android:layout="@layout/preference_basic"
        android:summary="@string/ytdlp_settings_summary"
        android:title="@string/ytdlp_settings_title" />

    <Preference
        android:icon="@drawable/ic_extras_filter"
        android:key="libraryFilter"
        android:layout="@layout/preference_basic"
        android:summary="@string/filter_settings_summary"
        android:title="@string/filter_settings_title" />

    <Preference
        android:icon="@drawable/ic_info"
        android:key="about\"""",
        marker='android:key="downloader"',
    ))

    fragment = (
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "ui" / "fragments" / "settings" / "MainSettingsFragment.kt"
    )
    steps.append(patch(
        fragment,
        anchor="""            "experimental" -> {
                startActivity(ExperimentalSettingsActivity::class.java)
            }""",
        replacement="""            "experimental" -> {
                startActivity(ExperimentalSettingsActivity::class.java)
            }

            "downloader" -> {
                startActivity(DownloaderActivity::class.java)
            }

            "libraryFilter" -> {
                startActivity(FilterActivity::class.java)
            }""",
        marker='"downloader" ->',
    ))
    steps.append(patch(
        fragment,
        anchor="import org.akanework.gramophone.R",
        replacement=(
            "import org.akanework.gramophone.R\n"
            "import org.akanework.gramophone.extras.filter.ui.FilterActivity\n"
            "import org.akanework.gramophone.extras.importer.ui.DownloaderActivity"
        ),
        marker="import org.akanework.gramophone.extras.importer.ui.DownloaderActivity",
    ))

    # ------------------------------------------------------------------
    # 7. Teach the library reader about per-file exclusions.
    #
    # Reader walks each file's OWN path before its parent directories when
    # testing the blacklist, so an absolute file path in that set hides exactly
    # that one file. The filter writes its verdicts to a separate preference so
    # the user's folder blacklist screen stays a list of folders, and the two
    # are unioned here.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "logic" / "GramophoneApplication.kt",
        anchor="""            if (key == null || key == "folderFilter") {
                blackListSetFlow.emit(prefs.getStringSet("folderFilter",
                    extraDisallowedFolders) ?: extraDisallowedFolders)
            }""",
        replacement="""            if (key == null || key == "folderFilter" || key == "junkFilterPaths") {
                val folders = prefs.getStringSet("folderFilter",
                    extraDisallowedFolders) ?: extraDisallowedFolders
                // Per-file exclusions written by the :extras library filter.
                val junk = prefs.getStringSet("junkFilterPaths", emptySet()) ?: emptySet()
                blackListSetFlow.emit(if (junk.isEmpty()) folders else folders + junk)
            }""",
        marker="junkFilterPaths",
    ))

    # ------------------------------------------------------------------
    # 8. Start the filter's watcher, if the user has switched it on.
    #    One line; the watcher itself checks the preference and does nothing
    #    when the feature is off.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "logic" / "GramophoneApplication.kt",
        anchor="""        // Set application theme when launching.""",
        replacement="""        // Re-applies the :extras library filter when new audio appears.
        // No-ops unless the user enabled both the filter and auto re-scan.
        org.akanework.gramophone.extras.filter.FilterWatcher.ensureStarted(this)

        // Set application theme when launching.""",
        marker="FilterWatcher.ensureStarted",
    ))

    print(f"Integrating :extras into {root}")
    for step in steps:
        print(step)
    print("\nDone. Build with:")
    print("  echo releaseType=CI > package.properties")
    print("  ./gradlew :app:assembleDebug")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <path-to-Gramophone-checkout>", file=sys.stderr)
        raise SystemExit(2)
    try:
        raise SystemExit(main(Path(sys.argv[1]).resolve()))
    except AnchorMissing as exc:
        print(f"\nerror: {exc}", file=sys.stderr)
        raise SystemExit(1)
