package me.obvgreen.spotifycontrol.client.config;

import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-old-Java configuration object with Gson persistence.
 *
 * <p>The file lives at {@code .minecraft/config/spotifycontrol.json} (Fabric config directory) and is
 * written atomically: a temporary file is filled first and then moved over the target so a crash
 * mid-write can never leave a truncated configuration behind.</p>
 *
 * <p>OAuth tokens are stored here as well, which is the safest place available to a client-side mod:
 * the file never leaves the user's machine and no client secret is ever persisted (PKCE only).</p>
 *
 * <h2>HUD model</h2>
 * <p>The overlay is no longer one widget. It is a set of independent elements
 * ({@link HudElementType}) and each element owns its own switch, anchor, offsets, scale, size and
 * optional colour overrides through {@link HudElementSettings}. The fields prefixed {@code hud*} in
 * this class are the <em>global defaults</em> an element inherits when it leaves a value unset, and
 * they are also the settings the original single-panel HUD used, so old configuration files keep
 * working unchanged.</p>
 *
 * <p>Every colour is stored as an ARGB hex string ({@code AARRGGBB}) and converted through
 * {@link ColorUtil}. Alpha is preserved end to end - including in the Mod Menu colour picker, which
 * used to silently drop it.</p>
 */
public final class ModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Config");
    private static final String FILE_NAME = "spotifycontrol.json";

    /** Screen anchors the HUD elements can be pinned to. */
    public enum Anchor implements TranslatableOption {
        TOP_LEFT("Top Left", "spotifycontrol.config.hud.anchor.top_left"),
        TOP_CENTER("Top Center", "spotifycontrol.config.hud.anchor.top_center"),
        TOP_RIGHT("Top Right", "spotifycontrol.config.hud.anchor.top_right"),
        BOTTOM_LEFT("Bottom Left", "spotifycontrol.config.hud.anchor.bottom_left"),
        BOTTOM_CENTER("Bottom Center", "spotifycontrol.config.hud.anchor.bottom_center"),
        BOTTOM_RIGHT("Bottom Right", "spotifycontrol.config.hud.anchor.bottom_right");

        private final String displayName;
        private final String translationKey;

        Anchor(String displayName, String translationKey) {
            this.displayName = displayName;
            this.translationKey = translationKey;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        /** Translation key shown in the config screen. */
        @Override
        public String translationKey() {
            return translationKey;
        }

        /** Safe parse used when loading hand-edited config files. */
        public static Anchor fromName(String name, Anchor fallback) {
            if (name == null) {
                return fallback;
            }
            for (Anchor anchor : values()) {
                if (anchor.name().equalsIgnoreCase(name.trim())) {
                    return anchor;
                }
            }
            return fallback;
        }

        public boolean isRight() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean isBottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_CENTER || this == BOTTOM_RIGHT;
        }

        /** {@code true} for the two horizontally centred anchors. */
        public boolean isCentered() {
            return this == TOP_CENTER || this == BOTTOM_CENTER;
        }
    }

    /** How the panel behind an element is painted. */
    public enum PanelStyle implements TranslatableOption {
        SOLID("Solid", "spotifycontrol.config.hud.panel.solid"),
        GRADIENT("Gradient", "spotifycontrol.config.hud.panel.gradient"),
        OUTLINE("Outline", "spotifycontrol.config.hud.panel.outline"),
        NONE("None", "spotifycontrol.config.hud.panel.none");

        private final String displayName;
        private final String translationKey;

        PanelStyle(String displayName, String translationKey) {
            this.displayName = displayName;
            this.translationKey = translationKey;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public String translationKey() {
            return translationKey;
        }
    }

    /** How the played portion of a progress bar is drawn. */
    public enum ProgressStyle implements TranslatableOption {
        SOLID("Bar", "spotifycontrol.config.hud.progress.solid"),
        SHADED_GRADIENT("Gradient", "spotifycontrol.config.hud.progress.gradient"),
        SEGMENTS("Segments", "spotifycontrol.config.hud.progress.segments"),
        HAIRLINE("Hairline", "spotifycontrol.config.hud.progress.hairline");

        private final String displayName;
        private final String translationKey;

        ProgressStyle(String displayName, String translationKey) {
            this.displayName = displayName;
            this.translationKey = translationKey;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public String translationKey() {
            return translationKey;
        }
    }

    /** Where the lyrics window gets its text from. */
    public enum LyricsSource implements TranslatableOption {
        LRCLIB("LRCLIB (lrclib.net)", "spotifycontrol.config.lyrics.source.lrclib"),
        OFF("Disabled", "spotifycontrol.config.lyrics.source.off");

        private final String displayName;
        private final String translationKey;

        LyricsSource(String displayName, String translationKey) {
            this.displayName = displayName;
            this.translationKey = translationKey;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public String translationKey() {
            return translationKey;
        }
    }

    /** Which signal makes the ad skipper believe an advertisement is playing. */
    public enum AdTrigger implements TranslatableOption {
        UNKNOWN_ARTIST("Unknown artist", "spotifycontrol.config.ads.trigger.unknown"),
        KEYWORDS("Ad keywords", "spotifycontrol.config.ads.trigger.keywords"),
        EITHER("Unknown artist or keywords", "spotifycontrol.config.ads.trigger.either");

        private final String displayName;
        private final String translationKey;

        AdTrigger(String displayName, String translationKey) {
            this.displayName = displayName;
            this.translationKey = translationKey;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public String translationKey() {
            return translationKey;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HUD master switch and global defaults
    // ---------------------------------------------------------------------------------------------

    /** Master switch for the whole HUD overlay. */
    public boolean hudEnabled = true;
    /** Default screen anchor every element inherits when it has none of its own. */
    public Anchor hudAnchor = Anchor.TOP_LEFT;
    /** Default horizontal pixel offset from the anchor. */
    public int hudOffsetX = 6;
    /** Default vertical pixel offset from the anchor. */
    public int hudOffsetY = 6;
    /** Default HUD scale multiplier. */
    public float hudScale = 1.0F;
    /** Default background panel opacity, 0 - 255. */
    public int hudBackgroundOpacity = 110;
    /** Default ARGB hex used for primary HUD text. */
    public String hudTextColor = "FFFFFFFF";
    /** Default ARGB hex used for secondary text (artist, times). */
    public String hudSecondaryTextColor = "FFB8B8B8";
    /** Default ARGB hex used for tertiary text (album, device, hints). */
    public String hudTertiaryTextColor = "FF8A8A8A";
    /** ARGB hex of the accent colour (progress fill, status glyph, lyrics highlight). */
    public String hudAccentColor = "FF1DB954";
    /** ARGB hex of the panel background before the opacity slider is applied. */
    public String hudBackgroundColor = "FF000000";
    /** ARGB hex of the panel border. */
    public String hudBorderColor = "33FFFFFF";
    /** Draw a drop shadow behind HUD text. */
    public boolean hudTextShadow = true;
    /** Render the album artwork on the card / art element. */
    public boolean hudShowAlbumArt = true;
    /** Render the progress block. */
    public boolean hudShowProgress = true;
    /** Render the play/pause status glyph. */
    public boolean hudShowStatusIcon = true;
    /** Show the album name as a third text row. */
    public boolean hudShowAlbumName = false;
    /** Show elapsed / total time next to the progress bar. */
    public boolean hudShowTime = true;
    /** Show the active playback device. */
    public boolean hudShowDevice = false;
    /** Show the current repeat mode. */
    public boolean hudShowRepeat = false;
    /** Pulse the status glyph while music plays. */
    public boolean hudShowPlayPulse = true;
    /** Marquee-scroll titles that do not fit. */
    public boolean hudMarquee = true;
    /** Marquee speed in pixels per second. */
    public float hudMarqueeSpeed = 40.0F;
    /** Corner rounding of every panel, in local units. */
    public int hudCornerRadius = 3;
    /** Panel painting style. */
    public PanelStyle hudPanelStyle = PanelStyle.GRADIENT;
    /** Progress bar painting style. */
    public ProgressStyle hudProgressStyle = ProgressStyle.SHADED_GRADIENT;
    /** Panel width of the card element in local units. */
    public int hudCardWidth = 160;

    /**
     * Per-element overrides. One entry per {@link HudElementType}; {@link #normalise()} guarantees a
     * complete set, so hand-written or older files that only carry a subset are upgraded in place.
     */
    public List<HudElementSettings> hudElements = new ArrayList<>();

    // ---------------------------------------------------------------------------------------------
    // Lyrics settings
    // ---------------------------------------------------------------------------------------------

    /** Master switch for lyrics fetching and rendering. */
    public boolean lyricsEnabled = true;
    /** Backend used to look lyrics up. */
    public LyricsSource lyricsSource = LyricsSource.LRCLIB;
    /** Manual timing correction applied to the synced lyrics, in milliseconds. */
    public int lyricsOffsetMs = 0;
    /** Number of lyric lines rendered around the current one. */
    public int lyricsLines = 3;
    /** Highlight the current line progressively instead of all at once. */
    public boolean lyricsKaraoke = true;
    /** ARGB hex of a not-yet-sung lyric line. */
    public String lyricsTextColor = "FFB8B8B8";
    /** ARGB hex of the line being sung / the most recent one. */
    public String lyricsActiveColor = "FFFFFFFF";
    /** Keep the lyrics element visible (showing a hint) while nothing is playing. */
    public boolean lyricsShowPlaceholder = true;
    /** Uppercase the lyric lines for a cleaner look. */
    public boolean lyricsUppercase = false;

    // ---------------------------------------------------------------------------------------------
    // Authentication / API settings
    // ---------------------------------------------------------------------------------------------

    /** Spotify application Client ID (public value; PKCE means no secret is needed). */
    public String clientId = "";
    /** Loopback port used for the OAuth redirect URI. */
    public int redirectPort = 8888;
    /** Track polling interval in seconds, clamped to [1, 60]. */
    public int pollIntervalSeconds = 3;
    /** Fall back to native OS media controls when the Web API is not authenticated. */
    public boolean allowLocalFallback = true;
    /** Persisted OAuth token bundle, {@code null} while logged out. */
    @Nullable
    public TokenSet tokens;

    // ---------------------------------------------------------------------------------------------
    // Ad skipping
    // ---------------------------------------------------------------------------------------------

    /**
     * Master switch for the ad skipper. Spotify exposes no API to skip an advertisement, so the only
     * available trick is restarting the desktop client, which drops the ad slot and resumes playback.
     */
    public boolean adSkipEnabled = false;
    /** Which signal marks a snapshot as an advertisement. */
    public AdTrigger adSkipTrigger = AdTrigger.UNKNOWN_ARTIST;
    /** Comma/semicolon separated keywords matched against title, artist and album. */
    public String adSkipKeywords = DEFAULT_AD_KEYWORDS;
    /** Minimum seconds between two restarts, clamped to [15, 3600]. */
    public int adSkipCooldownSeconds = 90;
    /** Maximum restarts per rolling hour, clamped to [1, 30]. */
    public int adSkipMaxPerHour = 10;
    /** Grace period between killing and relaunching Spotify, clamped to [500, 15000]. */
    public int adSkipRestartDelayMs = 4000;
    /** Print a chat message whenever the skipper acts. */
    public boolean adSkipNotify = true;
    /** Press play after the relaunch when Spotify came back paused. */
    public boolean adSkipResumePlayback = true;

    /** Default keyword list; word-boundary matching keeps tracks like "Bad" or "Radio" safe. */
    public static final String DEFAULT_AD_KEYWORDS =
            "advertisement, advert, sponsored, werbung, publicite, publicidad, anuncio, "
                    + "pubblicita, reklam, anzeige";

    // ---------------------------------------------------------------------------------------------
    // Debug
    // ---------------------------------------------------------------------------------------------

    /** Verbose request/native-query logging. */
    public boolean debugLogging = false;

    // ---------------------------------------------------------------------------------------------
    // Resolved colours
    // ---------------------------------------------------------------------------------------------

    /** @return the configured primary text colour. */
    public int hudTextColorArgb() {
        return ColorUtil.parseArgb(hudTextColor, 0xFFFFFFFF);
    }

    /** @return the configured secondary text colour. */
    public int hudSecondaryTextColorArgb() {
        return ColorUtil.parseArgb(hudSecondaryTextColor, 0xFFB8B8B8);
    }

    /** @return the configured tertiary text colour. */
    public int hudTertiaryTextColorArgb() {
        return ColorUtil.parseArgb(hudTertiaryTextColor, 0xFF8A8A8A);
    }

    /** @return the configured accent colour. */
    public int hudAccentColorArgb() {
        return ColorUtil.parseArgb(hudAccentColor, 0xFF1DB954);
    }

    /** @return the panel background colour with {@link #hudBackgroundOpacity} applied. */
    public int hudBackgroundArgb() {
        return ColorUtil.withAlpha(ColorUtil.parseArgb(hudBackgroundColor, 0xFF000000),
                Math.max(0, Math.min(255, hudBackgroundOpacity)));
    }

    /** @return the panel border colour. */
    public int hudBorderArgb() {
        return ColorUtil.parseArgb(hudBorderColor, 0x33FFFFFF);
    }

    /** @return the colour of a not-yet-sung lyric line. */
    public int lyricsTextColorArgb() {
        return ColorUtil.parseArgb(lyricsTextColor, 0xFFB8B8B8);
    }

    /** @return the colour of the active lyric line. */
    public int lyricsActiveColorArgb() {
        return ColorUtil.parseArgb(lyricsActiveColor, 0xFFFFFFFF);
    }

    /**
     * Backwards-compatible accessor for the primary HUD text colour.
     *
     * @return the configured text colour, never {@code 0}
     */
    public int textColorArgb() {
        return hudTextColorArgb();
    }

    // ---------------------------------------------------------------------------------------------
    // Element access
    // ---------------------------------------------------------------------------------------------

    /**
     * Looks an element up, creating it with its defaults when the configuration predates it.
     *
     * @param type element to fetch
     * @return the stored settings, never {@code null}
     */
    public HudElementSettings element(HudElementType type) {
        for (HudElementSettings settings : hudElements) {
            if (settings != null && settings.type == type) {
                return settings;
            }
        }
        HudElementSettings created = HudElementSettings.defaults(type);
        hudElements.add(created);
        return created;
    }

    /**
     * @param type element to test
     * @return {@code true} when the element is switched on and the HUD as a whole is enabled
     */
    public boolean isElementVisible(HudElementType type) {
        return hudEnabled && element(type).enabled;
    }

    /** Flips one element's switch and returns its new state. */
    public boolean toggleElement(HudElementType type) {
        HudElementSettings settings = element(type);
        settings.enabled = !settings.enabled;
        save();
        return settings.enabled;
    }

    /** Flips the lyrics master switch and returns its new state. */
    public boolean toggleLyrics() {
        lyricsEnabled = !lyricsEnabled;
        save();
        return lyricsEnabled;
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------

    /** Clamped polling interval in milliseconds. */
    public long pollIntervalMillis() {
        int seconds = Math.max(1, Math.min(60, pollIntervalSeconds));
        return seconds * 1000L;
    }

    /** The OAuth redirect URI implied by {@link #redirectPort}. */
    public String redirectUri() {
        return "http://127.0.0.1:" + Math.max(1, Math.min(65535, redirectPort)) + "/callback";
    }

    /** {@code true} when a Client ID has been configured. */
    public boolean hasClientId() {
        return clientId != null && !clientId.isBlank();
    }

    /** @return the trimmed Client ID (never {@code null}). */
    public String clientId() {
        return clientId == null ? "" : clientId.trim();
    }

    /** @return the stored tokens, or {@code null} when logged out. */
    @Nullable
    public TokenSet tokens() {
        return tokens;
    }

    /** Stores (or clears) the OAuth token bundle and persists the config. */
    public void setTokens(@Nullable TokenSet tokens) {
        this.tokens = tokens;
        save();
    }

    /** @return the config file location inside the Fabric config directory. */
    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * Loads the configuration from disk, creating a default file when none exists.
     * Never throws: on a corrupt file the defaults are used and the bad file is left untouched
     * for inspection (a {@code .bak} copy is attempted instead of destroying user data).
     */
    public static ModConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            ModConfig fresh = new ModConfig();
            fresh.save();
            return fresh;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ModConfig loaded = ConfigHolder.GSON.fromJson(reader, ModConfig.class);
            if (loaded == null) {
                LOGGER.warn("Config file {} was empty, using defaults.", path);
                return new ModConfig();
            }
            loaded.normalise();
            return loaded;
        } catch (Exception exception) {
            LOGGER.error("Failed to read {}, using defaults. Cause: {}", path, exception.toString());
            return new ModConfig();
        }
    }

    /** Writes the configuration atomically to disk. Never throws. */
    public void save() {
        normalise();
        Path path = configPath();
        Path temp = path.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                ConfigHolder.GSON.toJson(this, writer);
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to write {}: {}", path, exception.toString());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing else we can do.
            }
        }
    }

    /** Forces every field into a sane range; called before save and after load. */
    public void normalise() {
        if (hudAnchor == null) {
            hudAnchor = Anchor.TOP_LEFT;
        }
        if (hudPanelStyle == null) {
            hudPanelStyle = PanelStyle.GRADIENT;
        }
        if (hudProgressStyle == null) {
            hudProgressStyle = ProgressStyle.SHADED_GRADIENT;
        }
        if (lyricsSource == null) {
            lyricsSource = LyricsSource.LRCLIB;
        }
        if (Float.isNaN(hudScale) || Float.isInfinite(hudScale)) {
            hudScale = 1.0F;
        }
        hudScale = Math.max(0.25F, Math.min(4.0F, hudScale));
        if (Float.isNaN(hudMarqueeSpeed) || Float.isInfinite(hudMarqueeSpeed)) {
            hudMarqueeSpeed = 40.0F;
        }
        hudMarqueeSpeed = Math.max(5.0F, Math.min(200.0F, hudMarqueeSpeed));
        hudBackgroundOpacity = Math.max(0, Math.min(255, hudBackgroundOpacity));
        hudOffsetX = Math.max(-4000, Math.min(4000, hudOffsetX));
        hudOffsetY = Math.max(-4000, Math.min(4000, hudOffsetY));
        hudCornerRadius = Math.max(0, Math.min(12, hudCornerRadius));
        hudCardWidth = Math.max(80, Math.min(480, hudCardWidth));
        lyricsLines = Math.max(1, Math.min(11, lyricsLines));
        lyricsOffsetMs = Math.max(-30000, Math.min(30000, lyricsOffsetMs));

        // A colour that fails to parse and lands on fully transparent black is treated as "unset",
        // so a hand-edited typo can never blank the HUD out.
        hudTextColor = recover(hudTextColor, "FFFFFFFF");
        hudSecondaryTextColor = recover(hudSecondaryTextColor, "FFB8B8B8");
        hudTertiaryTextColor = recover(hudTertiaryTextColor, "FF8A8A8A");
        hudAccentColor = recover(hudAccentColor, "FF1DB954");
        hudBackgroundColor = recover(hudBackgroundColor, "FF000000");
        hudBorderColor = recover(hudBorderColor, "33FFFFFF");
        lyricsTextColor = recover(lyricsTextColor, "FFB8B8B8");
        lyricsActiveColor = recover(lyricsActiveColor, "FFFFFFFF");

        if (clientId == null) {
            clientId = "";
        }
        redirectPort = Math.max(1024, Math.min(65535, redirectPort));
        pollIntervalSeconds = Math.max(1, Math.min(60, pollIntervalSeconds));

        if (adSkipTrigger == null) {
            adSkipTrigger = AdTrigger.UNKNOWN_ARTIST;
        }
        if (adSkipKeywords == null || adSkipKeywords.isBlank()) {
            adSkipKeywords = DEFAULT_AD_KEYWORDS;
        }
        adSkipCooldownSeconds = clamp(adSkipCooldownSeconds, 15, 3600);
        adSkipMaxPerHour = clamp(adSkipMaxPerHour, 1, 30);
        adSkipRestartDelayMs = clamp(adSkipRestartDelayMs, 500, 15000);

        // Guarantee exactly one settings object per element type, in declaration order.
        List<HudElementSettings> normalisedElements = new ArrayList<>(HudElementType.values().length);
        for (HudElementType type : HudElementType.values()) {
            HudElementSettings settings = null;
            if (hudElements != null) {
                for (HudElementSettings candidate : hudElements) {
                    if (candidate != null && candidate.type == type) {
                        settings = candidate;
                        break;
                    }
                }
            }
            if (settings == null) {
                settings = HudElementSettings.defaults(type);
            }
            settings.normalise();
            normalisedElements.add(settings);
        }
        hudElements = normalisedElements;
    }

    /** @return {@code raw} when it parses to a visible-enough colour, {@code fallback} otherwise. */
    private static String recover(@Nullable String raw, String fallback) {
        int parsed = ColorUtil.parseArgb(raw, 0);
        if (parsed == 0) {
            return fallback;
        }
        return ColorUtil.toHex(parsed);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** @return the ad-skip cooldown in milliseconds. */
    public long adSkipCooldownMillis() {
        return Math.max(15, adSkipCooldownSeconds) * 1000L;
    }

    /** @return the delay between killing and relaunching Spotify, in milliseconds. */
    public long adSkipRestartDelay() {
        return Math.max(500, adSkipRestartDelayMs);
    }

    /** Lazily created Gson instance (pretty printing keeps hand-editing pleasant). */
    private static final class ConfigHolder {
        private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();
    }
}
