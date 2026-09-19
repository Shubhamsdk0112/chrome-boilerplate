# Gramophone + yt-dlp

A local Android music player that can pull songs straight off YouTube, tag them,
find real album art, and drop them into your library.

It is [Gramophone](https://github.com/AkaneTan/Gramophone) — an actively
maintained, Material 3, MediaStore-backed local player — plus one new Gradle
module, `:ytdlp`, that adds the importer.

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

## Using it

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

Run the heuristics tests with `./gradlew :ytdlp:testDebugUnitTest`.

### Updating upstream

The integration touches only **30 lines across 4 upstream files**, all anchored
on distinctive source lines. To move to a newer Gramophone, bump
`UPSTREAM_COMMIT` in `setup.sh` and re-run it. If an anchor has moved,
`integrate.py` stops and tells you exactly which edit to apply by hand rather
than producing a half-patched tree.

---

## What this repository contains

```
ytdlp/                     the new Gradle module — all the importer code
  src/main/java/.../
    YtDlp.kt               runtime lifecycle: lazy init, update, binary paths
    TrackMetadata.kt       metadata probe + title-cleaning heuristics
    ArtworkFinder.kt       iTunes / Deezer / thumbnail cover lookup
    Ffmpeg.kt              the artwork + tagging pass
    MusicImporter.kt       MediaStore publication (the player integration)
    DownloadRepository.kt  the queue and the pipeline
    DownloadService.kt     foreground service
    ui/DownloaderActivity.kt   Compose UI + share-target handling
  src/test/java/.../       unit tests for the title heuristics
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
