package me.obvgreen.spotifycontrol.client.spotify;

import me.obvgreen.spotifycontrol.client.config.ModConfig;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a {@link TrackInfo} snapshot looks like a Spotify advertisement.
 *
 * <p>Spotify offers no API to skip ads, and the Web API only labels a slot as an ad while the player
 * payload omits its item, so detection has to work from indirect evidence:</p>
 *
 * <ol>
 *   <li>{@link TrackInfo#advertisement()} - the Web API said {@code currently_playing_type == "ad"}.
 *       Always trusted.</li>
 *   <li>An unknown artist, the heuristic the mod was asked for: ad slots carry no artist metadata,
 *       while the native window-title backend also reports {@code null} for them.</li>
 *   <li>An optional keyword list, for localised ad titles such as "Advertisement" or "Werbung".</li>
 * </ol>
 *
 * <p>The heuristics are deliberately conservative: an offline, paused or completely empty snapshot
 * never counts, and a snapshot whose title is {@link TrackInfo#UNKNOWN_TITLE} is ignored because
 * there is nothing to reason about. Podcast episodes and local files also report an unknown artist,
 * which is why the feature is off by default and rate limited by {@link SpotifyService}.</p>
 */
public final class AdDetector {

    /** Pattern that never matches, used when the keyword list is empty. */
    private static final Pattern NEVER = Pattern.compile("(?!)");

    /** Keyword separators: comma, semicolon, newline. */
    private static final Pattern SEPARATORS = Pattern.compile("[,;\r\n]+");

    /**
     * Single-entry pattern cache. {@link #keywordPattern(ModConfig)} is called on every poll, so the
     * regex is only rebuilt when the configured keyword text actually changes.
     */
    private static volatile String cachedSource = "\u0000";
    private static volatile Pattern cachedPattern = NEVER;

    private AdDetector() {
    }

    /**
     * @param track  snapshot to inspect; {@code null} is treated as "no ad"
     * @param config live configuration carrying the trigger mode and keyword list
     * @return {@code true} when the snapshot should be treated as an advertisement
     */
    public static boolean isAd(@Nullable TrackInfo track, @Nullable ModConfig config) {
        if (track == null || track.offline() || !track.isPlaying()) {
            return false;
        }
        if (track.advertisement()) {
            return true;
        }
        if (config == null || TrackInfo.UNKNOWN_TITLE.equals(track.title())) {
            return false;
        }
        boolean unknownArtist = hasUnknownArtist(track);
        return switch (config.adSkipTrigger) {
            case UNKNOWN_ARTIST -> unknownArtist;
            case KEYWORDS -> matchesKeyword(track, config);
            case EITHER -> unknownArtist || matchesKeyword(track, config);
        };
    }

    /** @return {@code true} when the snapshot carries no usable artist metadata. */
    public static boolean hasUnknownArtist(@Nullable TrackInfo track) {
        if (track == null) {
            return false;
        }
        String artist = track.artist();
        return artist == null || artist.isBlank() || TrackInfo.UNKNOWN_ARTIST.equalsIgnoreCase(artist);
    }

    /** @return {@code true} when any configured keyword appears in title, artist or album. */
    public static boolean matchesKeyword(TrackInfo track, ModConfig config) {
        Pattern pattern = keywordPattern(config);
        if (pattern == NEVER) {
            return false;
        }
        String haystack = track.title() + '\n' + track.artist() + '\n' + track.album();
        return pattern.matcher(haystack).find();
    }

    /**
     * Builds (and caches) the word-boundary pattern for the configured keyword list.
     *
     * <p>Word boundaries matter: a plain substring search would flag legitimate tracks such as
     * "Bad", "Radio" or "Adore" contained in a longer word.</p>
     */
    public static Pattern keywordPattern(ModConfig config) {
        String source = config.adSkipKeywords == null ? "" : config.adSkipKeywords.trim();
        if (source.equals(cachedSource)) {
            return cachedPattern;
        }
        Pattern built = build(source);
        cachedSource = source;
        cachedPattern = built;
        return built;
    }

    private static Pattern build(String source) {
        if (source.isEmpty()) {
            return NEVER;
        }
        StringBuilder regex = new StringBuilder("(?i)(?<![\\p{L}\\p{N}])(");
        boolean first = true;
        for (String keyword : SEPARATORS.split(source)) {
            String trimmed = keyword.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!first) {
                regex.append('|');
            }
            regex.append(Pattern.quote(trimmed));
            first = false;
        }
        if (first) {
            return NEVER;
        }
        return Pattern.compile(regex.append(")(?![\\p{L}\\p{N}])").toString());
    }
}
