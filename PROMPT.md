---

```markdown
# Role & Task
You are an expert Fabric Minecraft mod developer specializing in Java 21, Fabric Loader, and Client-side API integrations for Minecraft 1.21.1. Your task is to write the COMPLETE, fully functional code for a Minecraft 1.21.1 Fabric client-side mod that controls Spotify playback, displays a custom HUD overlay, provides keybinds, registers commands, and integrates with Mod Menu.

---

## Technical Specifications
- **Target Version:** Minecraft 1.21.1
- **Mod Loader:** Fabric (Fabric API 0.100.0+ for 1.21.1)
- **Target Java Version:** Java 21
- **Environment:** Client-Only (`client` side execution)
- **Dependencies:**
  - Fabric API (`fabric-api`)
  - Mod Menu (`modmenu`)
  - Cloth Config v15+ (`cloth-config-fabric`)

---

## Core Features Required

### 1. Spotify Control Engine (`com.spotifycontrol.mod.spotify`)
Implement a robust Spotify controller supporting two playback/metadata fetch modes:
- **Primary Method (OAuth 2.0 PKCE / Web API):** Safe authentication using Spotify Web API PKCE flow (no client secret stored). Spin up a lightweight local HTTP loopback server (`http://localhost:8888/callback`) to capture the authorization code seamlessly, obtain access/refresh tokens, and store them securely in the mod's configuration file.
- **Secondary / Fallback Method (Local OS Media Controls / Local API):** Interface with Windows System Media Transport Controls (SMTC) or Linux DBus MPRIS / Spotify Local Port to read track information and trigger `Play/Pause`, `Next`, `Previous`, and `Toggle Repeat` without requiring user login if preferred.
- **Supported Actions:** `playPause()`, `nextTrack()`, `previousTrack()`, `toggleRepeat()`, `getCurrentlyPlayingTrack()`.
- **Track Metadata Record:** Store `title`, `artist`, `album`, `albumArtUrl` (or `NativeImage` texture), `durationMs`, `progressMs`, and `isPlaying`.

### 2. HUD Overlay (`com.spotifycontrol.mod.client.gui`)
- Register a custom HUD overlay using `HudRenderCallback` / `DrawContext`.
- **Display Features:**
  - Song Title & Artist name (with text scrolling/truncation if too long).
  - Track Progress Bar (elapsed time vs. total duration).
  - Album Art rendering (downloaded asynchronously, bound to a `DynamicTexture` identifier, and cached in memory).
  - Visual status indicator (Playing / Paused icon).
- **Positioning & Scaling:** Configurable screen anchor (Top-Left, Top-Right, Bottom-Left, Bottom-Right), X/Y offsets, scale slider, and opacity slider.

### 3. Keybind Integration (`com.spotifycontrol.mod.client.keybinds`)
Register client keybindings using `KeyBindingHelper` under a dedicated category (`key.category.spotifycontrol`):
- `Play / Pause` (Default: `UNBOUND` or configurable)
- `Next Track`
- `Previous Track`
- `Toggle Repeat Mode` (Off -> Context -> Track)
- `Toggle HUD Visibility`

Process key input during `ClientTickEvents.END_CLIENT_TICK`.

### 4. Client Commands (`com.spotifycontrol.mod.client.command`)
Register client-side command `/spotify` using `ClientCommandRegistrationCallback` and Brigadier:
- `/spotify play` / `/spotify pause` / `/spotify toggle`
- `/spotify next` / `/spotify skip`
- `/spotify prev`
- `/spotify repeat`
- `/spotify hud toggle`
- `/spotify status` (prints current song into client chat formatted in color)

### 5. Configuration & Mod Menu (`com.spotifycontrol.mod.client.config`)
- Implement `ModMenuIntegration` implementing `ModMenuApi` to hook into Mod Menu.
- Use **Cloth Config** to build a settings screen containing:
  - **HUD Settings:** Enable/Disable HUD, Anchor Position, X Offset, Y Offset, Scale, Background Opacity, Text Color.
  - **Authentication / API Settings:** Spotify Client ID field, Login/Logout button, Polling Interval (in seconds).
  - **Keybind Shortcuts:** Direct link to controls menu.

---

## Strict File & Package Structure Requirements
Provide the full, un-truncated, production-ready code for every single file. DO NOT use placeholders like `// TODO: implement this` or `// ... rest of code`. Group code cleanly into the following structure:


```

src/main/resources/
├── fabric.mod.json
└── assets/spotifycontrol/
└── lang/
└── en_us.json

src/main/java/com/spotifycontrol/mod/
├── SpotifyControlMod.java                          (Main Mod Initializer)
├── client/
│   ├── SpotifyControlClient.java                   (ClientModInitializer)
│   ├── config/
│   │   ├── ModConfig.java                          (Configuration POJO & Persistence)
│   │   └── ModMenuIntegration.java                 (ModMenuApi Implementation)
│   ├── gui/
│   │   ├── SpotifyHudOverlay.java                  (HudRenderCallback Implementation)
│   │   └── TextureManager.java                     (Async Image Downloader & Texture Binding)
│   ├── keybinds/
│   │   └── SpotifyKeybinds.java                    (Keybinding Registration & Handlers)
│   ├── command/
│   │   └── SpotifyCommand.java                     (Client Command Registration)
│   └── spotify/
│       ├── SpotifyService.java                     (Spotify Controller Interface)
│       ├── SpotifyWebApiClient.java                (OAuth PKCE & Web API Client)
│       ├── SpotifyLocalController.java             (OS Local Media / Fallback Controller)
│       └── TrackInfo.java                          (Data Record for Currently Playing Song)

```

---

## Implementation Rules
1. **Thread Safety:** All HTTP networking and Spotify API requests MUST run asynchronously on background threads or via `CompletableFuture`. NEVER block the main Minecraft render/tick thread.
2. **Texture Binding:** When downloading album art, execute the network fetch off-thread, convert the byte stream into a `NativeImage`, and register it on the main thread via `MinecraftClient.getInstance().execute(...)` using `TextureManager`.
3. **Error Handling:** If Spotify is closed, unreachable, or unauthenticated, handle errors gracefully without crashing the client. Render a subtle `Spotify Offline` or `Not Playing` state on the HUD.
4. **Code Completeness:** Write out EVERY import statement, annotation, class definition, helper method, and JSON configuration completely.

```

main package: me.obvgreen
author: ObvGreen