package me.obvgreen.spotifycontrol.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide holder for the single {@link ModConfig} instance.
 *
 * <p>Keeping construction here (instead of executing static initialisers inside {@link ModConfig})
 * means the config is only ever built when a client entrypoint actually asks for it, which keeps the
 * class safe if the mod is ever loaded in an unexpected environment.</p>
 */
public final class ModConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Config");

    private static volatile ModConfig instance;

    private ModConfigManager() {
    }

    /** @return the shared configuration, loading it from disk on first use. */
    public static ModConfig get() {
        ModConfig local = instance;
        if (local == null) {
            synchronized (ModConfigManager.class) {
                local = instance;
                if (local == null) {
                    local = ModConfig.load();
                    local.save();
                    instance = local;
                    LOGGER.info("Configuration loaded from {}", ModConfig.configPath());
                }
            }
        }
        return local;
    }

    /** Persists the shared configuration. */
    public static void save() {
        get().save();
    }

    /** Replaces the shared instance (used by tests and by the "reset" path of the config screen). */
    public static void replace(ModConfig config) {
        synchronized (ModConfigManager.class) {
            instance = config;
        }
    }
}
