package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.ColorUtil;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.gui.AlbumArtCache;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsSnapshot;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a HUD element needs for one frame: the draw context, the current track, the lyrics state
 * and the values that are shared between elements (interpolated playback position, marquee clock).
 *
 * <p>The object is created once per frame and read-only afterwards, so elements can freely pass it
 * around without owning any state themselves.</p>
 */
public final class HudRenderContext {

    private final GuiGraphics context;
    private final Font renderer;
    private final TrackInfo track;
    private final ModConfig config;
    private final AlbumArtCache artCache;
    private final LyricsSnapshot lyrics;
    private final boolean lyricsSynced;
    private final long progressMs;
    private final long durationMs;
    private final float marqueeOffset;
    private final float animationClock;
    private final int screenWidth;
    private final int screenHeight;
    private final boolean playing;
    private final boolean offline;

    /** Duration of the gap between the last update and "now" applied to the progress when playing. */
    private final long driftMs;

    public HudRenderContext(GuiGraphics context, Font renderer, TrackInfo track, ModConfig config,
                            AlbumArtCache artCache, LyricsSnapshot lyrics, boolean lyricsSynced, long progressMs,
                            long durationMs, long driftMs, float marqueeOffset, float animationClock,
                            int screenWidth, int screenHeight) {
        this.context = context;
        this.renderer = renderer;
        this.track = track;
        this.config = config;
        this.artCache = artCache;
        this.lyrics = lyrics;
        this.lyricsSynced = lyricsSynced;
        this.progressMs = progressMs;
        this.durationMs = durationMs;
        this.driftMs = driftMs;
        this.marqueeOffset = marqueeOffset;
        this.animationClock = animationClock;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.playing = track.isPlaying();
        this.offline = track.offline();
    }

    public GuiGraphics context() {
        return context;
    }

    public Font renderer() {
        return renderer;
    }

    public TrackInfo track() {
        return track;
    }

    public ModConfig config() {
        return config;
    }

    public AlbumArtCache artCache() {
        return artCache;
    }

    public LyricsSnapshot lyrics() {
        return lyrics;
    }

    public float animationClock() {
        return animationClock;
    }

    /** @return pixels the marquee has travelled; {@code 0} while it is still holding still. */
    public float marqueeOffset() {
        return marqueeOffset;
    }

    public float marqueeSpeed() {
        return config.hudMarqueeSpeed;
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    public boolean playing() {
        return playing;
    }

    public boolean offline() {
        return offline;
    }

    /** @return the interpolated playback position, clamped to the track duration. */
    public long progressMs() {
        return progressMs;
    }

    public long durationMs() {
        return durationMs;
    }

    /** @return the playback position including the lyrics offset, clamped to the duration. */
    public long lyricsPositionMs() {
        long offset = config.lyricsOffsetMs;
        long value = progressMs + offset;
        if (durationMs > 0L) {
            value = Math.max(0L, Math.min(durationMs, value));
        }
        return Math.max(0L, value);
    }

    /** @return {@code true} when the lyrics can be highlighted against the playback position. */
    public boolean lyricsSynced() {
        return lyricsSynced;
    }

    /** @return played fraction in {@code 0..1}, or {@code -1} when the duration is unknown. */
    public float progressFraction() {
        if (durationMs <= 0L) {
            return -1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, progressMs / (float) durationMs));
    }

    /** @return whether any track is currently known. */
    public boolean hasTrack() {
        return !offline && !TrackInfo.UNKNOWN_TITLE.equals(track.title());
    }

    /** @return the album art texture, or {@code null} while it is still downloading. */
    @Nullable
    public net.minecraft.resources.Identifier albumArt() {
        return artCache.textureFor(track.albumArtUrl());
    }

    // ---------------------------------------------------------------------------------------------
    // Shared strings and animation helpers
    // ---------------------------------------------------------------------------------------------

    /** @return the status colour of the little state glyph. */
    public int statusColor(HudLayout layout) {
        if (offline || !hasTrack()) {
            return 0xFFE53935;
        }
        return playing ? layout.accentColor() : 0xFFFFC107;
    }

    /**
     * @param layout element layout
     * @return a pulsing factor in {@code 0.55..1} used to breathe the status glyph while playing
     */
    public float pulse(HudLayout layout) {
        if (!config.hudShowPlayPulse || !playing) {
            return 1.0F;
        }
        double wave = 0.5D + 0.5D * Math.sin(animationClock * 3.2D);
        return (float) (0.55D + 0.45D * wave);
    }

    /** Localised string with a hard-coded English fallback so a missing key never leaks. */
    public String tr(String key, String fallback) {
        Component translated = Component.translatable(key);
        String value = translated.getString();
        return value.equals(key) ? fallback : value;
    }

    /** Convenience wrapper around {@link HudPainter#formatTime(long)}. */
    public String time(long millis) {
        return HudPainter.formatTime(millis);
    }

    /** @return the current track title, or an offline placeholder. */
    public String title() {
        if (offline) {
            return tr("spotifycontrol.hud.offline", "Spotify offline");
        }
        if (!hasTrack()) {
            return tr("spotifycontrol.hud.idle", "Nothing playing");
        }
        return track.title();
    }

    /** @return the current artist, or the status text when nothing plays. */
    public String artist() {
        if (offline) {
            return tr("spotifycontrol.hud.offline.hint", "Connect Spotify to see playback");
        }
        if (!hasTrack()) {
            return tr("spotifycontrol.hud.idle.hint", "Use /spotify auth to connect");
        }
        return track.artist();
    }

    /** @return the device / repeat suffix shown by the card when enabled, never {@code null}. */
    public String deviceLine() {
        StringBuilder builder = new StringBuilder();
        if (config.hudShowDevice && track.device() != null && !track.device().isBlank()) {
            builder.append(track.device());
        }
        if (config.hudShowRepeat && track.repeatMode() != null
                && track.repeatMode() != me.obvgreen.spotifycontrol.client.spotify.RepeatMode.OFF) {
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append(tr("spotifycontrol.hud.repeat." + track.repeatMode().name().toLowerCase(java.util.Locale.ROOT),
                    track.repeatMode().name()));
        }
        return builder.toString();
    }

    /** @return the frame delta seconds used for animation, clamped to a sane range. */
    public static float clampDelta(float frameSeconds) {
        return Math.min(Math.max(frameSeconds, 0.0F), 0.5F);
    }

    /** @return the number of milliseconds a track has advanced since its snapshot was published. */
    public static long driftMs(TrackInfo track, long nowMs) {
        if (!track.isPlaying()) {
            return 0L;
        }
        long drift = nowMs - track.lastUpdated();
        return Math.max(0L, Math.min(600_000L, drift));
    }

    /** @return the client's text renderer, or {@code null} before the client finished booting. */
    @Nullable
    public static Font textRenderer() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : client.font;
    }

    /** @return a translucent variant of {@code color}. */
    public static int alpha(int color, float factor) {
        return ColorUtil.scaleAlpha(color, factor);
    }
}
