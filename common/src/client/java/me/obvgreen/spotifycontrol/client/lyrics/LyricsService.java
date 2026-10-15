package me.obvgreen.spotifycontrol.client.lyrics;

import me.obvgreen.spotifycontrol.client.config.ModConfig;
import me.obvgreen.spotifycontrol.client.spotify.TrackInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the lyrics lookup lifecycle for the currently playing track.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>decide when a lookup is needed (track identity change, manual reload, provider change);</li>
 *   <li>keep an in-memory LRU cache of sheets plus a short-lived negative cache so a track that has
 *       no lyrics is not re-requested on every tenth poll;</li>
 *   <li>publish one immutable {@link LyricsSnapshot} that the render thread can read safely;</li>
 *   <li>never throw into a caller and never block the render or the Spotify worker thread.</li>
 * </ul>
 *
 * <p>Sync caveat: the Windows native fallback backend cannot report a playback position, so for those
 * snapshots the fetched sheet is rendered as a static block and {@link #canSync(TrackInfo)} reports
 * {@code false}.</p>
 */
public final class LyricsService {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Lyrics");
    /** How long a "no lyrics found" result is remembered before a retry is allowed. */
    private static final long NEGATIVE_TTL_MS = 5 * 60 * 1000L;
    /** Maximum number of sheets kept in memory. */
    private static final int CACHE_LIMIT = 64;

    private final ModConfig config;
    private final ExecutorService worker;
    private final AtomicLong generation = new AtomicLong();

    /** Synchronised LRU cache of successfully parsed sheets, keyed by {@link LyricsQuery#key()}. */
    private final Map<String, Lyrics> cache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    /** Negative cache: query key to the epoch millisecond after which a retry is allowed. */
    private final Map<String, Long> misses = new LinkedHashMap<>();

    private volatile LyricsSnapshot snapshot = LyricsSnapshot.idle();
    private volatile String currentKey = "";
    private volatile LyricsFetcher fetcher;
    /** Last track a lookup was started for, so {@link #reload()} can rebuild the query. */
    private volatile LyricsQuery lastQuery;

    public LyricsService(ModConfig config) {
        this.config = config;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-LyricsService");
            thread.setDaemon(true);
            return thread;
        });
        this.fetcher = new LrclibFetcher();
    }

    /** @return the live configuration, used by the commands to read/update the offset. */
    public ModConfig config() {
        return config;
    }

    /** @return the currently published state; safe to call from the render thread. */
    public LyricsSnapshot snapshot() {
        return snapshot;
    }

    /**
     * @param track the snapshot being rendered
     * @return {@code true} when the lyrics can be highlighted against the playback position
     */
    public boolean canSync(@Nullable TrackInfo track) {
        if (track == null || track.durationMs() <= 0L) {
            return false;
        }
        Lyrics lyrics = snapshot.lyrics();
        return lyrics != null && lyrics.synced();
    }

    /**
     * Called whenever the Spotify service publishes a new snapshot. Cheap when nothing changed, which
     * matters because the service publishes on every poll.
     *
     * @param track latest track, {@code null} or offline when nothing plays
     */
    public void onTrack(@Nullable TrackInfo track) {
        if (!config.lyricsEnabled || config.lyricsSource == ModConfig.LyricsSource.OFF) {
            if (snapshot.status() != LyricsStatus.DISABLED) {
                generation.incrementAndGet();
                currentKey = "";
                snapshot = new LyricsSnapshot("", LyricsStatus.DISABLED, null, "");
            }
            return;
        }
        if (track == null || track.offline() || TrackInfo.UNKNOWN_TITLE.equals(track.title())) {
            if (snapshot.status() != LyricsStatus.IDLE) {
                generation.incrementAndGet();
                currentKey = "";
                snapshot = new LyricsSnapshot("", LyricsStatus.IDLE, null, "");
            }
            return;
        }

        LyricsQuery query = LyricsQuery.of(track);
        String key = query.key();
        if (key.equals(currentKey)) {
            // Same track; only the playback position moved. Nothing to do.
            return;
        }
        currentKey = key;
        lookup(query, false);
    }

    /** Forces a fresh lookup for the track that is playing right now, bypassing both caches. */
    public void reload() {
        LyricsQuery query = lastQuery;
        if (query == null || currentKey.isEmpty()) {
            snapshot = LyricsSnapshot.idle();
            return;
        }
        Lyrics cached = cached(query.key());
        if (cached != null) {
            snapshot = new LyricsSnapshot(query.key(), statusFor(cached), cached, "cache");
            return;
        }
        misses.remove(query.key());
        lookup(query, true);
    }

    /**
     * Starts a lookup, publishing {@code LOADING} immediately so the overlay can show a hint.
     *
     * @param query    what to look up
     * @param force    {@code true} to ignore the negative cache
     */
    private void lookup(LyricsQuery query, boolean force) {
        String key = query.key();
        lastQuery = query;

        Lyrics cached = cached(key);
        if (cached != null) {
            snapshot = new LyricsSnapshot(key, statusFor(cached), cached, "cache");
            return;
        }
        Long retryAfter = misses.get(key);
        if (!force && retryAfter != null && retryAfter > System.currentTimeMillis()) {
            snapshot = new LyricsSnapshot(key, LyricsStatus.NOT_FOUND, null, "lrclib");
            return;
        }

        long ticket = generation.incrementAndGet();
        snapshot = new LyricsSnapshot(key, LyricsStatus.LOADING, null, fetcher.id());

        worker.execute(() -> {
            Lyrics result;
            LyricsStatus status;
            String detail;
            try {
                result = fetcher.fetch(query).join();
                status = statusFor(result);
                detail = result.source();
            } catch (CompletionException | IllegalStateException exception) {
                Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
                LOGGER.warn("Lyrics lookup failed for '{} - {}': {}", query.artist(), query.title(),
                        cause.toString());
                result = null;
                status = LyricsStatus.ERROR;
                detail = cause.getClass().getSimpleName();
            } catch (RuntimeException exception) {
                result = null;
                status = LyricsStatus.ERROR;
                detail = exception.getClass().getSimpleName();
            }

            if (ticket != generation.get()) {
                // A newer request superseded this one; drop the stale answer.
                return;
            }
            if (result != null && status == LyricsStatus.READY) {
                store(key, result);
            } else if (status == LyricsStatus.NOT_FOUND || status == LyricsStatus.INSTRUMENTAL) {
                synchronized (misses) {
                    misses.put(key, System.currentTimeMillis() + NEGATIVE_TTL_MS);
                }
            }
            snapshot = new LyricsSnapshot(key, status, result, detail);
        });
    }

    /** Maps a parsed sheet onto its lifecycle status. */
    private static LyricsStatus statusFor(Lyrics lyrics) {
        if (lyrics.instrumental()) {
            return LyricsStatus.INSTRUMENTAL;
        }
        return lyrics.isEmpty() ? LyricsStatus.NOT_FOUND : LyricsStatus.READY;
    }

    @Nullable
    private Lyrics cached(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private void store(String key, Lyrics lyrics) {
        synchronized (cache) {
            cache.put(key, lyrics);
        }
    }

    /** Drops every cached sheet, e.g. after changing the provider in the config screen. */
    public void invalidateCache() {
        synchronized (cache) {
            cache.clear();
        }
        synchronized (misses) {
            misses.clear();
        }
        currentKey = "";
        snapshot = LyricsSnapshot.idle();
    }

    /** Swaps the backing provider; used by the config screen and by tests. */
    public void setFetcher(LyricsFetcher fetcher) {
        if (fetcher == null) {
            return;
        }
        this.fetcher = fetcher;
        invalidateCache();
    }

    /** @return the number of cached sheets, shown in the debug section of the config screen. */
    public int cachedSheets() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Releases the worker plus the HTTP threads of the provider. */
    public void shutdown() {
        generation.incrementAndGet();
        worker.shutdownNow();
        try {
            worker.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        LyricsFetcher active = fetcher;
        if (active instanceof LrclibFetcher lrclib) {
            lrclib.shutdown();
        }
    }
}
