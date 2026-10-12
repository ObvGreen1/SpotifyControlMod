# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Full control console GUI (`/spotify menu`, openable via a new configurable keybind, default
  unbound): transport buttons, track-position scrubber with seek, repeat/shuffle state, ad-skip
  toggle, lyrics screen with live sync offset and manual scroll, settings shortcut, and Spotify
  account login/logout.
- `SpotifyLyricsScreen`: full-screen synced lyrics with karaoke highlight, ±0.5s offset tuning
  (persisted), reload, and scroll fallback for untimed sheets.
- Shuffle and seek controls: `/spotify shuffle [on|off]` and `/spotify seek <seconds>`, plus GUI
  buttons; Web API backend supports both, native Linux/macOS backends support both, Windows media
  keys support neither (surfaced honestly, never faked).
- Volume control: `/spotify volume [<percent>]`, GUI slider, and keyboard PgUp/PgDn-style volume
  step keys; Web API and Windows per-app Core Audio session cap at 100 %, Linux PulseAudio/PipeWire
  sink input reaches 200 %. The GUI always shows the backend's real maximum.
- Configurable ad skipping: when an advertisement is detected (explicit Web API `currently_playing_type
  = "ad"` signal, unknown artist and/or configurable keywords), the Spotify desktop client is closed
  and reopened, dropping the ad slot, with cooldown and per-hour restart limits plus optional
  chat notifications and playback resume (`AdDetector`, `SpotifyAppRestarter`).
- Ad detection inside `SpotifyWebApiClient` via the `advertisement` flag on `TrackInfo`.
- `/spotify adskip [on|off|toggle|now]` subcommand and an "Ad Skipping" category in the config screen.

### Changed

- Volume is read back from the backend (`volume_percent` from the Web API, `volume get` from the
  native bridge) when the console opens, so the slider starts on the real value instead of a guess.

### Planned

- Configurable keybind defaults instead of `UNBOUND`
- Track-change desktop notifications

## [1.1.0]

### Added

- Modular HUD element system (`gui/hud/`): card, art, progress, lyrics and mini layouts, each with
  independent settings persisted per element.
- Lyrics support: LRC fetching from [LRCLIB](https://lrclib.net/), parsing, and a scrolling
  lyrics HUD element with status handling (`LyricsService`, `LrclibFetcher`, `LrcParser`).
- Per-element HUD settings (`HudElementSettings`, `HudElementType`) with colour helpers
  (`ColorUtil`) and translated option labels (`TranslatableOption`).
- Album art disk cache under `config/spotifycontrol/artcache`.

### Changed

- HUD rendering split into painter/renderer/context classes; the overlay no longer blocks the
  render thread while art or lyrics load.
- `TrackInfo` snapshots are replaced wholesale, so the render thread never observes a half-updated
  track.

## [1.0.0]

### Added

- Initial release for Minecraft 1.21.11 (Fabric, client-side).
- Spotify Web API controller implementing OAuth 2.0 Authorization Code + PKCE against a loopback
  redirect (`http://localhost:8888/callback`); refresh tokens stored in `config/spotifycontrol.json`.
- Native OS media-session fallback: Windows media keys via a PowerShell bridge, Linux MPRIS over
  DBus, macOS `osascript`.
- Configurable HUD overlay (anchor, offsets, scale, opacity, text colour, shadow, album art,
  progress bar, marquee scrolling).
- Keybinds under `key.category.spotifycontrol` and the `/spotify` command tree.
- Mod Menu + Cloth Config settings screen; Cloth Config bundled Jar-in-Jar.

[Unreleased]: https://github.com/ObvGreen/SpotifyControlMod/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/ObvGreen/SpotifyControlMod/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/ObvGreen/SpotifyControlMod/releases/tag/v1.0.0
