package me.obvgreen.spotifycontrol.client.keybinds;

import me.obvgreen.spotifycontrol.client.SpotifyControlClient;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.gui.SpotifyGuiScreen;
import me.obvgreen.spotifycontrol.client.spotify.SpotifyService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers and polls the mod's keybinds.
 *
 * <p>All keys live in the {@code key.category.spotifycontrol} category, which Minecraft shows as
 * "Spotify Control" in the vanilla Controls screen. They are polled once per client tick - never from
 * the render thread - and the actual work is handed to {@link SpotifyService}, which runs it on its
 * own background executor.</p>
 */
public final class SpotifyKeybinds {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Keys");

    /** Translation key of the keybind category. */
    public static final String CATEGORY = "key.category.spotifycontrol";

    /**
     * Category the bindings are filed under. 1.21.9 replaced the plain category string with a
     * {@link KeyMapping.Category}; its label is derived as {@code key.category.<namespace>.<path>},
     * so {@code spotifycontrol:main} resolves to {@link #CATEGORY}. {@code create} registers the
     * category globally and rejects duplicates, hence the single shared constant.
     */
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spotifycontrol", "main"));

    private final SpotifyService service;
    private final ModConfig config;

    private KeyMapping playPauseKey;
    private KeyMapping nextKey;
    private KeyMapping previousKey;
    private KeyMapping repeatKey;
    private KeyMapping toggleHudKey;
    private KeyMapping toggleLyricsKey;
    private KeyMapping openMenuKey;

    public SpotifyKeybinds(SpotifyService service, ModConfig config) {
        this.service = service;
        this.config = config;
    }

    /** Creates the bindings and subscribes the tick handler. Called once during client init. */
    public void register() {
        playPauseKey = register("key.spotifycontrol.play_pause", GLFW.GLFW_KEY_UNKNOWN);
        nextKey = register("key.spotifycontrol.next", GLFW.GLFW_KEY_UNKNOWN);
        previousKey = register("key.spotifycontrol.previous", GLFW.GLFW_KEY_UNKNOWN);
        repeatKey = register("key.spotifycontrol.toggle_repeat", GLFW.GLFW_KEY_UNKNOWN);
        toggleHudKey = register("key.spotifycontrol.toggle_hud", GLFW.GLFW_KEY_UNKNOWN);
        toggleLyricsKey = register("key.spotifycontrol.toggle_lyrics", GLFW.GLFW_KEY_UNKNOWN);
        openMenuKey = register("key.spotifycontrol.open_menu", GLFW.GLFW_KEY_UNKNOWN);
        ClientTickEvents.END_CLIENT_TICK.register(client -> poll());
        LOGGER.info("Registered Spotify Control keybinds under category '{}'.", CATEGORY);
    }

    private static KeyMapping register(String translationKey, int defaultKeyCode) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(
                translationKey,
                InputConstants.Type.KEYSYM,
                defaultKeyCode,
                KEY_CATEGORY));
    }

    /**
     * Consumes every queued press. {@code wasPressed()} is drained in a loop because more than one
     * press can accumulate between ticks when the game is lagging.
     */
    private void poll() {
        while (playPauseKey.consumeClick()) {
            service.togglePlayPause();
        }
        while (nextKey.consumeClick()) {
            service.next();
        }
        while (previousKey.consumeClick()) {
            service.previous();
        }
        while (repeatKey.consumeClick()) {
            service.cycleRepeat();
        }
        while (toggleHudKey.consumeClick()) {
            toggleHud();
        }
        while (toggleLyricsKey.consumeClick()) {
            toggleLyrics();
        }
        while (openMenuKey.consumeClick()) {
            openMenu();
        }
    }

    /**
     * Opens the control console. The keybind poll already runs on the client thread, which is where
     * {@code setScreen} has to happen, so no scheduling is needed here.
     */
    private void openMenu() {
        Minecraft client = Minecraft.getInstance();
        SpotifyGuiScreen.open(client.screen);
    }

    private void toggleHud() {
        config.hudEnabled = !config.hudEnabled;
        SpotifyControlClient.configManager().save();
        LOGGER.info("HUD {}", config.hudEnabled ? "enabled" : "disabled");
    }

    private void toggleLyrics() {
        config.toggleLyrics();
        SpotifyControlClient.configManager().save();
        LOGGER.info("HUD lyrics {}", config.lyricsEnabled ? "enabled" : "disabled");
    }

    /** @return the keybind that toggles the lyrics element, used by the config screen shortcut. */
    public KeyMapping toggleLyricsKey() {
        return toggleLyricsKey;
    }

    /** @return the keybind that toggles the HUD, used by the config screen's shortcut button. */
    public KeyMapping toggleHudKey() {
        return toggleHudKey;
    }

    /** @return the play/pause keybind. */
    public KeyMapping playPauseKey() {
        return playPauseKey;
    }

    /** @return the next-track keybind. */
    public KeyMapping nextKey() {
        return nextKey;
    }

    /** @return the previous-track keybind. */
    public KeyMapping previousKey() {
        return previousKey;
    }

    /** @return the repeat-cycle keybind. */
    public KeyMapping repeatKey() {
        return repeatKey;
    }

    /** @return the keybind that opens the control console. */
    public KeyMapping openMenuKey() {
        return openMenuKey;
    }

    /** @return every binding in display order. */
    public KeyMapping[] all() {
        return new KeyMapping[]{playPauseKey, nextKey, previousKey, repeatKey, openMenuKey,
                toggleHudKey, toggleLyricsKey};
    }
}
