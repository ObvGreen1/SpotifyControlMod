package me.obvgreen.spotifycontrol.client;

import me.obvgreen.spotifycontrol.SpotifyControlMod;
import me.obvgreen.spotifycontrol.client.command.SpotifyCommand;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.config.ModConfigManager;
import me.obvgreen.spotifycontrol.client.gui.AlbumArtCache;
import me.obvgreen.spotifycontrol.client.gui.SpotifyHudOverlay;
import me.obvgreen.spotifycontrol.client.keybinds.SpotifyKeybinds;
import me.obvgreen.spotifycontrol.client.lyrics.LyricsService;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyService;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: wires configuration, the Spotify service, the HUD, the keybinds and the command
 * together, and tears them all down when the game closes.
 *
 * <p>Every long running task lives on an executor owned by {@link SpotifyService} or
 * {@link AlbumArtCache}. The game thread only ever reads the latest immutable {@link TrackInfo}
 * snapshot and draws it.</p>
 */
public final class SpotifyControlClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpotifyControlMod.MOD_NAME + "/Client");

    @Nullable
    private static SpotifyControlClient instance;

    private final ModConfig config;
    private final SpotifyService service;
    private final AlbumArtCache artCache;
    private final LyricsService lyrics;
    private final SpotifyHudOverlay hud;
    private final SpotifyKeybinds keybinds;
    private final SpotifyCommand command;

    /** Most recent snapshot, replaced wholesale so the render thread never sees a partial update. */
    private volatile TrackInfo latestTrack = TrackInfo.offlineSnapshot();

    /**
     * No-argument constructor required by Fabric's default entrypoint language adapter: the loader
     * reflectively instantiates this class itself. The configuration is pulled from the shared
     * manager, so an explicit config instance is optional (see {@link #SpotifyControlClient(ModConfig)}).
     */
    public SpotifyControlClient() {
        this(ModConfigManager.get());
    }

    /**
     * Creates the client modules around an explicit config instance. Used by tests and tooling that
     * want to run against a detached configuration.
     *
     * @param config configuration to share with every sub-module
     */
    public SpotifyControlClient(ModConfig config) {
        this.config = config;
        this.service = new SpotifyService(config);
        this.artCache = new AlbumArtCache(config);
        this.lyrics = new LyricsService(config);
        this.hud = new SpotifyHudOverlay(config, artCache, lyrics);
        this.keybinds = new SpotifyKeybinds(service, config);
        this.command = new SpotifyCommand(service, config);
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("Spotify Control client v{} initialising.", SpotifyControlMod.version());

        config.normalise();
        service.setListener(track -> {
            // Restart the marquee only when the track identity actually changes, not on every
            // progress tick and not on every offline poll.
            String previousIdentity = identity(latestTrack);
            latestTrack = track;
            if (!identity(track).equals(previousIdentity)) {
                hud.resetMarquee();
            }
            // Lyrics lookups are keyed on the track identity, so the service dedupes normal ticks.
            lyrics.onTrack(track);
        });
        service.setNotifier(message -> {
            // Arrives from the service worker, so chat delivery has to hop back to the client thread.
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> {
                    Player player = client.player;
                    if (player != null) {
                        player.displayClientMessage(Component.literal("[Spotify] ").withStyle(ChatFormatting.GREEN)
                                .append(Component.literal(message).withStyle(ChatFormatting.WHITE)), false);
                    }
                });
            }
        });
        service.start();

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> hud.render(
                drawContext,
                // 1.21.9 renamed these: getTickDelta -> getTickProgress, getLastFrameDuration ->
                // getDynamicDeltaTicks. The latter is still expressed in ticks, so convert to seconds.
                tickCounter.getGameTimeDeltaPartialTick(false),
                tickCounter.getGameTimeDeltaTicks() / 20.0F,
                latestTrack));

        keybinds.register();
        command.register();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

        LOGGER.info("Spotify Control ready. {}", service.statusLine());
    }

    /** Stops every executor and releases GL textures; safe to call more than once. */
    public void shutdown() {
        LOGGER.info("Spotify Control shutting down.");
        try {
            service.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.warn("Service shutdown failed: {}", exception.toString());
        }
        try {
            artCache.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("Art cache shutdown failed: {}", exception.toString());
        }
        try {
            lyrics.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.warn("Lyrics shutdown failed: {}", exception.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Static accessors used by the keybinds, the command and the config screen
    // ---------------------------------------------------------------------------------------------

    /** @return the live client instance, or {@code null} before initialisation. */
    @Nullable
    public static SpotifyControlClient get() {
        return instance;
    }

    /** Saves the shared configuration, ignoring failures so no UI action can crash on I/O. */
    public static void persistConfig() {
        SpotifyControlClient client = instance;
        if (client != null) {
            client.config.save();
        }
    }

    /** @return the shared configuration, or {@code null} before initialisation. */
    @Nullable
    public static ModConfig config() {
        SpotifyControlClient client = instance;
        return client == null ? null : client.config;
    }

    /** @return the shared configuration loaded by {@link ModConfigManager}, creating it on demand. */
    public static ModConfig configManager() {
        return ModConfigManager.get();
    }

    /** @return the service, or {@code null} before initialisation. */
    @Nullable
    public static SpotifyService service() {
        SpotifyControlClient client = instance;
        return client == null ? null : client.service;
    }

    /** @return the keybind holder, or {@code null} before initialisation. */
    @Nullable
    public static SpotifyKeybinds keybinds() {
        SpotifyControlClient client = instance;
        return client == null ? null : client.keybinds;
    }

    /** @return the HUD renderer, or {@code null} before initialisation. */
    @Nullable
    public static SpotifyHudOverlay hud() {
        SpotifyControlClient client = instance;
        return client == null ? null : client.hud;
    }

    /** @return the album art cache, or {@code null} before initialisation. */
    @Nullable
    public static AlbumArtCache albumArt() {
        SpotifyControlClient client = instance;
        return client == null ? null : client.artCache;
    }

    /** @return the lyrics service, or {@code null} before initialisation. */
    @Nullable
    public static LyricsService lyrics() {
        SpotifyControlClient client = instance;
        return client == null ? null : client.lyrics;
    }

    /** @return the configuration actually in use, creating it on demand for early callers. */
    public static ModConfig configOrLoad() {
        SpotifyControlClient client = instance;
        return client != null ? client.config : ModConfigManager.get();
    }

    /** Identity of a snapshot for marquee purposes: everything except progress and play state. */
    private static String identity(TrackInfo track) {
        return track.title() + '\u0000' + track.artist() + '\u0000' + track.album()
                + '\u0000' + track.offline();
    }
}
