#!/usr/bin/env python3
"""
Wires the :ytdlp module into a Gramophone checkout.

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
MODULE_SRC = HERE.parent / "ytdlp"


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
    destination = root / "ytdlp"
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(MODULE_SRC, destination)
    steps.append("  + ytdlp/: module copied")

    # ------------------------------------------------------------------
    # 2. Register the module with Gradle.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "settings.gradle.kts",
        anchor='include(":app")',
        replacement='include(":ytdlp")\ninclude(":app")',
        marker='include(":ytdlp")',
    ))

    app_gradle = root / "app" / "build.gradle.kts"

    steps.append(patch(
        app_gradle,
        anchor='    implementation(project(":hificore"))',
        replacement=(
            '    implementation(project(":hificore"))\n'
            '    implementation(project(":ytdlp"))'
        ),
        marker='project(":ytdlp")',
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
            // Must stay true: the :ytdlp module executes CPython and ffmpeg as
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
            }""",
        marker='"downloader" ->',
    ))
    steps.append(patch(
        fragment,
        anchor="import org.akanework.gramophone.ui.fragments.BaseSettingsActivity",
        replacement=(
            "import org.akanework.gramophone.ui.fragments.BaseSettingsActivity\n"
            "import org.akanework.gramophone.ytdlp.ui.DownloaderActivity"
        ),
        marker="import org.akanework.gramophone.ytdlp.ui.DownloaderActivity",
    ))

    print(f"Integrating :ytdlp into {root}")
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
