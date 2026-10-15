package me.obvgreen.spotifycontrol;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (loader-agnostic) entrypoint for Spotify Control.
 *
 * <p>The mod is declared with {@code "environment": "client"} and every feature lives in the client
 * sourceset, so this initialiser does nothing but publish the shared logger and version metadata.
 * Keeping a real entrypoint here means the mod is still loadable even when the client sourceset is
 * disabled (for example by tooling that only inspects the common sourceset).</p>
 */
public class SpotifyControlMod implements ModInitializer {

    /** Mod id, matching {@code fabric.mod.json}. */
    public static final String MOD_ID = "spotifycontrol";
    /** Human readable name used in log messages. */
    public static final String MOD_NAME = "Spotify Control";

    private static final Logger COMMON_LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static String version = "dev";

    @Override
    public void onInitialize() {
        version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");

        COMMON_LOGGER.info("{} v{} loaded (all functionality is provided by the client entrypoint).",
                MOD_NAME, version);
    }

    /** @return the mod version reported by the loader, or {@code "dev"} outside a loader. */
    public static String version() {
        return version;
    }
}
