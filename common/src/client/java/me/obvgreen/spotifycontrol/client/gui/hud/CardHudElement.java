package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.HudElementType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Full HUD card: album art, title, artist, optional album and device line, progress bar and times.
 *
 * <p>The height adapts to the enabled rows so switching the album name off does not leave a gap, and
 * the artwork is only reserved when it is enabled so a text-only card stays compact.</p>
 */
public final class CardHudElement implements HudElementRenderer {

    private static final int PADDING = 4;
    private static final int ROW_HEIGHT = 10;
    private static final int ART_SIZE = 32;
    private static final int BAR_HEIGHT = 3;
    private static final int TIME_HEIGHT = 9;
    /** Width of the accent stripe painted along the left edge. */
    private static final int STRIPE_WIDTH = 2;

    @Override
    public HudElementType type() {
        return HudElementType.CARD;
    }

    @Override
    public int measureWidth(HudRenderContext context, HudLayout layout) {
        return Math.max(96, layout.size());
    }

    @Override
    public int measureHeight(HudRenderContext context, HudLayout layout) {
        boolean art = context.config().hudShowAlbumArt;
        int rows = textRows(context);
        int textBlock = PADDING + rows * ROW_HEIGHT + progressBlock(context, layout) + PADDING;
        int artBlock = art ? ART_SIZE + PADDING * 2 : 0;
        return Math.max(artBlock, textBlock);
    }

    private static int textRows(HudRenderContext context) {
        int rows = 2;
        if (context.config().hudShowAlbumName) {
            rows++;
        }
        if (!context.deviceLine().isEmpty()) {
            rows++;
        }
        return rows;
    }

    /** Vertical space the progress bar plus time row needs, including the gap above the bar. */
    private static int progressBlock(HudRenderContext context, HudLayout layout) {
        if (!context.config().hudShowProgress) {
            return 0;
        }
        return 3 + BAR_HEIGHT + (context.config().hudShowTime ? 3 + TIME_HEIGHT : 0);
    }

    @Override
    public void render(HudRenderContext context, HudLayout layout, int width, int height) {
        HudPainter.panel(context.context(), context.config().hudPanelStyle, 0, 0, width, height,
                layout.cornerRadius(), layout.background(), layout.border());

        boolean art = context.config().hudShowAlbumArt;
        int artLeft = PADDING;
        int artTop = (height - ART_SIZE) / 2;
        if (art) {
            drawArt(context, artLeft, artTop);
        }

        // Accent stripe: gives the card a Spotify-green spine without tinting the artwork.
        HudPainter.fillRounded(context.context(), 0, 0, STRIPE_WIDTH, height, layout.cornerRadius(),
                layout.fade(layout.accentColor(), 0.85F));

        int contentLeft = art ? artLeft + ART_SIZE + 5 : artLeft + STRIPE_WIDTH;
        int contentRight = width - PADDING;
        int textWidth = Math.max(8, contentRight - contentLeft - 8);
        int y = PADDING;

        boolean shadow = layout.textShadow();
        HudPainter.scrollingText(context.context(), context.renderer(), context.title(), contentLeft, y,
                textWidth, layout.textColor(), shadow, layout.marquee(), context.marqueeOffset(),
                context.marqueeSpeed());
        if (context.config().hudShowStatusIcon) {
            HudPainter.statusGlyph(context.context(), contentRight - 6, y + 2, context.playing(),
                    context.offline() || !context.hasTrack(), context.statusColor(layout), context.pulse(layout));
        }
        y += ROW_HEIGHT;

        HudPainter.scrollingText(context.context(), context.renderer(), context.artist(), contentLeft, y,
                contentRight - contentLeft, layout.secondaryColor(), shadow, layout.marquee(),
                context.marqueeOffset(), context.marqueeSpeed());
        y += ROW_HEIGHT;

        if (context.config().hudShowAlbumName) {
            HudPainter.scrollingText(context.context(), context.renderer(), context.track().album(), contentLeft,
                    y, contentRight - contentLeft, layout.tertiaryColor(), shadow, layout.marquee(),
                    context.marqueeOffset(), context.marqueeSpeed());
            y += ROW_HEIGHT;
        }
        String deviceLine = context.deviceLine();
        if (!deviceLine.isEmpty()) {
            HudPainter.text(context.context(), context.renderer(),
                    HudPainter.trim(context.renderer(), deviceLine, contentRight - contentLeft), contentLeft, y,
                    layout.tertiaryColor(), shadow);
        }

        if (context.config().hudShowProgress) {
            drawProgress(context, layout, contentLeft, contentRight, height);
        }
    }

    private void drawArt(HudRenderContext context, int left, int top) {
        Identifier texture = context.albumArt();
        int border = HudRenderContext.alpha(0xFFFFFFFF, 0.25F);
        if (texture == null) {
            HudPainter.fillRounded(context.context(), left, top, left + ART_SIZE, top + ART_SIZE,
                    Math.min(3, ART_SIZE / 4), HudRenderContext.alpha(0xFFFFFFFF, 0.22F));
            int glyph = Math.max(8, ART_SIZE / 2);
            HudPainter.noteGlyph(context.context(), left + (ART_SIZE - glyph) / 2, top + (ART_SIZE - glyph) / 2,
                    glyph, HudRenderContext.alpha(0xFFFFFFFF, 0.55F));
        } else {
            // GuiGraphics#drawTexture needs an explicit pipeline; standalone RGBA art uses GUI_TEXTURED.
            context.context().blit(RenderPipelines.GUI_TEXTURED, texture, left, top, 0.0F, 0.0F,
                    ART_SIZE, ART_SIZE, ART_SIZE, ART_SIZE);
        }
        HudPainter.strokeRounded(context.context(), left - 1, top - 1, left + ART_SIZE + 1, top + ART_SIZE + 1,
                3, border);
    }

    private void drawProgress(HudRenderContext context, HudLayout layout, int left, int right, int height) {
        boolean showTime = context.config().hudShowTime;
        int timeRow = height - PADDING - TIME_HEIGHT;
        int barTop = showTime ? timeRow - 3 - BAR_HEIGHT : height - PADDING - BAR_HEIGHT;
        barTop = Math.max(PADDING + 2 * ROW_HEIGHT, barTop);
        HudPainter.progressBar(context.context(), left, barTop, right, barTop + BAR_HEIGHT,
                context.config().hudProgressStyle, layout.accentColor(), context.progressFraction());
        if (!showTime) {
            return;
        }
        String elapsed = context.time(context.progressMs());
        String total = context.durationMs() > 0L ? context.time(context.durationMs()) : "--:--";
        HudPainter.text(context.context(), context.renderer(), elapsed, left, timeRow,
                layout.tertiaryColor(), layout.textShadow());
        HudPainter.textRight(context.context(), context.renderer(), total, right, timeRow,
                layout.tertiaryColor(), layout.textShadow());
    }
}
