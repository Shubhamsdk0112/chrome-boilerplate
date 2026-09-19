# Context for a local session

Read this first. It exists so a session on a real machine can start building
immediately instead of re-deriving the state.

## What this is

A fork of [Gramophone](https://github.com/AkaneTan/Gramophone) (native Android,
Kotlin, Material 3, MediaStore-backed) plus one new Gradle module, `:extras`,
holding two features:

- **importer** — paste or share a YouTube link, yt-dlp downloads the audio,
  finds real album art, tags it, publishes it to MediaStore.
- **filter** — hides non-music (WhatsApp voice notes, recordings, ringtones,
  video files) using local rules plus an optional OpenRouter pass for whatever
  the rules cannot place.

Upstream is **not vendored**. `setup.sh` clones it at a pinned commit and
`integrate/integrate.py` applies 10 anchored patches (52 lines across 5 files).

## The single most important fact

**This has never been compiled by a real Android toolchain.** It was written in
a sandbox where `dl.google.com` is blocked, so there is no Android SDK there.
Compose type-checking, resource merging and manifest merging have never run.

**Expect `:app:assembleDebug` to fail the first time.** Most likely in the three
files that could never be type-checked:

- `extras/src/main/java/org/akanework/gramophone/extras/importer/ui/DownloaderActivity.kt`
- `extras/src/main/java/org/akanework/gramophone/extras/filter/ui/FilterActivity.kt`
- `extras/src/main/java/org/akanework/gramophone/extras/importer/DownloadService.kt`

Everything else — the whole filter package and the importer core — does compile
clean against a real `android.jar`, and 98 unit tests pass.

**Fixing that first build is the job.** Start there.

## Build

```bash
./setup.sh                 # Git Bash or WSL on Windows
cd build/Gramophone
./gradlew :app:assembleDebug        # gradlew.bat on Windows
```

Needs JDK 21, the Android SDK and the NDK (Gramophone's `hificore` module has
native code). The first build is slow: it compiles a patched Media3 from source.

Install the APK matching the target — **arm64-v8a** for a phone, **x86_64** for
an emulator. The wrong one installs fine but leaves the app with no CPython or
ffmpeg to execute, and every download fails at startup.

## Verify without the SDK

`./verify.sh` compiles the module and runs all 98 unit tests using only Maven
Central (a Robolectric `android.jar`, the real youtubedl-android classes, and
two small androidx stubs). Useful in CI or a sandbox. **Not** a substitute for a
Gradle build — it cannot type-check Compose.

## Things that are easy to break

- `jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts` is **required**.
  youtubedl-android does not `System.loadLibrary()` its payload, it *executes*
  it from `nativeLibraryDir`, which only exists if libs are extracted at install.
- `aboutLibraries` runs in strict mode and fails release builds on any licence
  not allow-listed. GPL-3.0 was added for yt-dlp's wrapper.
- The filter hides files by writing absolute paths into the `junkFilterPaths`
  preference, which a patch unions into Gramophone's blacklist. `Reader` walks
  each file's own path before its parents, which is why per-file entries work.
- The OpenRouter key is supplied by the user in-app and must never be committed.

## Not yet verified by anyone

- The Gradle build (above).
- The live OpenRouter request. `openrouter.ai` was also blocked in the sandbox,
  so only the request-building and response-parsing are tested (15 tests,
  including every malformed-response path). Failure there leaves files visible.
- Any behaviour on a real device.
