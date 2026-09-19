# Gramophone + extras

A local Android music player that

- pulls songs straight off YouTube, tags them, finds real album art and drops
  them into your library, and
- keeps the junk **out** of that library — WhatsApp voice notes, call
  recordings, ringtones, stray video files — using local rules plus an optional
  AI pass for the leftovers.

It is [Gramophone](https://github.com/AkaneTan/Gramophone) — an actively
maintained, Material 3, MediaStore-backed local player — plus one new Gradle
module, `:extras`, holding both features.

---

## How yt-dlp works, and why it fits on a phone

yt-dlp is a **Python command-line program**. You give it a URL and flags; it
talks to YouTube, picks a stream, downloads it, and shells out to **ffmpeg** to
remux, convert and tag the result. There is no GUI and no daemon. The Python
package itself is about **3 MB**.

Android has no Python interpreter, so it can't run directly. The
[`youtubedl-android`](https://github.com/yausername/youtubedl-android) library
solves that by shipping a stripped CPython, yt-dlp and ffmpeg as native
libraries. It's what [Seal](https://github.com/JunkFood02/Seal) uses, so this is
a well-travelled path.

Measured from the actual `0.18.1` artifacts, for **arm64-v8a**:

| Component | Adds to APK | Unpacks on first run |
|---|---:|---:|
| `library` — CPython 3.8 + yt-dlp + quickjs | 14.5 MB | ~40 MB |
| `ffmpeg` — ffmpeg + ffprobe | 34.5 MB | ~74 MB |
| Gramophone itself | ~15 MB | — |
| **Total** | **~65 MB** | **~115 MB** |

So roughly **180 MB on device**. Two consequences shape the build:

- **One APK per ABI.** A universal APK carries four copies of CPython and ffmpeg
  and lands around 220 MB. The integration enables ABI splits, so you install a
  ~65 MB `arm64-v8a` APK instead.
- **Native libraries must be extracted at install time.** youtubedl-android does
  not `System.loadLibrary()` its payload — it *executes* it, resolving CPython
  and ffmpeg as real files under `nativeLibraryDir`. Upstream Gramophone sets
  `jniLibs.useLegacyPackaging = false`, which leaves them mapped inside the APK
  where they cannot be exec'd. The integration flips it back to `true`.

Unpacking those 115 MB is far too slow for `Application.onCreate`, so the
runtime initialises **lazily**, the first time you actually import something.
If you never use the importer, the player starts exactly as fast as upstream.

---

## What an import actually does

```
YouTube URL
   │
   ├─ 1. probe      yt-dlp --print  →  track / artist / album / duration
   │                (YouTube Music entries carry real tags; plain uploads get
   │                 the title heuristics in TrackMetadata.kt instead)
   │
   ├─ 2. download   yt-dlp -x --audio-format m4a --embed-metadata
   │                into the app's own cache directory
   │
   ├─ 3. artwork    iTunes Search → Deezer → cropped video thumbnail
   │
   ├─ 4. tag        one ffmpeg copy pass: attach the cover, write the tags
   │
   └─ 5. publish    insert into MediaStore under Music/Gramophone/
                    → Gramophone's library observer fires → song appears
```

Step 5 is the whole integration with the player, and it is deliberately tiny.
Gramophone's `FlowReader` already watches `MediaStore.Files`, so the moment a row
lands the library refreshes by itself. Nothing hooks into the player's internals,
and there is nothing to redo when upstream changes.

### Some choices worth knowing about

**M4A is the default, not MP3.** YouTube already serves AAC in an MP4 container,
so asking for M4A lets yt-dlp *remux* — rewriting the container without touching
the audio. It's fast and lossless. MP3 forces a re-encode of already-lossy audio:
slower, and strictly worse. It's offered only for hardware that needs it.

**Album art does not come from the video thumbnail if it can be avoided.** A
YouTube thumbnail is 16:9 with a face and some text on it, which looks bad in a
library grid. The importer asks the iTunes Search API and then Deezer — both
answer without an API key — and only falls back to a centre-cropped thumbnail
when neither recognises the song. Those lookups also recover the **album name**,
which YouTube doesn't know, so imports don't each become their own one-track
album. Results are only accepted when the title and artist overlap strongly
enough, so you don't get confidently mis-tagged covers.

**Downloads are staged in the cache directory** and only copied into your music
folder once complete and tagged, so a cancelled or failed job can never leave a
half-written file in your library.

**The tagging pass never fails an import.** yt-dlp writes its own tags during the
download with its own ffmpeg. If the artwork pass then fails for any reason, you
still get a correctly tagged song, just without the nicer cover.

---

## The library filter

**Settings → Filter library.**

The problem: a phone accumulates thousands of audio files that are not music.
WhatsApp voice notes named `AUD-20240102-WA0007.opus`, call recordings,
notification blips, stray video files. A MediaStore-backed player shows all of
it and the library becomes unusable.

### What Gramophone already does, before any of this

Worth being straight about, because it changes what this filter is actually for.
Out of the box Gramophone already excludes `Ringtones/`, `Notifications/`,
`Alarms/`, `Podcasts/`, `Audiobooks/`, `Recordings/` (Android 12+) and
`Android/media` — which covers **modern** WhatsApp, since it now stores under
`Android/media/com.whatsapp/`. It also drops anything under 5 seconds.

So this filter is not rescuing a player that shows everything. It closes the
gaps that a folder-based default list structurally cannot:

- **Legacy paths.** `/WhatsApp/Media/WhatsApp Audio/` at the top of storage,
  which pre-scoped-storage installs and restored backups still use. This is
  usually the one people actually hit.
- **Telegram, Signal**, and vendor recorder folders (`MIUI/sound_recorder`,
  `Easy Voice Recorder`, …) that no standard-directory list covers.
- **Recordings living anywhere.** `Recording_001.m4a` sitting in `/Music/` is
  invisible to a folder rule.
- **Per-file decisions.** Upstream's blacklist is folder-granular. This works
  file by file, so one junk file in a good folder can go without taking the
  folder with it.
- **Screen and meeting recordings, video files, untagged timestamp-named
  clips**, and everything between 5 and 30 seconds that the length filter lets
  through.

### How it decides

Two stages, and the second one usually barely runs at all.

### Stage 1 — local rules, free and instant

Two kinds of rule:

- **Decisive** — things that are simply not music. A file named
  `AUD-20240102-WA0007.opus`, or one sitting in `WhatsApp/Media/WhatsApp Audio`,
  or carrying Android's own ringtone/alarm/recording flag. These short-circuit
  and are not up for debate.
- **Weighted** — everything else is scored from signals: artist and album tags,
  folder, duration, and **encoded bitrate**, which separates speech from music
  better than anything else available without decoding the file (voice notes sit
  near 16–32 kbps, music rarely below 96).

Anything that lands in the middle is returned as *unsure* and stays **visible**.

### Stage 2 — the AI pass, for leftovers only

Only *unsure* files reach it, so on a real library this is a handful of items,
not thousands. They are batched 40 per request to OpenRouter and the verdicts
are cached permanently, so a file is never classified twice.

**What gets sent: filename, folder, duration, bitrate and tags. That is all.**
No audio ever leaves the device — there is nothing in the request that a file
listing does not already show. There is a unit test asserting this.

You supply your own OpenRouter key in the settings screen; it is stored in the
app's private preferences and never compiled in. The model is a text field —
it defaults to a cheap one and any OpenRouter model id works.

### Keeping it clean by itself

A one-off cleanup is only half the job — messaging apps keep producing voice
notes. **Keep it clean automatically** (off by default) watches MediaStore and
re-runs the filter when new audio appears, debounced by 15 seconds so copying an
album triggers one scan rather than one per track. Because verdicts are cached,
a re-scan only does real work for files it has not seen, so the steady-state
cost is close to nothing.

### Three things it will never do

**It never deletes anything.** A junk verdict adds the file's path to
Gramophone's blacklist, which hides it from the library. The file stays on your
phone, untouched. "Show everything again" reverses the whole thing instantly.

**It never hides on failure.** Missing key, no network, rate limit, a model
replying with garbage — every failure path leaves files *visible*. The filter is
built so its worst case is showing too much, never silently swallowing your
music.

**It never hides what you imported.** Anything the YouTube importer wrote to
`Music/Gramophone/` is music by definition and is exempt from every rule, even
when its tagging pass failed and the file looks anonymous. The app does not get
to undo its own work.

Every hidden file is listed with the reason it was hidden, and a **Keep** button
that overrides the decision permanently.

### How it hooks into the player

`Reader` walks each file's **own path** before its parent directories when
testing the blacklist, so an absolute file path in that set hides exactly that
one file. The filter writes verdicts to a separate `junkFilterPaths` preference,
and a 6-line patch unions it with your folder blacklist — so the folder
blacklist screen stays a list of folders, and writing the set is what triggers
the library refresh.

---

## Using the importer

Two entry points:

- **Share → Gramophone** from the YouTube app. The link goes straight into the
  queue. This is the one you'll actually use.
- **Settings → Add from YouTube**, to paste a link by hand.

Downloads run one at a time under a foreground service, so leaving the app
doesn't kill them.

### Keeping it working

YouTube changes its player regularly, and a stale extractor is the usual cause of
"this suddenly stopped working". **Settings → Add from YouTube → ⋮ → Update
yt-dlp** pulls a fresh extractor at runtime — it swaps only the ~3 MB Python
payload, so it's quick and needs no new APK. Try that first, before assuming
anything is broken.

Failures say so in plain language rather than dumping a Python traceback, and
the ones that the update button actually fixes say that explicitly. Anything
unrecognised falls through to yt-dlp's own last error line — a confusing message
you can search for beats a friendly one that hides what happened.

---

## Building

You need JDK 21, the Android SDK, and the NDK (Gramophone's `hificore` module has
native code). Then:

```bash
./setup.sh
cd build/Gramophone
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/*arm64-v8a*.apk
```

`setup.sh` clones Gramophone at a pinned commit, fetches its submodules (it
builds a patched Media3 from source — expect a few hundred MB), and applies the
integration.

Run the tests with `./gradlew :extras:testDebugUnitTest`.

### Verifying without the Android SDK

`./verify.sh` compiles the module and runs all 71 unit tests using only Maven
Central — a real `android.jar` (Robolectric publishes full framework jars), the
actual youtubedl-android classes from its published `.aar`, and two tiny local
stubs for the androidx symbols, since androidx is published only on Google's
Maven. It also syntax-checks the three Compose/Service files that cannot be
type-checked without androidx.

This is what to use in a sandbox or CI that cannot reach `dl.google.com`. It is
**not** a substitute for a Gradle build: Compose type-checking, resource merging
and manifest merging all still need the SDK.

### Updating upstream

The integration touches only **52 lines across 5 upstream files** in 10 anchored
patches. To move to a newer Gramophone, bump
`UPSTREAM_COMMIT` in `setup.sh` and re-run it. If an anchor has moved,
`integrate.py` stops and tells you exactly which edit to apply by hand rather
than producing a half-patched tree.

---

## What this repository contains

```
extras/                    the new Gradle module
  src/main/java/.../extras/
    importer/              YouTube -> library
      YtDlp.kt             runtime lifecycle: lazy init, update, binary paths
      TrackMetadata.kt     metadata probe + title-cleaning heuristics
      ArtworkFinder.kt     iTunes / Deezer / thumbnail cover lookup
      Ffmpeg.kt            the artwork + tagging pass
      MusicImporter.kt     MediaStore publication (the player integration)
      DownloadRepository.kt   the queue and the pipeline
      DownloadError.kt     turns yt-dlp stderr into something actionable
      DownloadService.kt   foreground service
      ui/DownloaderActivity.kt
    filter/                keeping non-music out
      AudioCandidate.kt    the model + verdict types (no Android imports)
      JunkHeuristics.kt    stage one: decisive rules + weighted signals
      AiClassifier.kt      stage two: OpenRouter, with a pure testable core
      FilterStore.kt       settings, verdict cache, the hidden-path set
      LibraryScanner.kt    MediaStore query and orchestration
      FilterWatcher.kt     opt-in re-scan when new audio appears
      ui/FilterActivity.kt Compose settings + review screen
  src/test/java/.../       71 unit tests
integrate/integrate.py     applies the wiring into a Gramophone checkout
setup.sh                   clone + integrate in one step
```

Upstream Gramophone is **not vendored** — it's cloned on demand. Its submodules
alone run to hundreds of megabytes, and keeping the fork as a small patch is what
makes pulling upstream changes practical.

---

## Caveats, stated plainly

- **Google Play does not allow yt-dlp apps.** Sideload the APK. F-Droid is an
  option if you ever want to distribute it.
- **Downloading from YouTube is against YouTube's Terms of Service.** This is a
  personal-use tool; that's your call to make.
- **Licensing.** Gramophone is GPL-3.0 and so is this module. The integration
  adds GPL-3.0 to `aboutLibraries`' allow-list, which otherwise fails the release
  build in strict mode.
- **PO tokens.** YouTube increasingly gates formats behind proof-of-origin
  tokens. The bundled quickjs handles many JS challenges, but some videos may
  need cookies or a token provider. This is the most likely thing to need
  attention over time, and it's a yt-dlp-side problem rather than an app one.
- **Opus cover art is best-effort.** Embedding pictures in Ogg/Opus is awkward;
  M4A and MP3 are reliable.
