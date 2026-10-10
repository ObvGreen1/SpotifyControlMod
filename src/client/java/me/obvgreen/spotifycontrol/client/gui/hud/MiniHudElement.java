package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.HudElementType;

/**
 * One-line strip: {@code title - artist} with a hairline progress indicator underneath.
 *
 * <p>The strip measures itself so it does not span the screen for a short track name, but never grows
 * past the configured maximum width; overflow scrolls when the marquee is enabled.</p>
 */
public final class MiniHudElement implements HudElementRenderer {

    private static final int PADDING = 3;
    private static final int ROW_HEIGHT = 9;
    private static final int BAR_HEIGHT = 2;
    private static final String SEPARATOR = "  \u00B7  ";

    @Override
    public HudElementType type() {
        return HudElementType.MINI;
    }

    @Override
    public int measureWidth(HudRenderContext context, HudLayout layout) {
        String combined = context.title() + SEPARATOR + context.artist();
        int text = context.renderer().getWidth(combined);
        int glyph = context.config().hudShowStatusIcon ? 10 : 0;
        return Math.max(48, Math.min(layout.size(), text + glyph + PADDING * 2));
    }

    @Override
    public int measureHeight(HudRenderContext context, HudLayout layout) {
        return PADDING * 2 + ROW_HEIGHT + (context.config().hudShowProgress ? 2 + BAR_HEIGHT : 0);
    }

    @Override
    public void render(HudRenderContext context, HudLayout layout, int width, int height) {
        HudPainter.panel(context.context(), context.config().hudPanelStyle, 0, 0, width, height,
                layout.cornerRadius(), layout.backgroundScaled(0.85F), layout.border());

        int left = PADDING;
        if (context.config().hudShowStatusIcon) {
            HudPainter.statusGlyph(context.context(), left, PADDING + 1, context.playing(),
                    context.offline() || !context.hasTrack(), context.statusColor(layout), context.pulse(layout));
            left += 10;
        }
        int right = width - PADDING;
        int maxTextWidth = Math.max(8, right - left);

        String combined = context.title() + SEPARATOR + context.artist();
        if (context.renderer().getWidth(combined) <= maxTextWidth) {
            boolean shadow = layout.textShadow();
            HudPainter.text(context.context(), context.renderer(), context.title(), left, PADDING,
                    layout.textColor(), shadow);
            int titleWidth = context.renderer().getWidth(context.title());
            HudPainter.text(context.context(), context.renderer(), SEPARATOR + context.artist(),
                    left + titleWidth, PADDING, layout.secondaryColor(), shadow);
        } else {
            HudPainter.scrollingText(context.context(), context.renderer(), combined, left, PADDING,
                    maxTextWidth, layout.textColor(), layout.textShadow(), layout.marquee(),
                    context.marqueeOffset(), context.marqueeSpeed());
        }

        if (!context.config().hudShowProgress) {
            return;
        }
        int barTop = height - PADDING - BAR_HEIGHT;
        HudPainter.progressBar(context.context(), left, barTop, right, barTop + BAR_HEIGHT,
                me.obvgreen.spotifycontrol.client.config.ModConfig.ProgressStyle.HAIRLINE,
                layout.accentColor(), context.progressFraction());
    }
}
