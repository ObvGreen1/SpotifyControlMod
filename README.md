# 🎵 Spotify Control

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](#requirements)
[![build](https://github.com/ObvGreen/SpotifyControlMod/actions/workflows/build.yml/badge.svg)](https://github.com/ObvGreen/SpotifyControlMod/actions/workflows/build.yml)

Client-side Minecraft **1.21.11** Fabric mod that controls Spotify playback from inside the game,
draws a configurable HUD overlay, ships keybinds, registers `/spotify` commands and integrates with
**Mod Menu**.

```
OS - Windows / Linux / macOS           Engine - OAuth 2.0 PKCE Web API + native fallback
Author - ObvGreen                       Package - me.obvgreen.spotifycontrol
License - MIT                           Current version - 1.1.0
```

> **Disclaimer:** this mod is an independent community project. It is not affiliated with, endorsed
> by, or sponsored by Spotify AB. "Spotify" and the Spotify word mark are trademarks of Spotify AB.
> The mod uses the public Spotify Web API under Spotify's developer terms.

---

## Features

| Area | What you get |
| --- | --- |
| Spotify engine | OAuth 2.0 **Authorization Code + PKCE** against the Spotify Web API, plus a native OS media-session fallback (Windows media keys via `keybd_event`, Linux MPRIS/DBus, macOS `osascript`) |
| Actions | `playPause()`, `nextTrack()`, `previousTrack()`, `toggleRepeat()`, `getCurrentlyPlayingTrack()` |
| HUD | Five element types — **card, art, progress, lyrics, mini** — each with anchor, X/Y offsets, scale, background opacity, text colour, text shadow, and marquee scrolling |
| Lyrics | Synchronised lyrics fetched from [LRCLIB](https://lrclib.net/), parsed LRC, rendered as a scrolling HUD element |
| Keybinds | Play/Pause, Next, Previous, Toggle Repeat, Toggle HUD, Toggle HUD Lyrics, Open Menu — category `key.category.spotifycontrol` |
| GUI | `/spotify menu` or the Open Menu keybind: transport, seek scrubber, volume, repeat, shuffle, ad-skip, lyrics, settings, account |
| Commands | `/spotify play\|pause\|toggle\|next\|skip\|prev\|repeat\|hud\|status` and `/spotify adskip [on\|off\|toggle\|now]` |
| Ad skipping | Closes and reopens the Spotify desktop app when a track looks like an advertisement (unknown artist, explicit `currently_playing_type = "ad"` from the Web API, or configurable keywords), with cooldown + per-hour limits and optional chat notifications — off by default |
| Config | Mod Menu → Cloth Config screen (`config/spotifycontrol.json`) |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for **1.21.11**.
2. Drop **Spotify Control** (`spotifycontrol-1.1.0.jar`), **Fabric API**, and optionally **Mod Menu**
   into `.minecraft/mods`.
3. Start the game. The HUD shows *Spotify Offline* until you log in (Web API) or a local media
   session is found (fallback).

## Requirements

* Minecraft **1.21.11**
* Fabric Loader **≥ 0.19.0**
* Fabric API (any 1.21.11 build; developed against `0.141.6+1.21.11`)
* Java **21**
* **Mod Menu 17.0.0+** (optional, needed for the in-game config screen)
* **Cloth Config v21+** — bundled via Jar-in-Jar, no manual install needed

## Building

```bash
./gradlew build            # Windows: .\gradlew.bat build
```

The remapped mod lands in `build/libs/spotifycontrol-1.1.0.jar`. Use Java 21 for the build, e.g.:

```powershell
$env:JAVA_HOME = "C:\Users\<you>\.jdks\ms-21.0.11"
.\gradlew.bat build
```

## Setting up the Spotify Web API (recommended)

1. Open the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and create an app.
2. Add a Redirect URI of exactly `http://localhost:8888/callback`
   (change the port in the config screen if you prefer another one — it must match).
3. Copy the **Client ID** into *Mod Menu → Spotify Control → Spotify API / Authentication →
   Spotify Client ID*. No client secret is ever used or stored: the flow is PKCE-only.
4. Flip the **Login** toggle. Your browser opens the Spotify consent page; approving it hands the
   authorization code back to the mod's loopback listener, which exchanges it for tokens.
5. Tokens (access + refresh) are written to `.minecraft/config/spotifycontrol.json` and refreshed
   automatically. Keep that file private — never commit it.

## Native fallback

When no Web API session exists, or when **Allow Local Fallback** is on, the mod drives the OS media
session instead:

* **Windows** — synthesises the global media keys (`VK_MEDIA_PLAY_PAUSE`, `VK_MEDIA_NEXT_TRACK`,
  `VK_MEDIA_PREV_TRACK`) through a persistent PowerShell bridge script. Transport control works with
  any player that honours the media keys; track metadata is best-effort (read from the window title)
  and repeat mode is not controllable through this path.
* **Linux** — MPRIS over DBus.
* **macOS** — `osascript` media commands.

If nothing is reachable the HUD shows *Spotify Offline* and every action degrades to a logged no-op
instead of throwing.

## Ad skipping

Spotify exposes **no API to skip an advertisement**, so the mod uses the only lever that exists:
closing and reopening the Spotify desktop client, which drops the ad slot and resumes playback where
the track list left off. The feature is **off by default** and lives in
*Mod Menu → Spotify Control → Ad Skipping* (or `/spotify adskip`).

A track is treated as an ad when:

1. the Web API reports `currently_playing_type = "ad"` (always trusted), or
2. the artist is unknown/"Unknown Artist" (normal for ad slots; also true for podcast episodes and
   local files — hence the rate limits), or
3. the title/artist/album contains one of the configured **ad keywords** (whole-word matching, so
   tracks like "Radio" or "Bad" are safe).

Safety rails: a configurable **cooldown** between restarts, a **per-hour restart cap**, a restart
**delay**, and an aborted restart when no local Spotify process exists (playback on a phone or
speaker is left alone). When Spotify comes back paused you can have the mod press play again.

## Commands

```
/spotify                     Show status
/spotify menu|gui            Open the control console GUI   (same as the Open Menu keybind)
/spotify play|pause|toggle   Transport
/spotify next|skip           Next track
/spotify prev|previous       Previous track
/spotify repeat <mode>       off | all | one   (aliases: none | context | track)
/spotify shuffle [on|off]    Shuffle state (bare: report)
/spotify volume              Report current volume
/spotify volume <percent>    Set volume, 0-200 (past 100 only where the OS allows it)
/spotify seek <seconds>      Jump inside the current track
/spotify hud [toggle|on|off] HUD visibility
/spotify adskip             Ad-skip status
/spotify adskip on|off      Enable / disable ad skipping
/spotify adskip toggle      Flip ad skipping
/spotify adskip now         Restart Spotify immediately
/spotify status             Detailed status
```

The console GUI covers the same ground without typing: transport, track position scrubber,
volume (with the backend's honest maximum), repeat, shuffle, ad-skip, lyrics and settings, plus
Spotify account login/logout when the Web API is configured.

## Threading

The render thread and the client tick thread never block. All network I/O runs on named daemon
executors owned by the service; album art and lyrics are fetched off-thread and uploaded to a
`DynamicTexture` on the main thread through `MinecraftClient.execute(...)`, and cached on disk under
`.minecraft/config/spotifycontrol/artcache`. The HUD only ever reads an immutable `TrackInfo`
snapshot that is replaced wholesale.

## Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| HUD shows *Spotify Offline* | Not logged in **and** no local media session. Log in through the config screen, or make sure Spotify/your media player is playing. |
| Login toggle does nothing | Redirect URI mismatch: the port in the Spotify dashboard must equal the one in the config screen. Check `logs/latest.log`. |
| `repeat` command ignored | Repeat is only controllable through the Web API path (native fallback cannot set repeat mode). |
| Ad skipping never triggers | Restart requires a **local** Spotify process; playback on a phone/speaker/web player is left alone. Check the trigger setting and that the track really is playing (ads that are paused don't count). |
| No album art | Art cache is empty or the URL provider failed; check that the mod can reach the internet (proxy on this machine? see [CONTRIBUTING.md](CONTRIBUTING.md)). |
| Build fails with proxy errors | The project itself defines no proxy; your machine-level Gradle proxy (`~/.gradle/gradle.properties`) applies. |
| Mod Menu screen missing | Mod Menu or Cloth Config not installed; Cloth Config is Jar-in-Jar bundled, Mod Menu is not. |

## Layout

```
src/main/java/me/obvgreen/spotifycontrol/
    SpotifyControlMod.java                  common ModInitializer
src/client/java/me/obvgreen/spotifycontrol/client/
    SpotifyControlClient.java               client entrypoint / wiring
    command/SpotifyCommand.java             /spotify Brigadier tree
    config/ModConfig.java                   persisted settings + Anchor
    config/ModConfigManager.java            process-wide config holder
    config/ModMenuIntegration.java          Mod Menu + Cloth Config screen
    config/TokenSet.java                    OAuth token record
    gui/AlbumArtCache.java                  async album art download + GL cache
    gui/SpotifyHudOverlay.java              HUD renderer
    gui/hud/                                HUD element system (card, art, progress, lyrics, mini)
    keybinds/SpotifyKeybinds.java           keybinds + tick polling
    lyrics/                                 LRC fetching, parsing, service (LRCLIB)
    spotify/SpotifyController.java          controller interface
    spotify/SpotifyWebApiClient.java        OAuth PKCE + Web API client
    spotify/SpotifyLocalController.java     native OS media bridge
    spotify/SpotifyService.java             facade, polling, interpolation, ad skipping
    spotify/NullSpotifyController.java      inert fallback
    spotify/AdDetector.java                 ad-slot heuristics (unknown artist / keywords)
    spotify/SpotifyAppRestarter.java        close + relaunch the desktop client
    spotify/RepeatMode.java                 OFF / CONTEXT / TRACK
    spotify/TrackInfo.java                  immutable track snapshot
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — development setup, code style, and pull request process.
Changes are tracked in [CHANGELOG.md](CHANGELOG.md).

## License

MIT — see [LICENSE](LICENSE).