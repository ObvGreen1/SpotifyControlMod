package me.obvgreen.spotifycontrol.client.spotify;

/**
 * Spotify repeat modes, mirroring the {@code state} values accepted by
 * {@code PUT /v1/me/player/repeat}.
 */
public enum RepeatMode {
    /** Repeat is disabled. */
    OFF("off", "Repeat Off"),
    /** Repeat the current playback context (album, playlist, ...). */
    CONTEXT("context", "Repeat All"),
    /** Repeat the single current track. */
    TRACK("track", "Repeat One");

    private final String apiValue;
    private final String displayName;

    RepeatMode(String apiValue, String displayName) {
        this.apiValue = apiValue;
        this.displayName = displayName;
    }

    /** Value sent to the Spotify Web API. */
    public String apiValue() {
        return apiValue;
    }

    /** Human readable label used by the HUD and commands. */
    public String displayName() {
        return displayName;
    }

    /** Cycles {@code OFF -> CONTEXT -> TRACK -> OFF}, matching the "Toggle Repeat" keybind. */
    public RepeatMode next() {
        return switch (this) {
            case OFF -> CONTEXT;
            case CONTEXT -> TRACK;
            case TRACK -> OFF;
        };
    }

    /** Parses a Spotify API {@code repeat_state} value, defaulting to {@link #OFF}. */
    public static RepeatMode fromApiValue(String value) {
        if (value == null) {
            return OFF;
        }
        for (RepeatMode mode : values()) {
            if (mode.apiValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return OFF;
    }
}
