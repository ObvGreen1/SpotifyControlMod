# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
