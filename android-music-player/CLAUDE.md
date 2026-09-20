# Context for a local session

Read this first. It exists so a session on a real machine can start building
immediately instead of re-deriving the state.

## What this is

A fork of [Gramophone](https://github.com/AkaneTan/Gramophone) (native Android,
Kotlin, Material 3, MediaStore-backed) plus one new Gradle module, `:extras`,
holding three features:

- **importer** — paste or share a YouTube link, yt-dlp downloads the audio,
  finds real album art, tags it, publishes it to MediaStore. One tap from the
  library toolbar. Queue survives process death.
- **podcasts** — find a show by name (Apple directory) or feed URL, follow
  it, stream or download episodes (resumable), play through Gramophone's own
  player, pick up where you left off. Episode files live under the app's
  external files dir so they never appear as songs.
- **podcasts from YouTube** — a YouTube video ≥ 10 min (or one asked for as a
  podcast) becomes an episode of a show named after its channel, with the
  video's chapters; `Pacing.longVideosToPodcasts` is the switch. "On this
  phone" lists MediaStore audio ≥ 10 min as a virtual show.
- **filter** — hides non-music (WhatsApp voice notes, recordings, ringtones,
  video files, audio ≥ 10 min) using local rules plus an optional OpenRouter
  pass for whatever the rules cannot place. On by default and run from the
  patched `MainActivity.updateLibrary` before every library read, so a fresh
  install never shows the junk; the AI pass is opt-in.

Plus a curated palette (Spotify-style black + green, `integrate/palette.py`)
applied to upstream's resources and to the Compose screens alike.

Upstream is **not vendored**. `setup.sh` clones it at a pinned commit and
`integrate/integrate.py` applies ~20 anchored patches.

## State (2026-09-19)

**Builds and runs.** Verified on Windows 11 with Android Studio's JDK 21,
SDK Platform 37, NDK 28.2 (Gradle installs both), on an API 36 x86_64
emulator:

- `:app:assembleDebug` succeeds and produces per-ABI debug APKs.
- App launches with no StrictMode violation from `:extras` (upstream's debug
  builds use `detectAll()` + `penaltyDialog()` on the main thread — any
  first-touch disk I/O on the main thread pops a dialog).
- Settings shows "Add from YouTube" and "Filter library".
- Importer end to end: link shared via `ACTION_SEND` → metadata → download →
  YouTube-thumbnail cover → ffmpeg tags → `Music/Gramophone/Artist - Title.m4a`
  in MediaStore → appears in the library with art → plays.
- Filter end to end: a WhatsApp `PTT-*.opus` and a `Recordings/Call/*.m4a` are
  hidden with reasons, written to `junkFilterPaths`, and Gramophone's library
  drops them (the blacklist-union patch works). Tagged real songs survive.
- "Update yt-dlp" in the menu works (moved 2025.11.12 → 2026.08.19).
- Podcasts end to end: follow by feed URL, download (progress inline, kill
  + relaunch resumes), play a downloaded and a streamed episode through the
  session (dumpsys shows our item), position persisted.
- Process death: a queued song or episode is re-run on the next launch of
  any screen ("resuming N interrupted job(s)" in logcat).
- YouTube podcast: a 28-min video with 10 chapters routed to Podcasts on its
  own, chapters parsed, chapter skip lands on the right seconds; a 403 from
  YouTube went through the automatic retry + pacing.
- Pull-to-refresh, download pacing menu, green player, long-audio rule.
- Fresh install with nothing touched: 7 files, 2 hidden at first launch
  (voice note, 12-min audiobook); the Filter screen shows that result.
- `./verify.sh`: 115 unit tests pass, on Windows too.
- Release APKs (R8, signed) smoke-tested on the emulator before each tag.
- v0.2.1 (phone-reported bugs): Back inside Folders/Filesystem goes up a
  folder (`DetailedFolderAdapter` back callback, defers to the player sheet
  and the fragment back stack); next/previous always advertised and wrap
  around at the queue ends (`EndedWorkaroundPlayer.getState` +
  `onPlayerCommandRequest`); `extras/player/FocusResumer` resumes after
  another app's audio stops (Behaviour setting, default on).
- v0.2.2: YouTube cookies (`importer/Cookies.kt`, dialog in the downloader
  menu; private jar passed as `--cookies` to probe + download). The bot
  check ("Sign in to confirm you're not a bot") is an IP flag — reproduced
  from the PC with plain yt-dlp — and is now: explained, one throttled
  yt-dlp update, cookies as the fix. `Failed.botCheck` is persisted so
  saving cookies retries exactly those jobs.

## Build

```bash
./setup.sh                 # Git Bash on Windows (sets core.longpaths itself)
cd build/Gramophone
./gradlew :app:assembleDebug        # gradlew.bat on Windows
```

Needs JDK 21, the Android SDK and the NDK (Gramophone's `hificore` module has
native code). The first build is slow: it compiles a patched Media3 from source
and may download SDK Platform 37 and NDK 28.2. Later builds take ~1–2 minutes.

Install the APK matching the target — **arm64-v8a** for a phone, **x86_64** for
an emulator. The wrong one installs fine but leaves the app with no CPython or
ffmpeg to execute, and every download fails at startup. The debug package id is
`org.akanework.gramophone.debug`.

Palette: edit `integrate/palette.py`, then `python integrate/palette.py
--kotlin` (regenerates `extras/.../ui/Palette.kt`) and re-run integrate.

After editing anything under `extras/`, re-run
`python integrate/integrate.py build/Gramophone` (it re-copies the module and
is idempotent about the patches), then build.

### Windows notes

- Media3's test assets exceed MAX_PATH; `setup.sh` sets `core.longpaths`. If a
  submodule checkout ever dies halfway, re-run `setup.sh` — it passes
  `--force` so the checkout is repaired.
- `python3` on Windows is a Microsoft Store stub; `setup.sh` picks whichever
  interpreter actually runs.
- Android Studio's JDK is not on PATH. `export JAVA_HOME="C:\Program
  Files\Android\Android Studio\jbr"` before `gradlew.bat` / `verify.sh`.
- A Play Store emulator image fills its 6 GB data partition with Google app
  updates; the APK needs ~350 MB free to install.

## Release builds

```bash
cd build/Gramophone
./gradlew :app:assembleRelease -PAKANE_RELEASE_STORE_FILE=... -PAKANE_RELEASE_STORE_PASSWORD=... \
    -PAKANE_RELEASE_KEY_ALIAS=... -PAKANE_RELEASE_KEY_PASSWORD=...
```

Upstream reads the signing config from those Gradle properties. The test
key lives outside the repo at `C:\Users\shubh\dev\keys\gramophone-release.jks`
with its properties file next to it; never commit either. R8 is on for
release, and `extras/consumer-rules.pro` keeps youtubedl-android, Jackson
and commons-compress — without the last one the very first download dies
in commons-compress's static initialiser. Always run one download on a
release build before shipping it.

Releases are published to GitHub with `C:\Users\shubh\dev\publish_release.py <tag>`
(uses the stored git credential; notes come from `C:\Users\shubh\dev\release-notes.md`).
Tags so far: `extras-v0.1.0`, `extras-v0.2.0`, `extras-v0.2.1`, `extras-v0.2.2`. The PR from the working branch
to `main` is opened with `C:\Users\shubh\dev\open_pr.py` (same credential).

## Verify without the SDK

`./verify.sh` compiles the module and runs all 115 unit tests using only Maven
Central (a Robolectric `android.jar`, the real youtubedl-android classes, and
two small androidx stubs). Useful in CI or a sandbox. **Not** a substitute for a
Gradle build — it cannot type-check Compose.

## Things that are easy to break

- `jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts` is **required**.
  youtubedl-android does not `System.loadLibrary()` its payload, it *executes*
  it from `nativeLibraryDir`, which only exists if libs are extracted at install.
  `keepDebugSymbols` for `libpython.zip.so` / `libffmpeg.zip.so` only silences
  the strip step; they are zip files, not ELF.
- minSdk is 24 (app and `:extras`), not upstream's 23: youtubedl-android
  declares 24 and the manifest merger refuses anything lower.
- `aboutLibraries` runs in strict mode and fails the build on any licence not
  allow-listed. youtubedl-android's POM names its licence `"GPL-3.0 license"`
  (not an SPDX id), so that exact string is on the allow-list.
- youtubedl-android's `execute()` **throws** `YoutubeDLException` (message =
  yt-dlp's stderr) on a non-zero exit; it does not return `exitCode != 0`.
  Failure handling lives in the catch blocks, and `DownloadError.humanize`
  turns stderr into a message.
- The bundled yt-dlp is as old as the APK. When a download fails the way a
  stale extractor fails (`DownloadError.needsUpdate`), the repository updates
  yt-dlp once and retries before showing an error.
- Nothing in `:extras` may touch SharedPreferences, files or the network on
  the main thread — see StrictMode above. Even `context.filesDir` counts
  (PodcastStore keeps it lazy), and starting an activity with a content://
  URI does too (the downloader does it from IO). The StrictMode dialog blocks
  the main thread, which then shows up as an ANR.
- Gramophone's image loader has no network fetcher. Remote covers go through
  `ImageCache` to disk first; the player is handed a file:// artwork URI.
- The playback service passes URI-bearing MediaItems straight through, which
  is how episodes play; mediaIds are strings (`podcast:<guid>`).
- The filter hides files by writing absolute paths into the `junkFilterPaths`
  preference, which a patch unions into Gramophone's blacklist. `Reader` walks
  each file's own path before its parents, which is why per-file entries work.
- The OpenRouter key is supplied by the user in-app and must never be committed.

## Not yet verified by anyone

- The live OpenRouter request. Only the request-building and response-parsing
  are tested (15 tests, including every malformed-response path). Failure there
  leaves files visible.
- Anything on a real arm64 phone, or a release build.
- A podcast download interrupted mid-transfer (the Range resume path is
  unit-tested; on the emulator episodes finish before a kill lands).

## Known follow-ups

- The AI pass has still not been exercised live (needs an OpenRouter key
  in-app). Default model was updated to one that exists
  (`google/gemini-2.5-flash-lite`); ids churn, check /api/v1/models.
- ListeningHistory keeps a MediaController bound from Application.onCreate,
  which keeps the playback service alive while the app process lives.

- Playback speed and skip-silence for podcasts (Gramophone has a speed
  control in the player; nothing podcast-specific yet).
- Pacing: the gap also applies to the automatic retry of the same job,
  which is fine after a 403 but adds a wait after plain network drops.
- YouTube channel shows use the first video's thumbnail as cover; yt-dlp
  does not give the channel avatar in a single-video probe.
- Auto-download new episodes / refresh feeds in the background.

- Downloads and the OpenRouter call open untagged sockets, which upstream's
  VmPolicy logs (not a dialog). `TrafficStats.setThreadStatsTag` would quiet it.
