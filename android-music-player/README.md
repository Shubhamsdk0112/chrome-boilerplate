# Gramophone + extras

A fork of [Gramophone](https://github.com/AkaneTan/Gramophone) — a native
Android music player (Kotlin, Material 3, MediaStore) — with one extra Gradle
module, `:extras`, that adds what a phone music library is missing in 2026:

| | |
|---|---|
| **Add from YouTube** | Share a link from the YouTube app (or paste one). yt-dlp downloads the audio, real album art is found, the file is tagged and lands in your library. Batches are paced so YouTube does not rate-limit you; a stale yt-dlp updates itself; the queue survives the app being killed. |
| **Podcasts** | Paste a YouTube link to a long video and it becomes an episode with **chapters**, in its own section, never mixed with songs. Or search shows by name / paste an RSS feed. Stream or download, pick up where you left off, skip chapter by chapter. Long audio already on the phone shows up here too. |
| **Filter library** | Hides WhatsApp voice notes, call recordings, ringtones, videos and other non-music from the library — with the reason and a Keep button per file. Optional AI pass (your OpenRouter key) for anything the rules cannot place. |
| **Listening history** | Recently played and most played. |
| **Look** | One curated palette across the whole app (black + green), pull-to-refresh, back arrows everywhere. |

Everything else — playback, playlists, albums, artists, folders, lyrics,
equaliser — is upstream Gramophone, untouched.

## Install

Grab the latest APK from the [Releases](../../releases) page. Install the
**arm64-v8a** one on a phone. Minimum Android 7.0.

## Build

Upstream is not vendored. `setup.sh` clones it at a pinned commit and
`integrate/integrate.py` applies ~25 anchored patches on top:

```bash
./setup.sh                 # Git Bash on Windows
cd build/Gramophone
./gradlew :app:assembleDebug
```

Needs JDK 21, the Android SDK and NDK. See [CLAUDE.md](CLAUDE.md) for the
full state of the project, every gotcha found on the way, and how release
builds are signed and published.

`./verify.sh` runs the 115 unit tests without an SDK.

## Layout

```
extras/                       the module: importer/, podcast/, filter/, history/, ui/
integrate/integrate.py        wires :extras into an upstream checkout (anchored patches)
integrate/palette.py          the colour palette, applied to upstream and Compose alike
setup.sh · verify.sh          bootstrap and SDK-free tests
```

## Licence

GPL-3.0, like Gramophone and yt-dlp's Android wrapper.
