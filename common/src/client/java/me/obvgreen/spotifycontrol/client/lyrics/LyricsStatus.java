package me.obvgreen.spotifycontrol.client.lyrics;

/**
 * Lifecycle of the lyrics lookup for the current track.
 *
 * <p>Kept as an enum rather than a set of booleans so the overlay can render exactly one status
 * message and the commands can report something meaningful.</p>
 */
public enum LyricsStatus {

    /** Lyrics are switched off in the configuration. */
    DISABLED,
    /** Nothing is playing. */
    IDLE,
    /** A request is in flight. */
    LOADING,
    /** Lyrics are available. */
    READY,
    /** The provider says the track is instrumental. */
    INSTRUMENTAL,
    /** The provider has no lyrics for this track. */
    NOT_FOUND,
    /** The request failed (offline, provider down, rate limited). */
    ERROR,
    /** The active playback backend cannot report a position, so lyrics cannot be synced. */
    UNSYNCED;

    /** @return a translation key for a short human readable label. */
    public String translationKey() {
        return "spotifycontrol.lyrics.status." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
