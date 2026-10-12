package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.ColorUtil;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Locale;

/**
 * Low level drawing helpers shared by every HUD element.
 *
 * <p>All coordinates are in "local units" - the units of the element's own coordinate space, before
 * the per-element scale is applied. The overlay pushes a translate + scale matrix around each element,
 * so {@link DrawContext#enableScissor(int, int, int, int)} (which transforms its rectangle by the
 * active matrix, verified against the 1.21.11 bytecode) clips in these same local units.</p>
 *
 * <p>Rounded corners are emulated with per-row horizontal fills: {@code DrawContext} has no rounded
 * rectangle primitive and pulling in a shader for a HUD panel would be wasteful.</p>
 */
public final class HudPainter {

    /** Alpha applied to the track (unfilled) portion of a progress bar. */
    public static final int BAR_TRACK_ALPHA = 0x50;

    private HudPainter() {
    }

    // ---------------------------------------------------------------------------------------------
    // Rounded rectangles
    // ---------------------------------------------------------------------------------------------

    /** Horizontal inset of a rounded rectangle row, {@code row} being 0-based from the top. */
    private static int inset(int row, int height, int radius) {
        int r = Math.min(radius, Math.min(height, 0) == 0 ? radius : height / 2);
        if (r <= 0) {
            return 0;
        }
        int distance;
        if (row < r) {
            distance = row;
        } else if (row >= height - r) {
            distance = height - 1 - row;
        } else {
            return 0;
        }
        double dy = r - distance - 0.5D;
        double dx = Math.sqrt(Math.max(0.0D, (double) r * r - dy * dy));
        return (int) Math.round(r - dx);
    }

    /** Fills a rounded rectangle. */
    public static void fillRounded(DrawContext context, int x0, int y0, int x1, int y1, int radius, int color) {
        if (ColorUtil.alpha(color) == 0 || x1 <= x0 || y1 <= y0) {
            return;
        }
        int height = y1 - y0;
        for (int row = 0; row < height; row++) {
            int pad = inset(row, height, radius);
            int left = x0 + pad;
            int right = x1 - pad;
            if (right > left) {
                context.fill(left, y0 + row, right, y0 + row + 1, color);
            }
        }
    }

    /** Fills a rounded rectangle with a vertical gradient. */
    public static void fillRoundedGradient(DrawContext context, int x0, int y0, int x1, int y1, int radius,
                                           int topColor, int bottomColor) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int height = y1 - y0;
        for (int row = 0; row < height; row++) {
            int pad = inset(row, height, radius);
            int left = x0 + pad;
            int right = x1 - pad;
            if (right <= left) {
                continue;
            }
            float t = height <= 1 ? 0.0F : row / (float) (height - 1);
            context.fill(left, y0 + row, right, y0 + row + 1, ColorUtil.lerp(topColor, bottomColor, t));
        }
    }

    /** Strokes a one pixel rounded outline. */
    public static void strokeRounded(DrawContext context, int x0, int y0, int x1, int y1, int radius, int color) {
        if (ColorUtil.alpha(color) == 0 || x1 - x0 < 2 || y1 - y0 < 2) {
            return;
        }
        int height = y1 - y0;
        int topInset = inset(0, height, radius);
        for (int x = x0 + topInset; x < x1 - topInset; x++) {
            context.fill(x, y0, x + 1, y0 + 1, color);
            context.fill(x, y1 - 1, x + 1, y1, color);
        }
        for (int row = 0; row < height; row++) {
            int pad = inset(row, height, radius);
            context.fill(x0 + pad, y0 + row, x0 + pad + 1, y0 + row + 1, color);
            context.fill(x1 - pad - 1, y0 + row, x1 - pad, y0 + row + 1, color);
        }
    }

    /**
     * Paints a panel background according to the configured style.
     *
     * @param style        how to paint
     * @param background   background colour, alpha already applied
     * @param border       border colour
     */
    public static void panel(DrawContext context, ModConfig.PanelStyle style, int x0, int y0, int x1, int y1,
                             int radius, int background, int border) {
        if (style == null || style == ModConfig.PanelStyle.NONE) {
            return;
        }
        int baseAlpha = ColorUtil.alpha(background);
        switch (style) {
            case SOLID -> fillRounded(context, x0, y0, x1, y1, radius, background);
            case GRADIENT -> {
                // Keep the configured opacity: the gradient only shifts the RGB channels.
                int top = ColorUtil.withAlpha(ColorUtil.lerp(background, 0xFFFFFFFF, 0.10F), baseAlpha);
                int bottom = ColorUtil.withAlpha(ColorUtil.lerp(background, 0xFF000000, 0.22F), baseAlpha);
                fillRoundedGradient(context, x0, y0, x1, y1, radius, top, bottom);
            }
            case OUTLINE -> {
                fillRounded(context, x0, y0, x1, y1, radius, ColorUtil.withAlpha(background, baseAlpha / 3));
                strokeRounded(context, x0, y0, x1, y1, radius, border);
                return;
            }
            default -> {
                return;
            }
        }
        if (ColorUtil.alpha(border) > 0) {
            strokeRounded(context, x0, y0, x1, y1, radius, border);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Status glyph
    // ---------------------------------------------------------------------------------------------

    /**
     * Draws the playback state indicator: a breathing dot while playing, a pause bar pair while
     * paused and a flat dot when no controller is available.
     *
     * @param x     left edge of the 6x6 box
     * @param y     top edge of the 6x6 box
     * @param color base colour, already resolved by the caller
     * @param pulse alpha factor in {@code 0..1}
     */
    public static void statusGlyph(DrawContext context, int x, int y, boolean playing, boolean offline,
                                   int color, float pulse) {
        if (offline) {
            fillRounded(context, x, y + 1, x + 6, y + 5, 2, color);
            return;
        }
        if (playing) {
            int alpha = ColorUtil.alpha(color);
            int faded = ColorUtil.withAlpha(color, Math.round(alpha * pulse));
            fillRounded(context, x, y, x + 6, y + 6, 2, faded);
            return;
        }
        context.fill(x, y, x + 2, y + 6, color);
        context.fill(x + 4, y, x + 6, y + 6, color);
    }

    /**
     * Draws the placeholder musical note used wherever album artwork is missing or still loading.
     *
     * <p>Drawn from rectangles rather than a font glyph so it scales with {@code size} and never
     * depends on the active resource pack.</p>
     */
    public static void noteGlyph(DrawContext context, int x, int y, int size, int color) {
        if (size < 6 || ColorUtil.alpha(color) == 0) {
            return;
        }
        int head = Math.max(3, size / 3);
        int stem = Math.max(1, size / 10);
        int bottom = y + size;
        // Note head, then a stem up the right hand side and a short flag.
        fillRounded(context, x, bottom - head, x + head + 1, bottom, Math.max(1, head / 2), color);
        context.fill(x + head, bottom - head - (size - head), x + head + stem, bottom - head / 2, color);
        context.fill(x + head + stem, bottom - head - (size - head), x + head + stem + size / 4,
                bottom - head - (size - head) + stem, color);
    }

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    /** Draws a single line of text with or without a drop shadow. */
    public static void text(DrawContext context, TextRenderer renderer, String value, int x, int y, int color,
                            boolean shadow) {
        if (value == null || value.isEmpty() || ColorUtil.alpha(color) == 0) {
            return;
        }
        context.drawText(renderer, value, x, y, color, shadow);
    }

    /** Draws text right-aligned so that it ends at {@code right}. */
    public static void textRight(DrawContext context, TextRenderer renderer, String value, int right, int y,
                                 int color, boolean shadow) {
        text(context, renderer, value, right - renderer.getWidth(value), y, color, shadow);
    }

    /** Draws text horizontally centred on {@code centerX}. */
    public static void textCentered(DrawContext context, TextRenderer renderer, String value, int centerX, int y,
                                    int color, boolean shadow) {
        text(context, renderer, value, centerX - renderer.getWidth(value) / 2, y, color, shadow);
    }

    /**
     * Draws text into a fixed width, trimming it with an ellipsis or scrolling it.
     *
     * @param allowMarquee whether this element may scroll the text
     * @param marqueeOffset pixels the marquee has already travelled, {@code 0} while paused
     */
    public static void scrollingText(DrawContext context, TextRenderer renderer, String value, int x, int y,
                                     int maxWidth, int color, boolean shadow, boolean allowMarquee,
                                     float marqueeOffset, float marqueeSpeed) {
        if (value == null || value.isEmpty()) {
            return;
        }
        int width = renderer.getWidth(value);
        if (width <= maxWidth) {
            text(context, renderer, value, x, y, color, shadow);
            return;
        }
        if (!allowMarquee) {
            text(context, renderer, trim(renderer, value, maxWidth), x, y, color, shadow);
            return;
        }
        int loopWidth = width + 24;
        float offset = marqueeOffset <= 0.0F ? 0.0F : (marqueeOffset * Math.max(1.0F, marqueeSpeed / 40.0F)) % loopWidth;
        context.enableScissor(x, y - 1, x + maxWidth, y + 10);
        try {
            text(context, renderer, value, Math.round(x - offset), y, color, shadow);
            if (offset > width) {
                text(context, renderer, value, Math.round(x - offset + loopWidth), y, color, shadow);
            }
        } finally {
            context.disableScissor();
        }
    }

    /** Truncates a string so that it plus an ellipsis fits into {@code maxWidth}. */
    public static String trim(TextRenderer renderer, String value, int maxWidth) {
        String ellipsis = "\u2026";
        int ellipsisWidth = renderer.getWidth(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return ellipsis;
        }
        StringBuilder builder = new StringBuilder();
        int width = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterWidth = renderer.getWidth(character);
            if (width + characterWidth + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(character);
            width += characterWidth;
            index += Character.charCount(codePoint);
        }
        return builder.append(ellipsis).toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Progress
    // ---------------------------------------------------------------------------------------------

    /**
     * Draws a progress bar.
     *
     * @param style   how to fill the played portion
     * @param accent  accent colour of the fill
     * @param fraction played fraction, clamped to {@code 0..1}; negative draws an empty track
     */
    public static void progressBar(DrawContext context, int x0, int y0, int x1, int y1,
                                   ModConfig.ProgressStyle style, int accent, float fraction) {
        int width = x1 - x0;
        int height = y1 - y0;
        if (width <= 0 || height <= 0) {
            return;
        }
        int radius = style == ModConfig.ProgressStyle.HAIRLINE ? 0 : Math.min(2, height / 2);
        int track = ColorUtil.withAlpha(0xFFFFFFFF, BAR_TRACK_ALPHA);

        if (style == ModConfig.ProgressStyle.SEGMENTS) {
            int segments = Math.max(8, width / 6);
            int gap = 2;
            int segmentWidth = Math.max(1, (width - gap * (segments - 1)) / segments);
            int filled = fraction <= 0.0F ? 0 : Math.round(segments * Math.min(1.0F, fraction));
            for (int index = 0; index < segments; index++) {
                int left = x0 + index * (segmentWidth + gap);
                int right = Math.min(x1, left + segmentWidth);
                if (right <= left) {
                    break;
                }
                context.fill(left, y0, right, y1, index < filled ? accent : track);
            }
            return;
        }

        fillRounded(context, x0, y0, x1, y1, radius, track);
        if (fraction <= 0.0F) {
            return;
        }
        int capped = Math.min(1.0F, fraction) >= 1.0F ? width : Math.round(width * Math.min(1.0F, fraction));
        if (capped <= 0) {
            return;
        }
        int right = x0 + Math.max(1, capped);
        if (style == ModConfig.ProgressStyle.SHADED_GRADIENT) {
            int light = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.45F), ColorUtil.alpha(accent));
            fillRoundedGradient(context, x0, y0, right, y1, radius, accent, light);
        } else {
            fillRounded(context, x0, y0, right, y1, radius, accent);
        }
        if (style == ModConfig.ProgressStyle.SOLID || style == ModConfig.ProgressStyle.SHADED_GRADIENT) {
            // Playhead marker, extended one pixel past the bar so it stays visible at 0%.
            int head = Math.min(x1 - 1, right - 1);
            context.fill(head, y0 - 1, head + 1, y1 + 1, ColorUtil.withAlpha(0xFFFFFFFF, 0xE0));
        }
    }

    /** Formats a millisecond duration as {@code m:ss} or {@code h:mm:ss}. */
    public static String formatTime(long millis) {
        if (millis < 0L) {
            millis = 0L;
        }
        long totalSeconds = millis / 1000L;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
