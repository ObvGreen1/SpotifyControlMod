package me.obvgreen.spotifycontrol.client.lyrics;

import java.util.concurrent.CompletableFuture;

/**
 * A source of lyrics.
 *
 * <p>Implementations must never block the calling thread: the returned future is completed on an
 * executor owned by the implementation, and the HUD only ever reads an already-published snapshot.
 * Spotify itself does not expose lyrics through the Web API, so providers are third-party and must
 * be treated as best-effort.</p>
 */
public interface LyricsFetcher {

    /** Short identifier used in log lines and the config screen. */
    String id();

    /**
     * Fetches lyrics for one query.
     *
     * @param query what to look up
     * @return a future that always completes - with {@link Lyrics#empty(String)} when there are no
     *         lyrics and with a failed future only for transport level errors the caller should
     *         surface as "unavailable"
     */
    CompletableFuture<Lyrics> fetch(LyricsQuery query);
}
