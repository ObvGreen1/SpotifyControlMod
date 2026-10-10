package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.HudElementType;
import me.obvgreen.spotifycontrol.client.config.ModConfig;

/**
 * Stand-alone progress bar with the elapsed and total time above it.
 *
 * <p>When the active controller cannot report a duration (the Windows native fallback does not), the
 * bar degrades to an empty track instead of drawing a misleading full bar.</p>
 */
public final class ProgressHudElement implements HudElementRenderer {

    private static final int PADDING = 1;
    private static final int BAR_HEIGHT = 4;
    private static final int TIME_HEIGHT = 9;

    @Override
    public HudElementType type() {
        return HudElementType.PROGRESS;
    }

    @Override
    public int measureWidth(HudRenderContext context, HudLayout layout) {
        return Math.max(60, layout.size());
    }

    @Override
    public int measureHeight(HudRenderContext context, HudLayout layout) {
        return PADDING + (context.config().hudShowTime ? TIME_HEIGHT + 1 : 0) + BAR_HEIGHT + PADDING;
    }

    @Override
    public void render(HudRenderContext context, HudLayout layout, int width, int height) {
        boolean showTime = context.config().hudShowTime;
        boolean shadow = layout.textShadow();
        int barTop = showTime ? PADDING + TIME_HEIGHT + 1 : PADDING;

        if (showTime) {
            String elapsed = context.progressMs() > 0L ? context.time(context.progressMs()) : "0:00";
            String total = context.durationMs() > 0L ? context.time(context.durationMs()) : "--:--";
            HudPainter.text(context.context(), context.renderer(), elapsed, 0, PADDING,
                    layout.secondaryColor(), shadow);
            HudPainter.textRight(context.context(), context.renderer(), total, width, PADDING,
                    layout.secondaryColor(), shadow);
        }

        ModConfig.ProgressStyle style = context.config().hudProgressStyle;
        float fraction = context.progressFraction();
        HudPainter.progressBar(context.context(), 0, barTop, width, barTop + BAR_HEIGHT, style,
                layout.accentColor(), fraction);

        if (fraction < 0.0F) {
            // Unknown duration: label the bar so the empty state is not read as "stuck at the start".
            String hint = context.tr("spotifycontrol.hud.progress.unknown", "live");
            HudPainter.textCentered(context.context(), context.renderer(), hint, width / 2, PADDING + 1,
                    layout.tertiaryColor(), shadow);
        }
    }
}
