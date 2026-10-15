package me.obvgreen.spotifycontrol.client.lyrics;

/**
 * Immutable snapshot of the lyrics state, published by {@link LyricsService}.
 *
 * <p>The HUD reads this from the render thread while the service mutates its own state on a worker
 * thread, so the snapshot must be fully immutable. {@code lyrics} is {@code null} while loading.</p>
 *
 * @param trackKey  {@link LyricsQuery#key()} this snapshot belongs to
 * @param status    what happened
 * @param lyrics    the parsed sheet, or {@code null} when none is available
 * @param detail    short diagnostic string (provider name, error message), may be empty
 */
public record LyricsSnapshot(String trackKey, LyricsStatus status, Lyrics lyrics, String detail) {

    /** The state before anything has been requested. */
    public static LyricsSnapshot idle() {
        return new LyricsSnapshot("", LyricsStatus.IDLE, null, "");
    }

    /** @return {@code true} when lyrics are ready to render. */
    public boolean ready() {
        return status == LyricsStatus.READY && lyrics != null && !lyrics.isEmpty();
    }

    /** @return {@code true} when the overlay should show a status line instead of lyric text. */
    public boolean showStatusLine() {
        return status != LyricsStatus.READY;
    }

    /** @return the loaded sheet, or an empty one so callers never need a null check. */
    public Lyrics lyricsOrEmpty(String source) {
        return lyrics != null ? lyrics : Lyrics.empty(source);
    }
}
