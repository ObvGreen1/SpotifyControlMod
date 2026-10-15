package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.ColorUtil;
import me.obvgreen.spotifycontrol.client.config.HudElementType;
import me.obvgreen.spotifycontrol.client.lyrics.Lyrics;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsLine;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsSnapshot;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsStatus;

import java.util.List;
import java.util.Locale;

/**
 * Time-synced lyrics window.
 *
 * <p>Behaviour per backend:</p>
 * <ul>
 *   <li>Web API playback: synced lyrics scroll and, when karaoke mode is on, the active line fills
 *       left to right as it is sung.</li>
 *   <li>Native fallback playback: the controller cannot report a position, so the sheet is shown as a
 *       static block instead of a highlight stuck on line one.</li>
 *   <li>No lyrics found: one status line, so the player knows the lookup ran.</li>
 * </ul>
 */
public final class LyricsHudElement implements HudElementRenderer {

    private static final int PADDING = 3;
    private static final int ROW_HEIGHT = 10;
    private static final int ROW_GAP = 1;
    /** Assumed line length used for the karaoke fill when the next timestamp is missing. */
    private static final long FALLBACK_LINE_MS = 4000L;

    @Override
    public HudElementType type() {
        return HudElementType.LYRICS;
    }

    @Override
    public int measureWidth(HudRenderContext context, HudLayout layout) {
        return Math.max(80, layout.size());
    }

    @Override
    public int measureHeight(HudRenderContext context, HudLayout layout) {
        int rows = rowsFor(context, layout);
        return PADDING * 2 + rows * ROW_HEIGHT + (rows - 1) * ROW_GAP;
    }

    /** How many text rows this frame needs: the lyric window, or a single status line. */
    private static int rowsFor(HudRenderContext context, HudLayout layout) {
        Lyrics lyrics = context.lyrics().lyrics();
        if (lyrics != null && !lyrics.isEmpty()) {
            return Math.max(1, layout.lyricsLines());
        }
        return 1;
    }

    @Override
    public void render(HudRenderContext context, HudLayout layout, int width, int height) {
        int background = layout.backgroundScaled(0.9F);
        LyricsSnapshot snapshot = context.lyrics();
        Lyrics lyrics = snapshot.lyrics();

        if (lyrics == null || lyrics.isEmpty()) {
            if (!context.config().lyricsShowPlaceholder) {
                return;
            }
            drawPanel(context, layout, width, height, background, -1);
            String message = statusText(context, snapshot);
            HudPainter.textCentered(context.context(), context.renderer(), message, width / 2, PADDING + 1,
                    layout.tertiaryColor(), layout.textShadow());
            return;
        }

        if (lyrics.instrumental()) {
            drawPanel(context, layout, width, height, background, -1);
            String message = context.tr("spotifycontrol.lyrics.status.instrumental", "Instrumental");
            HudPainter.textCentered(context.context(), context.renderer(), message, width / 2, PADDING + 1,
                    layout.tertiaryColor(), layout.textShadow());
            return;
        }

        List<LyricsLine> lines = lyrics.lines();
        int rows = Math.max(1, layout.lyricsLines());
        int active = context.lyricsSynced() && lyrics.synced()
                ? lyrics.indexAt(context.lyricsPositionMs())
                : -1;
        int first;
        if (active < 0) {
            first = 0;
        } else {
            first = Math.max(0, Math.min(lines.size() - rows, active - rows / 2));
        }

        drawPanel(context, layout, width, height, background, active - first);

        boolean uppercase = context.config().lyricsUppercase;
        boolean karaoke = context.config().lyricsKaraoke && active >= 0;
        int baseColor = context.config().lyricsTextColorArgb();
        int activeColor = context.config().lyricsActiveColorArgb();

        for (int row = 0; row < rows; row++) {
            int index = first + row;
            if (index >= lines.size()) {
                break;
            }
            String text = lines.get(index).text();
            if (uppercase) {
                text = text.toUpperCase(Locale.ROOT);
            }
            if (text.isBlank()) {
                continue;
            }
            int rowY = PADDING + row * (ROW_HEIGHT + ROW_GAP);
            String fitted = HudPainter.trim(context.renderer(), text, width - PADDING * 2);
            int textWidth = context.renderer().width(fitted);
            int textX = (width - textWidth) / 2;
            boolean shadow = layout.textShadow();

            if (index != active) {
                int distance = active < 0 ? row : Math.abs(index - active);
                float fade = Math.max(0.35F, 1.0F - distance * 0.22F);
                HudPainter.text(context.context(), context.renderer(), fitted, textX, rowY,
                        ColorUtil.scaleAlpha(baseColor, fade), shadow);
                continue;
            }

            if (!karaoke) {
                HudPainter.text(context.context(), context.renderer(), fitted, textX, rowY, activeColor, shadow);
                continue;
            }

            float progress = lyrics.lineProgress(index, context.lyricsPositionMs(), FALLBACK_LINE_MS);
            // Unsung portion first, then the sung part clipped to the karaoke position.
            HudPainter.text(context.context(), context.renderer(), fitted, textX, rowY,
                    ColorUtil.scaleAlpha(baseColor, 0.95F), shadow);
            if (progress <= 0.0F) {
                continue;
            }
            int clipRight = textX + Math.max(1, Math.round(textWidth * Math.min(1.0F, progress)));
            context.context().enableScissor(textX, rowY - 1, clipRight, rowY + ROW_HEIGHT);
            try {
                HudPainter.text(context.context(), context.renderer(), fitted, textX, rowY, activeColor, shadow);
            } finally {
                context.context().disableScissor();
            }
        }
    }

    private void drawPanel(HudRenderContext context, HudLayout layout, int width, int height, int background,
                           int highlightedRow) {
        HudPainter.panel(context.context(), context.config().hudPanelStyle, 0, 0, width, height,
                layout.cornerRadius(), background, layout.border());
        if (highlightedRow < 0 || background == 0) {
            return;
        }
        // Soft pill behind the active line so the eye finds it without reading the text colour.
        int rowY = PADDING + highlightedRow * (ROW_HEIGHT + ROW_GAP);
        HudPainter.fillRounded(context.context(), 1, rowY - 1, width - 1, rowY + ROW_HEIGHT - 1,
                Math.min(3, layout.cornerRadius()), layout.backgroundScaled(0.55F));
    }

    /** Localised explanation of why no lyrics are on screen. */
    private static String statusText(HudRenderContext context, LyricsSnapshot snapshot) {
        LyricsStatus status = snapshot.status();
        return switch (status) {
            case LOADING -> context.tr("spotifycontrol.lyrics.status.loading", "Searching lyrics\u2026");
            case NOT_FOUND -> context.tr("spotifycontrol.lyrics.status.not_found", "No lyrics found");
            case ERROR -> context.tr("spotifycontrol.lyrics.status.error", "Lyrics lookup failed");
            case DISABLED -> context.tr("spotifycontrol.lyrics.status.disabled", "Lyrics disabled");
            case INSTRUMENTAL -> context.tr("spotifycontrol.lyrics.status.instrumental", "Instrumental");
            case UNSYNCED -> context.tr("spotifycontrol.lyrics.status.unsynced", "Lyrics unsynced");
            default -> context.hasTrack()
                    ? context.tr("spotifycontrol.lyrics.status.idle", "Waiting for playback")
                    : context.tr("spotifycontrol.hud.idle", "Nothing playing");
        };
    }
}
