package me.obvgreen.spotifycontrol.client.gui;

import me.obvgreen.spotifycontrol.client.SpotifyControlClient;
import me.obvgreen.spotifycontrol.client.config.ColorUtil;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.gui.hud.HudPainter;
import me.obvgreen.spotifycontrol.client.lyrics.Lyrics;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsLine;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsService;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsSnapshot;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyService;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Full-screen lyrics sheet for the current track.
 *
 * <p>Synced sheets auto-scroll so the active line stays centred and carry a karaoke underline; an
 * untimed sheet is shown as a static block the player can scroll by hand. The offset applied here is
 * the same {@link ModConfig#lyricsOffsetMs} the HUD uses, so a correction made once shows up
 * everywhere.</p>
 */
public final class SpotifyLyricsScreen extends Screen {

    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 260;
    private static final int PADDING = 12;
    private static final int LINE_HEIGHT = 12;
    private static final int ROW_HEIGHT = 20;
    private static final int GROUP_GAP = 4;
    private static final int OFFSET_STEP_MS = 500;
    private static final int OFFSET_LIMIT_MS = 10000;

    private static final int PANEL_BG = 0xE6101014;
    private static final int PANEL_BORDER = 0x50FFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFFB0B0B0;
    private static final int DIM_COLOR = 0xFF808080;

    private final Screen parent;
    @Nullable
    private final SpotifyService service;
    private final LyricsService lyricsService;
    private final ModConfig config;

    private long progressBaseMs;
    private long progressBaseAt;
    private long progressDurationMs;
    private boolean progressPlaying;
    private String progressIdentity = "";

    /** First visible line for untimed sheets, moved by the scroll wheel. */
    private int manualScroll;

    /**
     * Creates the screen.
     *
     * @param parent        screen to return to on close, may be {@code null}
     * @param service       live playback service, used to sync the active line; may be {@code null}
     * @param lyricsService live lyrics service
     * @param config        live configuration
     */
    public SpotifyLyricsScreen(@Nullable Screen parent, @Nullable SpotifyService service,
                               LyricsService lyricsService, ModConfig config) {
        super(Component.translatable("spotifycontrol.lyrics.title"));
        this.parent = parent;
        this.service = service;
        this.lyricsService = lyricsService;
        this.config = config;
        syncProgress();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int bottomY = top + panelHeight - PADDING - ROW_HEIGHT;

        int rowWidth = 76 + 76 + 96 + 62 + 52 + GROUP_GAP * 4;
        int cursor = left + (panelWidth - rowWidth) / 2;
        cursor = addButton(cursor, bottomY, 76, Component.translatable("spotifycontrol.lyrics.offsetMinus"),
                button -> adjustOffset(-OFFSET_STEP_MS));
        cursor = addButton(cursor, bottomY, 76, Component.translatable("spotifycontrol.lyrics.offsetPlus"),
                button -> adjustOffset(OFFSET_STEP_MS));
        cursor = addButton(cursor, bottomY, 96, Component.translatable("spotifycontrol.lyrics.offsetReset"),
                button -> setOffset(0));
        cursor = addButton(cursor, bottomY, 62, Component.translatable("spotifycontrol.lyrics.reload"),
                button -> lyricsService.reload());
        addButton(cursor, bottomY, 52, Component.translatable("spotifycontrol.lyrics.back"),
                button -> onClose());
    }

    private int addButton(int x, int y, int widgetWidth, Component label, Button.OnPress action) {
        addRenderableWidget(Button.builder(label, action)
                .bounds(x, y, widgetWidth, ROW_HEIGHT).build());
        return x + widgetWidth + GROUP_GAP;
    }

    private void adjustOffset(int deltaMs) {
        setOffset(config.lyricsOffsetMs + deltaMs);
    }

    private void setOffset(int offsetMs) {
        config.lyricsOffsetMs = Math.max(-OFFSET_LIMIT_MS, Math.min(OFFSET_LIMIT_MS, offsetMs));
        config.save();
    }

    @Override
    public void tick() {
        syncProgress();
    }

    /** Re-seeds the local interpolation whenever the service publishes a different snapshot. */
    private void syncProgress() {
        if (service == null) {
            return;
        }
        TrackInfo track = service.currentTrack();
        String identity = track.title() + '\u0000' + track.artist() + '\u0000' + track.progressMs()
                + '\u0000' + track.isPlaying();
        if (identity.equals(progressIdentity)) {
            return;
        }
        progressIdentity = identity;
        progressBaseMs = track.progressMs();
        progressBaseAt = System.currentTimeMillis();
        progressDurationMs = track.durationMs();
        progressPlaying = track.isPlaying();
    }

    private long displayProgressMs() {
        long value = progressBaseMs;
        if (progressPlaying) {
            value += System.currentTimeMillis() - progressBaseAt;
        }
        if (progressDurationMs > 0L) {
            value = Math.min(progressDurationMs, value);
        }
        return Math.max(0L, value);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        HudPainter.fillRounded(context, left, top, left + panelWidth, top + panelHeight, 6, PANEL_BG);
        HudPainter.strokeRounded(context, left, top, left + panelWidth, top + panelHeight, 6, PANEL_BORDER);

        LyricsSnapshot snapshot = lyricsService.snapshot();
        String detail = snapshot.detail();
        String status = Component.translatable(
                        "spotifycontrol.lyrics.status." + snapshot.status().name().toLowerCase(Locale.ROOT))
                .getString()
                + (detail.isEmpty() ? "" : " - " + detail);
        HudPainter.text(context, font,
                HudPainter.trim(font, status, panelWidth - PADDING * 2),
                left + PADDING, top + 8, MUTED_COLOR, true);
        String offset = Component.translatable("spotifycontrol.lyrics.offsetValue", config.lyricsOffsetMs).getString();
        HudPainter.textRight(context, font, offset, left + panelWidth - PADDING, top + 8,

                config.lyricsOffsetMs == 0 ? DIM_COLOR : TEXT_COLOR, true);

        int areaLeft = left + PADDING;
        int areaRight = left + panelWidth - PADDING;
        int areaTop = top + 24;
        int areaBottom = top + panelHeight - PADDING - ROW_HEIGHT - 6;
        int visible = Math.max(1, (areaBottom - areaTop) / LINE_HEIGHT);

        Lyrics lyrics = snapshot.lyricsOrEmpty(detail.isEmpty() ? "local" : detail);
        if (!snapshot.ready()) {
            HudPainter.textCentered(context, font,
                    Component.translatable("spotifycontrol.lyrics.waiting").getString(),
                    (areaLeft + areaRight) / 2, (areaTop + areaBottom) / 2 - 4, DIM_COLOR, true);
        } else {
            renderSheet(context, lyrics, areaTop, areaBottom, areaLeft, areaRight, visible);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderSheet(GuiGraphics context, Lyrics lyrics, int areaTop, int areaBottom,
                             int areaLeft, int areaRight, int visible) {
        int total = lyrics.size();
        int activeIndex = lyrics.synced() ? lyrics.indexAt(displayProgressMs() + config.lyricsOffsetMs) : -1;
        int first;
        if (lyrics.synced() && activeIndex >= 0) {
            first = activeIndex - visible / 2;
        } else {
            first = manualScroll;
        }
        first = Math.max(0, Math.min(Math.max(0, total - visible), first));
        manualScroll = first;

        int centerX = (areaLeft + areaRight) / 2;
        int y = areaTop + Math.max(0, (areaBottom - areaTop - visible * LINE_HEIGHT) / 2);
        int activeColor = config.lyricsActiveColorArgb();
        int normalColor = config.lyricsTextColorArgb();

        for (int row = 0; row < visible; row++) {
            int index = first + row;
            LyricsLine line = lyrics.line(index);
            if (line == null) {
                break;
            }
            String text = line.text();
            if (text.isEmpty()) {
                y += LINE_HEIGHT;
                continue;
            }
            if (config.lyricsUppercase) {
                text = text.toUpperCase(Locale.ROOT);
            }
            text = HudPainter.trim(font, text, areaRight - areaLeft);
            boolean active = index == activeIndex;
            boolean past = lyrics.synced() && activeIndex >= 0 && index < activeIndex;
            int color = active ? activeColor : past ? DIM_COLOR : normalColor;
            HudPainter.textCentered(context, font, text, centerX, y, color, true);
            if (active && lyrics.synced()) {
                float progress = lyrics.lineProgress(index, displayProgressMs() + config.lyricsOffsetMs,
                        Math.max(1L, progressDurationMs));
                int width = font.width(text);
                int barLeft = centerX - width / 2;
                int barWidth = Math.max(1, Math.round(width * progress));
                context.fill(barLeft, y + LINE_HEIGHT - 2, barLeft + barWidth, y + LINE_HEIGHT - 1,
                        ColorUtil.scaleAlpha(activeColor, 0.65F));
            }
            y += LINE_HEIGHT;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0.0D) {
            manualScroll = Math.max(0, manualScroll - (int) Math.signum(verticalAmount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        switch (input.key()) {
            case GLFW.GLFW_KEY_LEFT -> {
                adjustOffset(-OFFSET_STEP_MS);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                adjustOffset(OFFSET_STEP_MS);
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                lyricsService.reload();
                return true;
            }
            default -> {
                return super.keyPressed(input);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
