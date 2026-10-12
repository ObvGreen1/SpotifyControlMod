package me.obvgreen.spotifycontrol.client.gui;

import me.obvgreen.spotifycontrol.client.SpotifyControlClient;
import me.obvgreen.spotifycontrol.client.config.ColorUtil;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.config.ModMenuIntegration;
import me.obvgreen.spotifycontrol.client.gui.hud.HudPainter;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsService;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyService;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;

/**
 * Full transport console for the current Spotify playback.
 *
 * <p>The screen only reads {@link SpotifyService} snapshots: every button pushes one command onto
 * the service worker and the next poll (or the optimistic snapshot the service publishes right
 * after a successful command) refreshes the drawing. Nothing here blocks, touches the network, or
 * owns state that outlives the screen.</p>
 *
 * <p>Volume is presented honestly: the slider spans {@code 0..maxVolumePercent()} and the caption
 * states when the active backend cannot amplify past 100 %, instead of pretending a boost was
 * applied.</p>
 */
public final class SpotifyGuiScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Gui");

    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 216;
    private static final int PADDING = 12;
    private static final int ART_SIZE = 56;
    private static final int BAR_HEIGHT = 8;
    private static final int ROW_HEIGHT = 20;
    private static final int GROUP_GAP = 4;
    private static final long SEEK_STEP_MS = 5000L;
    private static final int VOLUME_STEP = 5;
    private static final long VOLUME_SYNC_SUPPRESS_MS = 3000L;
    /** How long a one-off notice stays on the status line. */
    private static final long NOTICE_MS = 4000L;

    private static final int PANEL_BG = 0xE6101014;
    private static final int PANEL_BORDER = 0x50FFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFFB0B0B0;
    private static final int DIM_COLOR = 0xFF808080;
    private static final int ACCENT = 0xFF1DB954;
    private static final int ACCENT_DIM = 0xFF1A9E4B;

    private final Screen parent;
    private final SpotifyService service;
    private final ModConfig config;
    @Nullable
    private final AlbumArtCache artCache;

    private SeekBarWidget seekBar;
    private VolumeSlider volumeSlider;
    private ButtonWidget playButton;
    private ButtonWidget repeatButton;
    private ButtonWidget shuffleButton;
    private ButtonWidget adButton;
    private ButtonWidget authButton;

    /** Snapshot progress captured when the track identity last changed, used to interpolate. */
    private long progressBaseMs;
    private long progressBaseAt;
    private long progressDurationMs;
    private boolean progressPlaying;
    private String progressIdentity = "";

    /** {@code >= 0} while the user drags the seek bar: the fraction of the track being pointed at. */
    private float scrubFraction = -1.0F;
    /** Suppresses slider rewrites for a moment after the user commits a volume change. */
    private long volumeSyncSuppressedUntil;
    private volatile String authMessage = "";
    /** Transient one-off feedback (for example "backend cannot shuffle"); wins over the status line. */
    private volatile String noticeMessage = "";
    private volatile long noticeUntil;

    /**
     * Creates the screen.
     *
     * @param parent   screen to return to on close, may be {@code null}
     * @param service  live playback service
     * @param config   live configuration
     * @param artCache album art cache, may be {@code null}
     */
    public SpotifyGuiScreen(@Nullable Screen parent, SpotifyService service, ModConfig config,
                            @Nullable AlbumArtCache artCache) {
        super(Text.translatable("spotifycontrol.gui.title"));
        this.parent = parent;
        this.service = service;
        this.config = config;
        this.artCache = artCache;
        syncProgress();
    }

    /**
     * Opens the console on the client thread.
     *
     * @param parent screen to return to on close, may be {@code null}
     * @return {@code true} when the screen was opened, {@code false} when the mod is not ready yet
     *         (the client entrypoint has not finished initialising, so there is no service to bind to)
     */
    public static boolean open(@Nullable Screen parent) {
        SpotifyService service = SpotifyControlClient.service();
        if (service == null) {
            LOGGER.warn("Cannot open the Spotify console: the client service is not available yet.");
            return false;
        }
        MinecraftClient.getInstance().setScreen(new SpotifyGuiScreen(parent, service,
                SpotifyControlClient.configOrLoad(), SpotifyControlClient.albumArt()));
        return true;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int contentLeft = left + PADDING;
        int contentWidth = panelWidth - PADDING * 2;

        // Seek bar: a 12px clickable strip; this screen paints the time labels just below it.
        seekBar = new SeekBarWidget(contentLeft, top + 90, contentWidth, 12,
                this::displayFraction, fraction -> {
            scrubFraction = fraction;
        }, fraction -> {
            scrubFraction = -1.0F;
            if (progressDurationMs > 0L) {
                service.seek(Math.round(fraction * progressDurationMs));
            }
        });
        addDrawableChild(seekBar);

        int transportY = top + 122;
        int transportWidth = 26 + 62 + 26 + 80 + 80 + GROUP_GAP * 4;
        int cursor = left + (panelWidth - transportWidth) / 2;
        button(cursor, transportY, 26, Text.literal("\u23EE"), widget -> service.previous());
        cursor += 26 + GROUP_GAP;
        playButton = button(cursor, transportY, 62, Text.translatable("spotifycontrol.gui.play"),
                widget -> service.togglePlayPause());
        cursor += 62 + GROUP_GAP;
        button(cursor, transportY, 26, Text.literal("\u23ED"), widget -> service.next());
        cursor += 26 + GROUP_GAP;
        repeatButton = button(cursor, transportY, 80, Text.translatable("spotifycontrol.gui.repeat"),
                widget -> cycleRepeat());
        cursor += 80 + GROUP_GAP;
        shuffleButton = button(cursor, transportY, 80, Text.translatable("spotifycontrol.gui.shuffle"),
                widget -> toggleShuffle());

        int volumeY = top + 150;
        int sliderWidth = Math.max(80, contentWidth - 76);
        int initialVolume = service.volumePercent();
        if (initialVolume < 0) {
            initialVolume = Math.min(100, service.maxVolumePercent());
        }
        volumeSlider = new VolumeSlider(contentLeft, volumeY, sliderWidth, ROW_HEIGHT,
                Math.max(1, service.maxVolumePercent()), initialVolume, percent -> {
            volumeSyncSuppressedUntil = System.currentTimeMillis() + VOLUME_SYNC_SUPPRESS_MS;
            service.setVolume(percent);
        });
        addDrawableChild(volumeSlider);

        int bottomY = top + 178;
        int bottomWidth = 84 + 104 + 62 + 76 + GROUP_GAP * 3;
        cursor = left + (panelWidth - bottomWidth) / 2;
        button(cursor, bottomY, 84, Text.translatable("spotifycontrol.gui.lyrics"),
                widget -> openLyrics());
        cursor += 84 + GROUP_GAP;
        adButton = button(cursor, bottomY, 104, Text.translatable("spotifycontrol.gui.adskip"),
                widget -> {
                    config.adSkipEnabled = !config.adSkipEnabled;
                    config.save();
                });
        cursor += 104 + GROUP_GAP;
        button(cursor, bottomY, 62, Text.translatable("spotifycontrol.gui.config"),
                widget -> client.setScreen(ModMenuIntegration.screen(this)));
        cursor += 62 + GROUP_GAP;
        authButton = button(cursor, bottomY, 76, Text.translatable("spotifycontrol.gui.login"),
                widget -> toggleAuth());

        service.refreshVolume();
        updateLabels();
    }

    private ButtonWidget button(int x, int y, int widgetWidth, Text label, ButtonWidget.PressAction action) {
        ButtonWidget widget = ButtonWidget.builder(label, action)
                .dimensions(x, y, widgetWidth, ROW_HEIGHT).build();
        addDrawableChild(widget);
        return widget;
    }

    private void openLyrics() {
        LyricsService lyrics = SpotifyControlClient.lyrics();
        if (lyrics != null) {
            client.setScreen(new SpotifyLyricsScreen(this, service, lyrics, config));
        }
    }

    /**
     * Advances the repeat mode, reporting why nothing happened when the backend cannot do it.
     *
     * <p>The button is deliberately never disabled: a disabled widget swallows the click and makes the
     * panel feel broken, so the click always lands and any limitation is explained in the status
     * line instead.</p>
     */
    private void cycleRepeat() {
        if (!service.activeController().canControlRepeat()) {
            notice(Text.translatable("spotifycontrol.gui.repeatUnsupported").getString());
            return;
        }
        service.cycleRepeat();
    }

    /** Toggles shuffle, reporting why nothing happened when the backend cannot do it. */
    private void toggleShuffle() {
        if (!service.canControlShuffle()) {
            notice(Text.translatable("spotifycontrol.gui.shuffleUnsupported").getString());
            return;
        }
        service.toggleShuffle();
    }

    /** Shows a short lived message in the panel's status line. */
    private void notice(String message) {
        noticeMessage = message == null ? "" : message;
        noticeUntil = System.currentTimeMillis() + NOTICE_MS;
    }

    private void toggleAuth() {
        if (service.webApi().isAuthenticated()) {
            service.logout(() -> authMessage = Text.translatable("spotifycontrol.gui.loggedOut").getString());
            return;
        }
        if (!config.hasClientId()) {
            // Without a client id the PKCE flow cannot even build its authorize URL, so send the user
            // to the settings screen that holds the field instead of failing silently.
            authMessage = Text.translatable("spotifycontrol.gui.noClientId").getString();
            notice(authMessage);
            client.setScreen(ModMenuIntegration.screen(this));
            return;
        }
        authMessage = Text.translatable("spotifycontrol.gui.authorizing").getString();
        service.login(result -> authMessage = switch (result) {
            case SUCCESS -> Text.translatable("spotifycontrol.gui.loggedIn").getString();
            case ALREADY_RUNNING -> Text.translatable("spotifycontrol.gui.authorizing").getString();
            case NO_CLIENT_ID -> Text.translatable("spotifycontrol.gui.noClientId").getString();
            default -> Text.translatable("spotifycontrol.gui.loginFailed").getString();
        });
    }

    @Override
    public void tick() {
        syncProgress();
        updateLabels();
        syncVolumeSlider();
    }

    /** Re-seeds the local interpolation whenever the service publishes a different snapshot. */
    private void syncProgress() {
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

    /** Mirrors backend volume changes onto the slider, unless the user is moving it right now. */
    private void syncVolumeSlider() {
        int reported = service.volumePercent();
        if (reported < 0 || volumeSlider.isDragging()
                || System.currentTimeMillis() < volumeSyncSuppressedUntil) {
            return;
        }
        volumeSlider.syncTo(reported);
    }

    private void updateLabels() {
        TrackInfo track = service.currentTrack();
        if (playButton != null) {
            playButton.setMessage(Text.translatable(track.isPlaying()
                    ? "spotifycontrol.gui.pause" : "spotifycontrol.gui.play"));
        }
        if (repeatButton != null) {
            // The caption carries the state ("Repeat: Track"), so the label has to be refreshed on
            // every poll or the button looks frozen after a successful toggle.
            repeatButton.setMessage(Text.translatable("spotifycontrol.gui.repeatValue",
                    track.repeatMode().displayName()));
            // Never disabled: unsupported combinations are explained on click, not swallowed.
            repeatButton.active = true;
        }
        if (shuffleButton != null) {
            shuffleButton.setMessage(Text.translatable("spotifycontrol.gui.shuffleValue",
                    Text.translatable(service.shuffleEnabled()
                            ? "spotifycontrol.gui.on" : "spotifycontrol.gui.off")));
            // Never disabled: unsupported combinations are explained on click, not swallowed.
            shuffleButton.active = true;
        }
        if (adButton != null) {
            adButton.setMessage(Text.translatable("spotifycontrol.gui.adskipValue",
                    Text.translatable(config.adSkipEnabled
                            ? "spotifycontrol.gui.on" : "spotifycontrol.gui.off")));
        }
        if (authButton != null) {
            boolean authenticated = service.webApi().isAuthenticated();
            authButton.setMessage(Text.translatable(authenticated
                    ? "spotifycontrol.gui.logout" : "spotifycontrol.gui.login"));
            // Always clickable: without a client id the click opens the settings screen.
            authButton.active = true;
        }
    }

    /** Progress to draw right now: the scrub target while dragging, otherwise interpolated. */
    private long displayProgressMs() {
        if (progressDurationMs <= 0L) {
            return progressBaseMs;
        }
        if (scrubFraction >= 0.0F) {
            return Math.round(scrubFraction * progressDurationMs);
        }
        long value = progressBaseMs;
        if (progressPlaying) {
            value += System.currentTimeMillis() - progressBaseAt;
        }
        return Math.max(0L, Math.min(progressDurationMs, value));
    }

    private float displayFraction() {
        if (progressDurationMs <= 0L) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, (float) displayProgressMs() / progressDurationMs));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        TrackInfo track = service.currentTrack();
        int panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int panelHeight = Math.min(PANEL_HEIGHT, height - 20);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int accent = config.hudAccentColorArgb();

        HudPainter.fillRounded(context, left, top, left + panelWidth, top + panelHeight, 6, PANEL_BG);
        HudPainter.strokeRounded(context, left, top, left + panelWidth, top + panelHeight, 6, PANEL_BORDER);
        // Accent spine, matching the HUD card language.
        HudPainter.fillRounded(context, left, top, left + 2, top + panelHeight, 6, accent);

        drawArt(context, track, left + PADDING + 2, top + 28);

        int textLeft = left + PADDING + ART_SIZE + 8;
        int textRight = left + panelWidth - PADDING;
        int textWidth = Math.max(16, textRight - textLeft);
        HudPainter.text(context, textRenderer,
                HudPainter.trim(textRenderer, track.title(), textWidth), textLeft, top + 30, TEXT_COLOR, true);
        HudPainter.text(context, textRenderer,
                HudPainter.trim(textRenderer, track.artist(), textWidth), textLeft, top + 41, MUTED_COLOR, true);
        HudPainter.text(context, textRenderer,
                HudPainter.trim(textRenderer, track.album(), textWidth), textLeft, top + 52, DIM_COLOR, true);
        String device = track.device();
        if (device == null || device.isBlank()) {
            device = service.activeController().displayName();
        }
        HudPainter.text(context, textRenderer,
                HudPainter.trim(textRenderer, device, textWidth), textLeft, top + 63, DIM_COLOR, true);

        super.render(context, mouseX, mouseY, delta);

        // Time labels sit under the bar the seek widget has already drawn.
        HudPainter.text(context, textRenderer, HudPainter.formatTime(displayProgressMs()),
                left + PADDING, top + 104, MUTED_COLOR, true);
        String total = progressDurationMs > 0L ? HudPainter.formatTime(progressDurationMs) : "--:--";
        HudPainter.textRight(context, textRenderer, total, left + panelWidth - PADDING, top + 104,
                MUTED_COLOR, true);

        int volumeLeft = volumeSlider.getX() + volumeSlider.getWidth() + 6;
        String volumeLabel = volumeCaption();
        HudPainter.text(context, textRenderer,
                HudPainter.trim(textRenderer, volumeLabel, Math.max(16, left + panelWidth - PADDING - volumeLeft)),
                volumeLeft, volumeSlider.getY() + 6,
                service.canControlVolume() ? DIM_COLOR : 0xFFE06060, true);

        String statusLine = statusLine();
        HudPainter.text(context, textRenderer,
                HudPainter.trim(textRenderer, statusLine, panelWidth - PADDING * 2),
                left + PADDING, top + panelHeight - 12, DIM_COLOR, true);
    }

    /** The bottom line: a fresh notice, then the last auth message, then the service status. */
    private String statusLine() {
        if (!noticeMessage.isEmpty() && System.currentTimeMillis() < noticeUntil) {
            return noticeMessage;
        }
        return authMessage.isEmpty() ? service.statusLine() : authMessage;
    }

    private String volumeCaption() {
        if (!service.canControlVolume()) {
            return Text.translatable("spotifycontrol.gui.volumeUnavailable").getString();
        }
        int reported = service.volumePercent();
        if (reported < 0) {
            return volumeSlider.pendingPercent() + "%";
        }
        return service.maxVolumePercent() > 100
                ? reported + "% / " + service.maxVolumePercent() + "%"
                : reported + "%";
    }

    private void drawArt(DrawContext context, TrackInfo track, int left, int top) {
        Identifier texture = artCache == null ? null : artCache.textureFor(track.albumArtUrl());
        if (texture == null) {
            // Placeholder: a visible tile plus a note rather than a blank square, drawn both while the
            // cover is still being fetched and when the backend supplies no artwork at all.
            HudPainter.fillRounded(context, left, top, left + ART_SIZE, top + ART_SIZE, 4,
                    ColorUtil.scaleAlpha(0xFFFFFFFF, 0.22F));
            int glyph = ART_SIZE / 2;
            HudPainter.noteGlyph(context, left + (ART_SIZE - glyph) / 2, top + (ART_SIZE - glyph) / 2,
                    glyph, ColorUtil.scaleAlpha(0xFFFFFFFF, 0.55F));
        } else {
            // DrawContext#drawTexture needs an explicit pipeline; standalone RGBA art uses GUI_TEXTURED.
            context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, left, top, 0.0F, 0.0F,
                    ART_SIZE, ART_SIZE, ART_SIZE, ART_SIZE);
        }
        HudPainter.strokeRounded(context, left - 1, top - 1, left + ART_SIZE + 1, top + ART_SIZE + 1, 4,
                ColorUtil.scaleAlpha(0xFFFFFFFF, 0.25F));
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        switch (input.key()) {
            case GLFW.GLFW_KEY_LEFT -> {
                service.seek(Math.max(0L, displayProgressMs() - SEEK_STEP_MS));
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                service.seek(displayProgressMs() + SEEK_STEP_MS);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                service.setVolume(volumeSlider.pendingPercent() + VOLUME_STEP);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                service.setVolume(volumeSlider.pendingPercent() - VOLUME_STEP);
                return true;
            }
            default -> {
                return super.keyPressed(input);
            }
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        // Music keeps playing, and polling keeps the panel current, while this screen is open.
        return false;
    }

    /**
     * Draggable progress bar. Reports the pointed-at fraction continuously while dragging and once
     * more when the drag ends, so exactly one seek request reaches the backend.
     */
    private static final class SeekBarWidget extends ClickableWidget {

        private final DoubleSupplier fractionSource;
        private final Consumer<Float> onScrub;
        private final Consumer<Float> onCommit;

        SeekBarWidget(int x, int y, int width, int height, DoubleSupplier fractionSource,
                      Consumer<Float> onScrub, Consumer<Float> onCommit) {
            super(x, y, width, height, Text.translatable("spotifycontrol.gui.seek"));
            this.fractionSource = fractionSource;
            this.onScrub = onScrub;
            this.onCommit = onCommit;
        }

        private float fractionAt(double mouseX) {
            return (float) Math.max(0.0D, Math.min(1.0D, (mouseX - getX()) / Math.max(1, getWidth())));
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            onScrub.accept(fractionAt(click.x()));
        }

        @Override
        protected void onDrag(Click click, double offsetX, double offsetY) {
            onScrub.accept(fractionAt(click.x()));
        }

        @Override
        public void onRelease(Click click) {
            onCommit.accept(fractionAt(click.x()));
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            HudPainter.progressBar(context, getX(), getY() + 2, getRight(), getY() + 2 + BAR_HEIGHT,
                    ModConfig.ProgressStyle.SHADED_GRADIENT,
                    isHovered() || isFocused() ? ACCENT : ACCENT_DIM,
                    (float) fractionSource.getAsDouble());
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /**
     * Volume slider spanning {@code 0..maxPercent}. The value only reaches the backend when the drag
     * ends (or an arrow key is released), so a long drag does not turn into a stream of API calls.
     */
    private static final class VolumeSlider extends SliderWidget {

        private final int maxPercent;
        private final IntConsumer onCommit;
        private int pendingPercent;
        /** {@code true} between press and release, so backend updates do not fight the drag. */
        private boolean dragging;

        VolumeSlider(int x, int y, int width, int height, int maxPercent, int initialPercent,
                     IntConsumer onCommit) {
            super(x, y, width, height, Text.empty(),
                    maxPercent <= 0 ? 0.0D
                            : (double) Math.max(0, Math.min(maxPercent, initialPercent)) / maxPercent);
            this.maxPercent = Math.max(1, maxPercent);
            this.onCommit = onCommit;
            this.pendingPercent = Math.max(0, Math.min(this.maxPercent, initialPercent));
            updateMessage();
        }

        /** @return {@code true} while the handle is being dragged or keyboard-adjusted. */
        boolean isDragging() {
            return dragging;
        }

        /** @return the percentage the slider currently points at. */
        int pendingPercent() {
            return pendingPercent;
        }

        /** Moves the handle without firing a command, used when the backend reports a new volume. */
        void syncTo(int percent) {
            int clamped = Math.max(0, Math.min(maxPercent, percent));
            if (clamped == pendingPercent) {
                return;
            }
            setValue((double) clamped / maxPercent);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("spotifycontrol.gui.volumeValue", pendingPercent));
        }

        @Override
        protected void applyValue() {
            pendingPercent = (int) Math.round(value * maxPercent);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            dragging = true;
            super.onClick(click, doubled);
        }

        @Override
        protected void onDrag(Click click, double offsetX, double offsetY) {
            dragging = true;
            super.onDrag(click, offsetX, offsetY);
        }

        @Override
        public void onRelease(Click click) {
            dragging = false;
            super.onRelease(click);
            onCommit.accept(pendingPercent);
        }

        @Override
        public boolean keyPressed(KeyInput input) {
            boolean handled = super.keyPressed(input);
            if (handled) {
                onCommit.accept(pendingPercent);
            }
            return handled;
        }
    }
}
