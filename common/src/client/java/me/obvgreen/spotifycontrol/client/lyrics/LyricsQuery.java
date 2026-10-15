package me.obvgreen.spotifycontrol.client.lyrics;

import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The lookup key handed to a {@link LyricsFetcher}.
 *
 * @param title    track title
 * @param artist   primary artist list as reported by Spotify
 * @param album    album name, may be empty
 * @param durationMs track duration in milliseconds, {@code 0} when unknown
 */
public record LyricsQuery(String title, String artist, String album, long durationMs) {

    /** Builds a query from a playback snapshot. */
    public static LyricsQuery of(TrackInfo track) {
        return new LyricsQuery(track.title(), track.artist(), track.album(), track.durationMs());
    }

    /**
     * Stable identity of a query. Used as the lyrics cache key and to detect track changes without
     * re-fetching when only the playback position moved.
     *
     * <p>The duration is bucketed to whole seconds so the millisecond jitter between two polls of the
     * same track never invalidates the cache, while a genuinely different recording still misses.</p>
     *
     * @return a lower-cased key
     */
    public String key() {
        long seconds = durationMs / 1000L;
        return (title + '\u0000' + artist + '\u0000' + album + '\u0000' + seconds).toLowerCase(Locale.ROOT);
    }

    /** @return the artist without Spotify's {@code feat.}/{@code &} decorations, for looser matching. */
    public String primaryArtist() {
        String value = artist == null ? "" : artist;
        int cut = value.length();
        for (String marker : new String[]{",", " feat.", " feat ", " featuring", " & ", " x "}) {
            int index = value.toLowerCase(Locale.ROOT).indexOf(marker);
            if (index > 0 && index < cut) {
                cut = index;
            }
        }
        return value.substring(0, cut).trim();
    }

    /** @return the title with common " - Remastered 2011" style suffixes removed. */
    public String cleanTitle() {
        String value = title == null ? "" : title.trim();
        int dash = value.lastIndexOf(" - ");
        if (dash > 0) {
            String suffix = value.substring(dash + 3).toLowerCase(Locale.ROOT);
            if (suffix.contains("remaster") || suffix.contains("live") || suffix.contains("version")
                    || suffix.contains("edit") || suffix.contains("mix")) {
                value = value.substring(0, dash).trim();
            }
        }
        return value;
    }

    /** @return the album, or {@code null} when it is not usable for a lookup. */
    @Nullable
    public String usableAlbum() {
        return album == null || album.isBlank() ? null : album.trim();
    }
}
