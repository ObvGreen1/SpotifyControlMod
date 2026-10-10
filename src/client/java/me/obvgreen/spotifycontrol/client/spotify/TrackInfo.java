package me.obvgreen.spotifycontrol.client.spotify;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable snapshot of the track Spotify is currently reporting.
 *
 * <p>Every field is nullable-safe: {@link #offline()} produces a well-formed value describing the
 * "Spotify is not reachable / nothing is playing" state so callers never have to null-check.</p>
 *
 * @param title        track title
 * @param artist       primary artist (comma separated list when Spotify reports several)
 * @param album        album name
 * @param albumArtUrl  absolute URL of the album artwork, or {@code null} when unknown
 * @param durationMs   total track duration in milliseconds, {@code 0} when unknown
 * @param progressMs   current playback position in milliseconds, {@code 0} when unknown
 * @param isPlaying    {@code true} while the track is actually playing
 * @param repeatMode   current repeat mode reported by the active controller
 * @param device       name of the active playback device, or {@code null}
 * @param lastUpdated  {@link System#currentTimeMillis()} timestamp of this snapshot
 * @param offline      {@code true} when the snapshot could not be produced by a live controller
 */
public record TrackInfo(
        String title,
        String artist,
        String album,
        @Nullable String albumArtUrl,
        long durationMs,
        long progressMs,
        boolean isPlaying,
        RepeatMode repeatMode,
        @Nullable String device,
        long lastUpdated,
        boolean offline
) {

    public static final String UNKNOWN_TITLE = "Unknown Track";
    public static final String UNKNOWN_ARTIST = "Unknown Artist";
    public static final String UNKNOWN_ALBUM = "Unknown Album";

    public TrackInfo {
        title = (title == null || title.isBlank()) ? UNKNOWN_TITLE : title.trim();
        artist = (artist == null || artist.isBlank()) ? UNKNOWN_ARTIST : artist.trim();
        album = (album == null || album.isBlank()) ? UNKNOWN_ALBUM : album.trim();
        durationMs = Math.max(0L, durationMs);
        progressMs = Math.max(0L, progressMs);
        if (durationMs > 0L && progressMs > durationMs) {
            progressMs = durationMs;
        }
        repeatMode = repeatMode == null ? RepeatMode.OFF : repeatMode;
        lastUpdated = lastUpdated <= 0L ? System.currentTimeMillis() : lastUpdated;
    }

    /** Builds a live snapshot, stamping it with the current time. */
    public static TrackInfo of(String title,
                               String artist,
                               String album,
                               @Nullable String albumArtUrl,
                               long durationMs,
                               long progressMs,
                               boolean isPlaying,
                               RepeatMode repeatMode,
                               @Nullable String device) {
        return new TrackInfo(title, artist, album, albumArtUrl, durationMs, progressMs, isPlaying,
                repeatMode, device, System.currentTimeMillis(), false);
    }

    /** The canonical "nothing to show" value used for the offline / unauthenticated HUD state. */
    public static TrackInfo offlineSnapshot() {
        return new TrackInfo(UNKNOWN_TITLE, UNKNOWN_ARTIST, UNKNOWN_ALBUM, null, 0L, 0L, false,
                RepeatMode.OFF, null, System.currentTimeMillis(), true);
    }

    /** Returns a copy with an updated progress value, used for client-side interpolation. */
    public TrackInfo withProgress(long newProgressMs, boolean stillPlaying) {
        return new TrackInfo(title, artist, album, albumArtUrl, durationMs, newProgressMs, stillPlaying,
                repeatMode, device, System.currentTimeMillis(), offline);
    }

    /** Returns a copy flagged as paused, used for optimistic UI updates. */
    public TrackInfo withPlaying(boolean playing) {
        return new TrackInfo(title, artist, album, albumArtUrl, durationMs, progressMs, playing,
                repeatMode, device, System.currentTimeMillis(), offline);
    }

    /** Returns a copy carrying a new repeat mode. */
    public TrackInfo withRepeatMode(RepeatMode mode) {
        return new TrackInfo(title, artist, album, albumArtUrl, durationMs, progressMs, isPlaying,
                mode, device, System.currentTimeMillis(), offline);
    }

    /** Returns a copy carrying a new album art URL (kept in sync by the web API controller). */
    public TrackInfo withAlbumArtUrl(@Nullable String url) {
        return new TrackInfo(title, artist, album, url, durationMs, progressMs, isPlaying, repeatMode,
                device, System.currentTimeMillis(), offline);
    }

    public boolean hasDuration() {
        return durationMs > 0L;
    }

    public boolean hasProgress() {
        return hasDuration() && progressMs > 0L;
    }

    /** Fraction of the track already played, clamped to {@code [0, 1]}. */
    public float progressFraction() {
        if (!hasDuration()) {
            return 0.0F;
        }
        return Math.min(1.0F, (float) progressMs / (float) durationMs);
    }

    /** {@code "1:23"} style rendering of the elapsed time. */
    public String formattedProgress() {
        return formatTime(progressMs);
    }

    /** {@code "1:23"} style rendering of the total duration. */
    public String formattedDuration() {
        return formatTime(durationMs);
    }

    /** Renders a millisecond value as {@code m:ss}, or {@code -:--} when unknown. */
    public static String formatTime(long millis) {
        if (millis <= 0L) {
            return "-:--";
        }
        long totalSeconds = millis / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes >= 60L) {
            long hours = minutes / 60L;
            long restMinutes = minutes % 60L;
            return String.format("%d:%02d:%02d", hours, restMinutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    /** {@code "Title - Artist"} one-liner used for logs and chat feedback. */
    public String displayName() {
        return title + " - " + artist;
    }

    @Override
    public String toString() {
        return "TrackInfo[" + displayName() + ", playing=" + isPlaying + ", progress=" + formattedProgress()
                + "/" + formattedDuration() + ", repeat=" + repeatMode + ", offline=" + offline + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrackInfo info)) {
            return false;
        }
        return durationMs == info.durationMs
                && progressMs == info.progressMs
                && isPlaying == info.isPlaying
                && offline == info.offline
                && repeatMode == info.repeatMode
                && title.equals(info.title)
                && artist.equals(info.artist)
                && album.equals(info.album)
                && Objects.equals(albumArtUrl, info.albumArtUrl)
                && Objects.equals(device, info.device);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, artist, album, albumArtUrl, durationMs, progressMs, isPlaying, repeatMode,
                device, offline);
    }
}
