package me.obvgreen.spotifycontrol.client.lyrics;

/**
 * One lyric line.
 *
 * @param timeMs start of the line in milliseconds, or {@code -1} when the source had no timing
 *               information (plain-text lyrics)
 * @param text   the line itself, never {@code null}
 */
public record LyricsLine(int timeMs, String text) {

    public LyricsLine {
        text = text == null ? "" : text;
    }

    /** @return {@code true} when the line carries a usable timestamp. */
    public boolean synced() {
        return timeMs >= 0;
    }

    /** @return {@code true} when the line only contains whitespace. */
    public boolean blank() {
        return text.isBlank();
    }
}
