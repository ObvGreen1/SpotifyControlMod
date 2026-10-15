package me.obvgreen.spotifycontrol.client.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An immutable, already-parsed lyric sheet.
 *
 * <p>Two flavours exist and both are supported by the HUD:</p>
 * <ul>
 *   <li><b>synced</b> - every line carries a timestamp, so the overlay can follow the song and
 *       highlight the line being sung (LRCLIB calls this {@code syncedLyrics});</li>
 *   <li><b>plain</b> - untimed text, which the overlay shows as a static block (< 0 timestamps,
 *       {@link #synced()} returns {@code false}).</li>
 * </ul>
 *
 * @param synced       {@code true} when the lines carry timestamps
 * @param instrumental {@code true} when the provider reports the track has no lyrics at all
 * @param lines        the parsed lines, in playback order
 * @param source       human readable provider name, used for diagnostics
 */
public record Lyrics(boolean synced, boolean instrumental, List<LyricsLine> lines, String source) {

    public Lyrics {
        lines = lines == null ? List.of() : List.copyOf(lines);
        source = source == null ? "unknown" : source;
    }

    /** A sheet that carries no lyrics at all. */
    public static Lyrics empty(String source) {
        return new Lyrics(false, false, List.of(), source);
    }

    /** A sheet that only exists to say "this track has no lyrics". */
    public static Lyrics instrumental(String source) {
        return new Lyrics(false, true, List.of(), source);
    }

    /** @return {@code true} when there is nothing to render. */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** @return the number of lines. */
    public int size() {
        return lines.size();
    }

    /** @return the line at {@code index}, or {@code null} when out of range. */
    @Nullable
    public LyricsLine line(int index) {
        if (index < 0 || index >= lines.size()) {
            return null;
        }
        return lines.get(index);
    }

    /**
     * Finds the line that should be highlighted for a playback position.
     *
     * <p>Binary search over the timestamps; a position before the first line resolves to index
     * {@code -1} so the overlay can render "instrumental intro" style leading silence.</p>
     *
     * @param positionMs playback position in milliseconds
     * @return the index of the active line, or {@code -1} when none has started yet
     */
    public int indexAt(long positionMs) {
        if (lines.isEmpty()) {
            return -1;
        }
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int time = lines.get(mid).timeMs();
            if (time < 0) {
                // Untimed sheet: nothing to search, the whole block is static.
                return -1;
            }
            if (time <= positionMs) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /**
     * Progress through the active line, used by the karaoke highlight.
     *
     * @param index      index of the active line
     * @param positionMs playback position in milliseconds
     * @param fallbackMs duration used for the last line, whose end is unknown
     * @return a value in {@code 0..1}
     */
    public float lineProgress(int index, long positionMs, long fallbackMs) {
        LyricsLine current = line(index);
        if (current == null || !current.synced()) {
            return 0.0F;
        }
        LyricsLine next = line(index + 1);
        long start = current.timeMs();
        long end = next != null && next.synced() ? next.timeMs() : Math.max(start + 1L, fallbackMs);
        if (end <= start) {
            return 1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, (positionMs - start) / (float) (end - start)));
    }
}
