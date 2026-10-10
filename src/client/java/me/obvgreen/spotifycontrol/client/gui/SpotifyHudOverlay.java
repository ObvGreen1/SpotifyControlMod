package me.obvgreen.spotifycontrol.client.gui;

import java.util.List;
import java.util.Map;

import me.obvgreen.spotifycontrol.client.config.HudElementType;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.gui.hud.HudElementRenderer;
import me.obvgreen.spotifycontrol.client.gui.hud.HudElements;
import me.obvgreen.spotifycontrol.client.gui.hud.HudLayout;
import me.obvgreen.spotifycontrol.client.gui.hud.HudRenderContext;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsService;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsSnapshot;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the Spotify HUD.
 *
 * <p>Each enabled {@link HudElementType} is measured and drawn independently: the overlay only
 * resolves the element's anchor, applies its offset and scale, and clips the work off-screen. All
 * looks live in the element renderers under {@code gui.hud}, and every shared per-frame value lives in
 * {@link HudRenderContext}.</p>
 *
 * <p>This class is called from the render thread, so it must never block. Album artwork comes from
 * {@link AlbumArtCache}, which returns {@code null} until the texture has been uploaded; elements draw
 * a placeholder in the meantime.</p>
 */
public final class SpotifyHudOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Hud");

    /** Width of the default card in local units, kept for callers that only need a rough size. */
    public static final int BASE_WIDTH = 160;
    /** Height of the default card in local units. */
    public static final int BASE_HEIGHT = 40;

    private final ModConfig config;
    private final AlbumArtCache artCache;
    @Nullable
    private final LyricsService lyrics;
    private final List<HudElementRenderer> renderers = HudElements.createAll();
    private final Map<HudElementType, HudElementRenderer> renderersByType = HudElements.byType();

    /** Continuously increasing animation clock, advanced once per rendered frame. */
    private float animationClock;
    /** Seconds the marquee holds still before it starts scrolling again. */
    private float marqueePause;

    public SpotifyHudOverlay(ModConfig config, AlbumArtCache artCache) {
        this(config, artCache, null);
    }

    public SpotifyHudOverlay(ModConfig config, AlbumArtCache artCache, @Nullable LyricsService lyrics) {
        this.config = config;
        this.artCache = artCache;
        this.lyrics = lyrics;
    }

    // ---------------------------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------------------------

    /**
     * Renders every enabled element.
     *
     * @param context      the draw context provided by {@code HudRenderCallback}
     * @param tickDelta    the partial tick time of the current frame
     * @param frameSeconds seconds elapsed since the previous frame, used so animation speed is
     *                     frame-rate independent
     * @param track        the most recent snapshot; may be offline or empty
     */
    public void render(DrawContext context, float tickDelta, float frameSeconds, TrackInfo track) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || client.options.hudHidden) {
            return;
        }
        if (!config.hudEnabled) {
            return;
        }
        float delta = HudRenderContext.clampDelta(frameSeconds);
        animationClock += Math.max(tickDelta, 0.0F) / 20.0F;
        if (marqueePause > 0.0F) {
            marqueePause -= delta;
        }

        TrackInfo snapshot = track == null ? TrackInfo.offlineSnapshot() : track;
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();

        for (HudElementRenderer renderer : renderers) {
            HudElementType type = renderer.type();
            if (!config.isElementVisible(type)) {
                continue;
            }
            HudLayout layout = HudLayout.resolve(config, type);
            if (layout.scale() <= 0.0F) {
                continue;
            }
            HudRenderContext frame = createContext(context, client, snapshot, layout, screenWidth, screenHeight);
            try {
                if (!renderer.visible(frame, layout)) {
                    continue;
                }
                int width = Math.max(1, renderer.measureWidth(frame, layout));
                int height = Math.max(1, renderer.measureHeight(frame, layout));
                float scale = layout.scale();
                int x = anchorX(layout, screenWidth, width, scale);
                int y = anchorY(layout, screenHeight, height, scale);
                if (x > screenWidth || y > screenHeight || x + width * scale < 0.0F || y + height * scale < 0.0F) {
                    continue;
                }
                paint(context, frame, renderer, layout, width, height, x, y);
            } catch (RuntimeException exception) {
                // A rendering failure must never take the whole HUD down with it.
                LOGGER.error("Failed to render HUD element {}", type, exception);
            }
        }
    }

    private HudRenderContext createContext(DrawContext context, MinecraftClient client, TrackInfo snapshot,
                                           HudLayout layout, int screenWidth, int screenHeight) {
        long now = System.currentTimeMillis();
        long drift = HudRenderContext.driftMs(snapshot, now);
        long duration = snapshot.durationMs();
        long progress = snapshot.progressMs() + drift;
        if (duration > 0L) {
            progress = Math.min(progress, duration);
        }
        LyricsSnapshot lyricsSnapshot = lyrics == null ? LyricsSnapshot.idle() : lyrics.snapshot();
        boolean synced = lyrics != null && lyrics.canSync(snapshot);
        return new HudRenderContext(context, client.textRenderer, snapshot, config, artCache, lyricsSnapshot,
                synced, Math.max(0L, progress), duration, drift, marqueeOffset(), animationClock, screenWidth,
                screenHeight);
    }

    /** Draws one element inside its own translated and scaled matrix. */
    private void paint(DrawContext context, HudRenderContext frame, HudElementRenderer renderer, HudLayout layout,
                       int width, int height, int x, int y) {
        // 1.21.9+ replaced the old MatrixStack with a 2D-only transform stack; draw order is carried by
        // the GUI render state instead of a z offset, so only translate and scale are needed here.
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate((float) x, (float) y);
        matrices.scale(layout.scale(), layout.scale());
        try {
            renderer.render(frame, layout, width, height);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to draw HUD element {}", renderer.type(), exception);
        } finally {
            matrices.popMatrix();
        }
    }

    /**
     * Draws one element at an explicit GUI position, used by the configuration screen preview.
     *
     * @param context      draw context of the hosting screen
     * @param type         element to draw
     * @param track        snapshot to render, may be {@code null}
     * @param x            left edge in GUI coordinates
     * @param y            top edge in GUI coordinates
     * @param frameSeconds frame delta, used for animation
     */
    public void renderPreview(DrawContext context, HudElementType type, @Nullable TrackInfo track, int x, int y,
                              float frameSeconds) {
        MinecraftClient client = MinecraftClient.getInstance();
        HudElementRenderer renderer = renderersByType.get(type);
        if (client == null || renderer == null) {
            return;
        }
        animationClock += HudRenderContext.clampDelta(frameSeconds);
        TrackInfo snapshot = track == null ? TrackInfo.offlineSnapshot() : track;
        HudLayout layout = HudLayout.resolve(config, type);
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        HudRenderContext frame = createContext(context, client, snapshot, layout, screenWidth, screenHeight);
        try {
            int width = Math.max(1, renderer.measureWidth(frame, layout));
            int height = Math.max(1, renderer.measureHeight(frame, layout));
            paint(context, frame, renderer, layout, width, height, x, y);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to preview HUD element {}", type, exception);
        }
    }

    /** @return the measured size of one element in local units, for layout previews. */
    public int[] measure(HudElementType type, @Nullable TrackInfo track) {
        MinecraftClient client = MinecraftClient.getInstance();
        HudElementRenderer renderer = renderersByType.get(type);
        if (client == null || renderer == null) {
            return new int[]{1, 1};
        }
        TrackInfo snapshot = track == null ? TrackInfo.offlineSnapshot() : track;
        HudLayout layout = HudLayout.resolve(config, type);
        HudRenderContext frame = createContext(null, client, snapshot, layout, 0, 0);
        return new int[]{Math.max(1, renderer.measureWidth(frame, layout)),
                Math.max(1, renderer.measureHeight(frame, layout))};
    }

    private static int anchorX(HudLayout layout, int screenWidth, int width, float scale) {
        if (layout.anchor().isCentered()) {
            return Math.round((screenWidth - width * scale) / 2.0F) + layout.offsetX();
        }
        if (layout.anchor().isRight()) {
            return screenWidth - Math.round(width * scale) - layout.offsetX();
        }
        return layout.offsetX();
    }

    private static int anchorY(HudLayout layout, int screenHeight, int height, float scale) {
        if (layout.anchor().isBottom()) {
            return screenHeight - Math.round(height * scale) - layout.offsetY();
        }
        return layout.offsetY();
    }

    // ---------------------------------------------------------------------------------------------
    // State shared with the rest of the client
    // ---------------------------------------------------------------------------------------------

    /** Restarts the marquee pause; called whenever the track identity changes. */
    public void resetMarquee() {
        marqueePause = 1.5F;
    }

    /** @return the scrolled distance in local pixels, or {@code 0} while the marquee holds still. */
    private float marqueeOffset() {
        return marqueePause > 0.0F ? 0.0F : animationClock * config.hudMarqueeSpeed;
    }

    /** @return the width of the card element in screen pixels. */
    public int scaledWidth() {
        HudLayout layout = HudLayout.resolve(config, HudElementType.CARD);
        return Math.round(layout.size() * layout.scale());
    }

    /** @return the estimated height of the card element in screen pixels. */
    public int scaledHeight() {
        HudLayout layout = HudLayout.resolve(config, HudElementType.CARD);
        return Math.round(BASE_HEIGHT * layout.scale());
    }

    // Localised string with a hard-coded English fallback so a missing key never leaks.
    static String tr(String key, String fallback) {
        Text translated = Text.translatable(key);
        String value = translated.getString();
        return value.equals(key) ? fallback : value;
    }
}
