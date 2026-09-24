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

sys.path.insert(0, str(HERE))
import palette  # noqa: E402  (sibling module; holds the colour tokens)


class AnchorMissing(RuntimeError):
    pass


def patch(path: Path, anchor: str, replacement: str, *, marker: str, every: bool = False) -> str:
    """Replace `anchor` with `replacement`, unless `marker` is already present.

    `every=True` replaces all occurrences, for a line upstream repeats verbatim.
    """
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return f"  = {path.name}: already patched"
    if anchor not in text:
        raise AnchorMissing(
            f"{path}: could not find the anchor below. Upstream has changed; "
            f"apply this edit by hand.\n--- anchor ---\n{anchor}\n--------------"
        )
    count = -1 if every else 1
    path.write_text(text.replace(anchor, replacement, count), encoding="utf-8", newline="\n")
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
        # Replace the sources but leave Gradle's own output directory alone:
        # on Windows the daemon keeps files under extras/build open, so
        # deleting it fails, and keeping it means a re-run does not force a
        # full rebuild of the module.
        for child in destination.iterdir():
            if child.name == "build":
                continue
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
    shutil.copytree(MODULE_SRC, destination, dirs_exist_ok=True)
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
            useLegacyPackaging = true
            // These are zip payloads named like shared objects so the install
            // extracts them. They are not ELF, so tell the strip step to leave
            // them alone instead of printing an error per ABI.
            keepDebugSymbols += "**/libpython.zip.so"
            keepDebugSymbols += "**/libffmpeg.zip.so\"""",
        marker="Must stay true",
    ))

    # ------------------------------------------------------------------
    # 3b. youtubedl-android declares minSdk 24 and the manifest merger refuses
    #     to build an app below a library's floor. Upstream is at 23 (Android
    #     6.0, which has effectively no users left); raising it is the honest
    #     fix, as tools:overrideLibrary would only move the failure to runtime.
    # ------------------------------------------------------------------
    steps.append(patch(
        app_gradle,
        anchor="""        applicationId = appIdOverride ?: "org.akanework.gramophone"
        minSdk = 23""",
        replacement="""        applicationId = appIdOverride ?: "org.akanework.gramophone"
        // 24, not upstream's 23: the :extras module's yt-dlp runtime needs it.
        minSdk = 24""",
        marker="yt-dlp runtime needs it",
    ))

    # ------------------------------------------------------------------
    # 4. Split by ABI. A universal APK carrying four copies of CPython and
    #    ffmpeg is ~220 MB; one ABI is ~65 MB.
    #
    #    x86_64 is included for the emulator. Without it the app installs on an
    #    emulator but has no CPython or ffmpeg to execute, so every download
    #    fails at startup with nothing obvious to point at. Splits mean this is
    #    an extra APK file, not extra bytes in the phone one.
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
            include("arm64-v8a", "armeabi-v7a", "x86_64")
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
    #
    #    The wrapper's POM names the licence "GPL-3.0 license" rather than by
    #    SPDX id, and aboutLibraries cannot map that to GPL-3.0, so the check
    #    (which is applied to debug builds too) sees an unknown licence. The
    #    allow-list accepts raw names as well as SPDX ids, so list that too.
    # ------------------------------------------------------------------
    steps.append(patch(
        app_gradle,
        anchor='allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "LGPL-2.1-or-later")',
        replacement=(
            'allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", '
            '"LGPL-2.1-or-later", "GPL-3.0-or-later", "GPL-3.0", "GPL-3.0 license", "Unlicense")'
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
        android:icon="@drawable/ic_extras_podcast"
        android:key="podcasts"
        android:layout="@layout/preference_basic"
        android:summary="@string/podcast_settings_summary"
        android:title="@string/podcast_settings_title" />

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

    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "xml" / "settings_top.xml",
        anchor="""    <Preference
        android:icon="@drawable/ic_extras_filter"
        android:key="libraryFilter\"""",
        replacement="""    <Preference
        android:icon="@drawable/ic_extras_podcast"
        android:key="podcasts"
        android:layout="@layout/preference_basic"
        android:summary="@string/podcast_settings_summary"
        android:title="@string/podcast_settings_title" />

    <Preference
        android:icon="@drawable/ic_extras_filter"
        android:key="libraryFilter\"""",
        marker='android:key="podcasts"',
    ))

    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "xml" / "settings_top.xml",
        anchor="""    <Preference
        android:icon="@drawable/ic_extras_filter"
        android:key="libraryFilter\"""",
        replacement="""    <Preference
        android:icon="@drawable/ic_extras_history"
        android:key="history"
        android:layout="@layout/preference_basic"
        android:summary="@string/history_settings_summary"
        android:title="@string/history_settings_title" />

    <Preference
        android:icon="@drawable/ic_extras_filter"
        android:key="libraryFilter\"""",
        marker='android:key="history"',
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
        anchor="""            "libraryFilter" -> {
                startActivity(FilterActivity::class.java)
            }""",
        replacement="""            "libraryFilter" -> {
                startActivity(FilterActivity::class.java)
            }

            "podcasts" -> {
                startActivity(PodcastsActivity::class.java)
            }""",
        marker='"podcasts" ->',
    ))
    steps.append(patch(
        fragment,
        anchor="import org.akanework.gramophone.extras.importer.ui.DownloaderActivity",
        replacement=(
            "import org.akanework.gramophone.extras.importer.ui.DownloaderActivity\n"
            "import org.akanework.gramophone.extras.podcast.ui.PodcastsActivity"
        ),
        marker="import org.akanework.gramophone.extras.podcast.ui.PodcastsActivity",
    ))
    steps.append(patch(
        fragment,
        anchor="""            "podcasts" -> {
                startActivity(PodcastsActivity::class.java)
            }""",
        replacement="""            "podcasts" -> {
                startActivity(PodcastsActivity::class.java)
            }

            "history" -> {
                startActivity(HistoryActivity::class.java)
            }""",
        marker='"history" ->',
    ))
    steps.append(patch(
        fragment,
        anchor="import org.akanework.gramophone.extras.importer.ui.DownloaderActivity",
        replacement=(
            "import org.akanework.gramophone.extras.history.ui.HistoryActivity\n"
            "import org.akanework.gramophone.extras.importer.ui.DownloaderActivity"
        ),
        marker="import org.akanework.gramophone.extras.history.ui.HistoryActivity",
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
    # 6a. The palette. Upstream's Android 12+ theme takes its colours from
    #     the wallpaper; below that it uses a stock Material blue. Both are
    #     replaced by the curated set in palette.py, on every API level.
    #     The player keeps seeding its own colours from the album cover.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "values-v31" / "themes.xml",
        anchor='<style name="Theme.Gramophone" parent="Base.Theme.Gramophone">',
        replacement=(
            '<!-- PreV31 rather than Base: the curated palette, not wallpaper colours. -->\n'
            '    <style name="Theme.Gramophone" parent="PreV31.Theme.Gramophone">'
        ),
        marker="curated palette, not wallpaper colours",
    ))
    steps.extend(palette.apply_to_checkout(root))

    # The player tints itself from the album cover by default (upstream's
    # "content-based colour"), which replaces the palette with whatever the
    # cover happens to be. Off by default; still a switch under Player UI.
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "ui" / "components" / "FullBottomSheet.kt",
        anchor='prefs.getBooleanStrict("content_based_color", true)',
        replacement='prefs.getBooleanStrict("content_based_color", false)',
        marker='getBooleanStrict("content_based_color", false)',
        every=True,
    ))
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "xml" / "settings_player.xml",
        anchor="""        <SwitchPreferenceCompat
            android:defaultValue="true"
            android:icon="@drawable/ic_colors"
            android:key="content_based_color\"""",
        replacement="""        <SwitchPreferenceCompat
            android:defaultValue="false"
            android:icon="@drawable/ic_colors"
            android:key="content_based_color\"""",
        marker='android:defaultValue="false"\n            android:icon="@drawable/ic_colors"',
    ))

    # ------------------------------------------------------------------
    # 6b. One-tap entry from the library screen. Settings is three taps deep
    #     and the importer is the feature people open most; the icon sits next
    #     to search in the home toolbar.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "menu" / "home_menu.xml",
        anchor="""    <item
        android:id="@+id/shuffle\"""",
        replacement="""    <item
        android:id="@+id/download"
        android:icon="@drawable/ic_ytdlp_download"
        android:title="@string/ytdlp_settings_title"
        app:showAsAction="always" />
    <item
        android:id="@+id/shuffle\"""",
        marker='android:id="@+id/download"',
    ))
    pager = (
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "ui" / "fragments" / "ViewPagerFragment.kt"
    )
    steps.append(patch(
        pager,
        anchor="""                R.id.settings -> {
                    activity.startActivity(Intent(activity, MainSettingsActivity::class.java))
                }""",
        replacement="""                R.id.settings -> {
                    activity.startActivity(Intent(activity, MainSettingsActivity::class.java))
                }

                R.id.download -> {
                    activity.startActivity(Intent(activity, DownloaderActivity::class.java))
                }""",
        marker="R.id.download ->",
    ))
    steps.append(patch(
        pager,
        anchor="import org.akanework.gramophone.ui.fragments.settings.MainSettingsActivity",
        replacement=(
            "import org.akanework.gramophone.extras.importer.ui.DownloaderActivity\n"
            "import org.akanework.gramophone.ui.fragments.settings.MainSettingsActivity"
        ),
        marker="import org.akanework.gramophone.extras.importer.ui.DownloaderActivity",
    ))
    # With two more icons beside it, "ifRoom" demotes search to the overflow
    # on a phone. Search is the most used action on that bar; pin it.
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "menu" / "home_menu.xml",
        anchor="""        android:title="@string/home_menu_search"
        app:showAsAction="ifRoom" />""",
        replacement="""        android:title="@string/home_menu_search"
        app:showAsAction="always" />""",
        marker='home_menu_search"\n        app:showAsAction="always"',
    ))
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "menu" / "home_menu.xml",
        anchor="""    <item
        android:id="@+id/shuffle\"""",
        replacement="""    <item
        android:id="@+id/podcasts"
        android:icon="@drawable/ic_extras_podcast"
        android:title="@string/podcast_settings_title"
        app:showAsAction="always" />
    <item
        android:id="@+id/shuffle\"""",
        marker='android:id="@+id/podcasts"',
    ))
    steps.append(patch(
        pager,
        anchor="""                R.id.download -> {
                    activity.startActivity(Intent(activity, DownloaderActivity::class.java))
                }""",
        replacement="""                R.id.download -> {
                    activity.startActivity(Intent(activity, DownloaderActivity::class.java))
                }

                R.id.podcasts -> {
                    activity.startActivity(Intent(activity, PodcastsActivity::class.java))
                }""",
        marker="R.id.podcasts ->",
    ))
    steps.append(patch(
        pager,
        anchor="import org.akanework.gramophone.extras.importer.ui.DownloaderActivity",
        replacement=(
            "import org.akanework.gramophone.extras.importer.ui.DownloaderActivity\n"
            "import org.akanework.gramophone.extras.podcast.ui.PodcastsActivity"
        ),
        marker="import org.akanework.gramophone.extras.podcast.ui.PodcastsActivity",
    ))
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "menu" / "home_menu.xml",
        anchor="""    <item
        android:id="@+id/settings\"""",
        replacement="""    <item
        android:id="@+id/history"
        android:icon="@drawable/ic_extras_history"
        android:title="@string/history_title"
        app:showAsAction="never" />
    <item
        android:id="@+id/settings\"""",
        marker='android:id="@+id/history"',
    ))
    steps.append(patch(
        pager,
        anchor="""                R.id.podcasts -> {
                    activity.startActivity(Intent(activity, PodcastsActivity::class.java))
                }""",
        replacement="""                R.id.podcasts -> {
                    activity.startActivity(Intent(activity, PodcastsActivity::class.java))
                }

                R.id.history -> {
                    activity.startActivity(Intent(activity, HistoryActivity::class.java))
                }""",
        marker="R.id.history ->",
    ))
    steps.append(patch(
        pager,
        anchor="import org.akanework.gramophone.extras.importer.ui.DownloaderActivity",
        replacement=(
            "import org.akanework.gramophone.extras.history.ui.HistoryActivity\n"
            "import org.akanework.gramophone.extras.importer.ui.DownloaderActivity"
        ),
        marker="import org.akanework.gramophone.extras.history.ui.HistoryActivity",
    ))

    # ------------------------------------------------------------------
    # 6c. Pull to refresh on the library screen. The pager is wrapped in a
    #     SwipeRefreshLayout (which takes over the scrolling-view behaviour so
    #     the collapsing header keeps working) and wired to upstream's own
    #     updateLibrary, the same thing the "Quick refresh" menu item does.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "layout" / "fragment_viewpager.xml",
        anchor="""    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/fragment_viewpager"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        app:layout_behavior="@string/appbar_scrolling_view_behavior" />""",
        replacement="""    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/extras_swipe_refresh"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <androidx.viewpager2.widget.ViewPager2
            android:id="@+id/fragment_viewpager"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false" />

    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>""",
        marker='android:id="@+id/extras_swipe_refresh"',
    ))
    steps.append(patch(
        pager,
        anchor="""        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()""",
        replacement="""        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()
        // :extras — pull down at the top of a list to refresh the library.
        rootView.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.extras_swipe_refresh)
            ?.let { swipe ->
                org.akanework.gramophone.extras.ui.PullToRefresh.attach(swipe, viewPager2, appBarLayout) {
                    SingletonImageLoader.get(requireContext()).memoryCache?.clear()
                    (requireActivity() as MainActivity).updateLibrary { swipe.isRefreshing = false }
                }
            }""",
        marker="PullToRefresh.attach",
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

    # ------------------------------------------------------------------
    # 7b. Ask before throwing the user's data away on uninstall. Songs live
    #     in Music/ and survive anyway; podcast episodes, positions, history
    #     and the filter's Keep choices are app-private and would not. With
    #     this flag Android 10+ offers a "Keep app data" box in the uninstall
    #     dialog, and a later reinstall picks everything up again.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "AndroidManifest.xml",
        anchor="""        android:allowBackup="true"
        android:appCategory="audio\"""",
        replacement="""        android:allowBackup="true"
        android:hasFragileUserData="true"
        android:appCategory="audio\"""",
        marker="hasFragileUserData",
    ))

    # ------------------------------------------------------------------
    # 8a. Run the library filter before the library is read, so a fresh
    #     install never shows the voice notes at all and a re-open catches
    #     what arrived while the app was dead. updateLibrary is the one
    #     chokepoint: first launch, permission grant, pull-to-refresh.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "ui" / "MainActivity.kt",
        anchor="""            this@MainActivity.gramophoneApplication.reader.refresh()
            withContext(Dispatchers.Main) {
                onLibraryLoaded()""",
        replacement="""            // :extras library filter, ahead of the read so the first list is
            // already clean. Cached verdicts make a repeat pass cheap.
            org.akanework.gramophone.extras.filter.FilterWatcher
                .scanIfEnabled(this@MainActivity.gramophoneApplication)
            this@MainActivity.gramophoneApplication.reader.refresh()
            withContext(Dispatchers.Main) {
                onLibraryLoaded()""",
        marker="FilterWatcher.scanIfEnabled",
    ))

    # ------------------------------------------------------------------
    # 8b. Pick up downloads that a process death interrupted. One small file
    #     read on IO when there is nothing to do. Anchored on the line the
    #     previous patch inserted, so it applies to old and new checkouts.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "logic" / "GramophoneApplication.kt",
        anchor="""        org.akanework.gramophone.extras.filter.FilterWatcher.ensureStarted(this)""",
        replacement="""        org.akanework.gramophone.extras.filter.FilterWatcher.ensureStarted(this)
        // Resumes any :extras download that a process death interrupted.
        org.akanework.gramophone.extras.importer.DownloadRepository.resumeOnStartup(this)""",
        marker="DownloadRepository.resumeOnStartup",
    ))
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "logic" / "GramophoneApplication.kt",
        anchor="""        org.akanework.gramophone.extras.importer.DownloadRepository.resumeOnStartup(this)""",
        replacement="""        org.akanework.gramophone.extras.importer.DownloadRepository.resumeOnStartup(this)
        // Counts plays and remembers when, for the listening history screen.
        org.akanework.gramophone.extras.history.ListeningHistory.start(this)""",
        marker="ListeningHistory.start",
    ))


    # ------------------------------------------------------------------
    # 9a. Back goes up one folder in the Folders / Filesystem tabs instead
    #     of leaving the app. Upstream navigates those tabs in place (the
    #     ".." row is the only way up) and never registered a back callback.
    #     The callback is live only while the tab is the visible page and a
    #     folder is open, and it defers to anything more specific — an
    #     expanded player sheet, a fragment on the back stack — by disabling
    #     itself and re-dispatching.
    # ------------------------------------------------------------------
    folder_adapter = (root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
                      / "ui" / "adapters" / "DetailedFolderAdapter.kt")
    steps.append(patch(
        folder_adapter,
        anchor="""    val qTitle = fileNodePath.map { it?.lastOrNull() ?: "/" }""",
        replacement="""    val qTitle = fileNodePath.map { it?.lastOrNull() ?: "/" }

    // :extras — Back goes up one folder instead of leaving the app.
    private var inFolder = false
    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Anything more specific — an expanded player sheet, a fragment
            // on the back stack — wins, exactly as if this callback did not
            // exist.
            isEnabled = false
            if (mainActivity.onBackPressedDispatcher.hasEnabledCallbacks()) {
                mainActivity.onBackPressedDispatcher.onBackPressed()
            } else {
                enter(null)
            }
            refreshBackCallback()
        }
    }
    private val backCallbackLifecycle =
        androidx.lifecycle.LifecycleEventObserver { _, _ -> refreshBackCallback() }

    private fun refreshBackCallback() {
        // Pages that are not the current one sit at STARTED, so this also
        // keeps a remembered folder on a hidden tab from swallowing Back.
        backCallback.isEnabled = inFolder && fragment.lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }""",
        marker="refreshBackCallback",
    ))
    steps.append(patch(
        folder_adapter,
        anchor="""        this.scope!!.launch {
            fileNodePath.collect {
                withContext(Dispatchers.Main) {
                    folderPopAdapter.enabled = !it.isNullOrEmpty()
                }
            }
        }
    }""",
        replacement="""        this.scope!!.launch {
            fileNodePath.collect {
                withContext(Dispatchers.Main) {
                    folderPopAdapter.enabled = !it.isNullOrEmpty()
                    inFolder = !it.isNullOrEmpty()
                    refreshBackCallback()
                }
            }
        }
        mainActivity.onBackPressedDispatcher.addCallback(fragment, backCallback)
        fragment.lifecycle.addObserver(backCallbackLifecycle)
    }""",
        marker="addCallback(fragment, backCallback)",
    ))
    steps.append(patch(
        folder_adapter,
        anchor="""    override fun onDetachedFromRecyclerView(recyclerView: MyRecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope!!.cancel()
        scope = null
    }""",
        replacement="""    override fun onDetachedFromRecyclerView(recyclerView: MyRecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope!!.cancel()
        scope = null
        backCallback.remove()
        fragment.lifecycle.removeObserver(backCallbackLifecycle)
    }""",
        marker="backCallback.remove()",
    ))

    # ------------------------------------------------------------------
    # 9b. Previous / next never disappear. ExoPlayer withdraws
    #     COMMAND_SEEK_TO_NEXT on the last song (repeat off), so the
    #     notification drops the button and Android's media controls reflow
    #     the custom buttons into the gap. Always offer both commands and
    #     wrap the seek around at either end (9c).
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "logic" / "utils" / "exoplayer" / "EndedWorkaroundPlayer.kt",
        anchor="""        if (isEnded) {
            if (superState.playerError != null) {""",
        replacement="""        // :extras — previous/next stay available at both ends of the queue;
        // the service wraps the seek around. Otherwise the last song loses
        // its "next" button and the system media controls reflow the custom
        // buttons into the gap, which reads as the buttons disappearing.
        if (!superState.timeline.isEmpty) {
            superState = superState.buildUpon()
                .setAvailableCommands(
                    superState.availableCommands.buildUpon()
                        .add(COMMAND_SEEK_TO_NEXT)
                        .add(COMMAND_SEEK_TO_PREVIOUS)
                        .build()
                )
                .build()
        }
        if (isEnded) {
            if (superState.playerError != null) {""",
        marker="add(COMMAND_SEEK_TO_NEXT)",
    ))

    service = (root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
               / "logic" / "GramophonePlaybackService.kt")

    # 9c. The wrap-around itself, at the one place every controller — the
    #     notification, the system media controls, Bluetooth buttons, our own
    #     UI — goes through.
    steps.append(patch(
        service,
        anchor="""    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {""",
        replacement="""    // :extras — see EndedWorkaroundPlayer.getState(): next/previous are always
    // offered, so at either end of the queue they wrap around instead of
    // silently doing nothing.
    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int
    ): Int {
        val player = session.player
        val timeline = player.currentTimeline
        if (!timeline.isEmpty && !player.isCurrentMediaItemLive) {
            val shuffle = player.shuffleModeEnabled
            if (playerCommand == Player.COMMAND_SEEK_TO_NEXT && !player.hasNextMediaItem()) {
                player.seekTo(timeline.getFirstWindowIndex(shuffle), C.TIME_UNSET)
                return SessionResult.RESULT_INFO_SKIPPED
            }
            if (playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS && !player.hasPreviousMediaItem()
                && player.currentPosition <= player.maxSeekToPreviousPosition
            ) {
                player.seekTo(timeline.getLastWindowIndex(shuffle), C.TIME_UNSET)
                return SessionResult.RESULT_INFO_SKIPPED
            }
        }
        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {""",
        marker="override fun onPlayerCommandRequest",
    ))

    # ------------------------------------------------------------------
    # 9d. Resume after another app's audio ends (extras FocusResumer).
    #     Installed on the ExoPlayer, released with the service.
    # ------------------------------------------------------------------
    steps.append(patch(
        service,
        anchor="""    private lateinit var handler: Handler
""",
        replacement="""    private lateinit var handler: Handler
    private var focusResumer: org.akanework.gramophone.extras.player.FocusResumer? = null
""",
        marker="focusResumer:",
    ))
    steps.append(patch(
        service,
        anchor="""        player.exoPlayer.addAnalyticsListener(EventLogger())""",
        replacement="""        // :extras — resume once another app is done with the speaker
        // (Behaviour › "Resume after other apps finish playing").
        focusResumer = org.akanework.gramophone.extras.player.FocusResumer(this, player.exoPlayer)
            .also { it.attach() }
        player.exoPlayer.addAnalyticsListener(EventLogger())""",
        marker="FocusResumer(this",
    ))
    steps.append(patch(
        service,
        anchor="""        Log.i(TAG, "+onDestroy()")
        instanceForWidgetAndLyricsOnly = null""",
        replacement="""        Log.i(TAG, "+onDestroy()")
        instanceForWidgetAndLyricsOnly = null
        focusResumer?.release()
        focusResumer = null""",
        marker="focusResumer?.release()",
    ))
    steps.append(patch(
        root / "app" / "src" / "main" / "res" / "xml" / "settings_behavior.xml",
        anchor="""        <SwitchPreferenceCompat
            android:defaultValue="false"
            android:key="stopPlayingWhenDismissTask"
            android:layout="@layout/preference_switch"
            android:summary="@string/settings_stop_on_dismiss_summary"
            android:title="@string/settings_stop_on_dismiss"
            android:widgetLayout="@layout/preference_switch_widget"
            app:iconSpaceReserved="false" />""",
        replacement="""        <SwitchPreferenceCompat
            android:defaultValue="false"
            android:key="stopPlayingWhenDismissTask"
            android:layout="@layout/preference_switch"
            android:summary="@string/settings_stop_on_dismiss_summary"
            android:title="@string/settings_stop_on_dismiss"
            android:widgetLayout="@layout/preference_switch_widget"
            app:iconSpaceReserved="false" />

        <SwitchPreferenceCompat
            android:defaultValue="true"
            android:key="extras_resume_after_interruption"
            android:layout="@layout/preference_switch"
            android:summary="@string/extras_resume_summary"
            android:title="@string/extras_resume_title"
            android:widgetLayout="@layout/preference_switch_widget"
            app:iconSpaceReserved="false" />""",
        marker="extras_resume_after_interruption",
    ))

    # ------------------------------------------------------------------
    # 9e. Upstream's Android 14 workaround cancels the media notification
    #     whenever MainActivity is destroyed while paused (media3 #805 — the
    #     service's onDestroy is not called after a swipe from recents).
    #     Destroyed for memory in the background is not that case, and it
    #     took a paused-but-alive session's notification with it. Limit it
    #     to a real finish.
    # ------------------------------------------------------------------
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "ui" / "MainActivity.kt",
        anchor="""        if (needsMissingOnDestroyCallWorkarounds()
            && (getPlayer()?.playWhenReady != true || getPlayer()?.mediaItemCount == 0)""",
        replacement="""        if (needsMissingOnDestroyCallWorkarounds() && isFinishing
            && (getPlayer()?.playWhenReady != true || getPlayer()?.mediaItemCount == 0)""",
        marker="needsMissingOnDestroyCallWorkarounds() && isFinishing",
    ))


    # ------------------------------------------------------------------
    # 10a. Home tab (extras HomeFragment, Compose): first in the default tab
    #      order, and put first for existing users whose saved tab list
    #      predates it (upstream would append it after the hidden marker,
    #      i.e. hide it). The tab settings screen can still move or hide it.
    # ------------------------------------------------------------------
    vp_adapter = (root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
                  / "ui" / "adapters" / "ViewPager2Adapter.kt")
    steps.append(patch(
        vp_adapter,
        anchor="""            // Do not rename entries here, names are written to disk. Order is default tab order
            Songs(R.id.songs, R.string.category_songs),""",
        replacement="""            // Do not rename entries here, names are written to disk. Order is default tab order
            Home(org.akanework.gramophone.extras.R.id.extras_home,
                org.akanework.gramophone.extras.R.string.home_tab),
            Songs(R.id.songs, R.string.category_songs),""",
        marker="org.akanework.gramophone.extras.R.id.extras_home",
    ))
    steps.append(patch(
        vp_adapter,
        anchor="""                if (!stList.contains(it) && (it != Tab.Genres || hasImprovedMediaStore()))
                    stList.add(it)""",
        replacement="""                if (!stList.contains(it) && (it != Tab.Genres || hasImprovedMediaStore())) {
                    // :extras — a saved tab list from before Home existed
                    // gets Home in front rather than past the hidden marker.
                    if (it == Tab.Home) stList.add(0, it) else stList.add(it)
                }""",
        marker="if (it == Tab.Home) stList.add(0, it)",
    ))
    steps.append(patch(
        vp_adapter,
        anchor="""    override fun createFragment(position: Int): Fragment =
        AdapterFragment().apply {""",
        replacement="""    override fun createFragment(position: Int): Fragment =
        if (tabs[position] == Tab.Home) org.akanework.gramophone.extras.home.HomeFragment()
        else AdapterFragment().apply {""",
        marker="extras.home.HomeFragment()",
    ))
    steps.append(patch(
        root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
        / "ui" / "fragments" / "ViewPagerFragment.kt",
        anchor="""                            as AdapterFragment?)?.onTabReselected()""",
        replacement="""                            as? AdapterFragment)?.onTabReselected()""",
        marker="as? AdapterFragment)?.onTabReselected()",
    ))
    # Android Auto's browse tree maps tabs to categories; Home is not one.
    tree = (root / "app" / "src" / "main" / "java" / "org" / "akanework" / "gramophone"
            / "logic" / "LibraryTreeLoader.kt")
    steps.append(patch(
        tree,
        anchor="""            .filter { it != ViewPager2Adapter.Companion.Tab.FileSystem }""",
        replacement="""            .filter { it != ViewPager2Adapter.Companion.Tab.FileSystem
                && it != ViewPager2Adapter.Companion.Tab.Home }""",
        marker="it != ViewPager2Adapter.Companion.Tab.Home",
    ))
    steps.append(patch(
        tree,
        anchor='        ViewPager2Adapter.Companion.Tab.FileSystem -> "detailed_folders"',
        replacement=('        ViewPager2Adapter.Companion.Tab.FileSystem -> "detailed_folders"\n'
                     '        ViewPager2Adapter.Companion.Tab.Home -> "home"'),
        marker="Tab.Home -> \"home\"",
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
