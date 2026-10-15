package me.obvgreen.spotifycontrol.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.obvgreen.spotifycontrol.client.config.ModConfig;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads album artwork off the render thread and hands the decoded pixels to OpenGL on the client
 * thread.
 *
 * <p>Three properties matter here:</p>
 * <ol>
 *   <li><b>No blocking on the render thread.</b> HTTP and PNG decoding happen on a private daemon
 *       executor; only the {@code TextureManager.registerTexture} call is hopped back onto the client
 *       thread with {@link Minecraft#execute(Runnable)}, because GL object creation is not
 *       thread safe.</li>
 *   <li><b>Bounded memory.</b> At most {@link #MAX_CACHED_TEXTURES} textures are resident; the least
 *       recently used ones are released when the limit is exceeded.</li>
 *   <li><b>Disk cache.</b> Fetched artwork is mirrored under
 *       {@code config/spotifycontrol/artcache/}, so a restart and a repeat of the same track costs no
 *       network round trip at all.</li>
 * </ol>
 */
public final class AlbumArtCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("SpotifyControl/Art");

    /** Maximum number of album art textures kept on the GPU at once. */
    private static final int MAX_CACHED_TEXTURES = 24;
    /** Reject implausibly large payloads before handing them to the image decoder. */
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    /** Longest edge accepted for album art; larger images are rejected to protect VRAM. */
    private static final int MAX_IMAGE_EDGE = 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "SpotifyControl/1.0 (Fabric client mod)";

    private final ModConfig config;
    private final ExecutorService worker;
    private final HttpClient http;
    private final Path cacheDirectory;
    private final Map<String, Entry> textures = new ConcurrentHashMap<>();
    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record Entry(Identifier id, long lastUsed) {
        Entry touch() {
            return new Entry(id, System.nanoTime());
        }
    }

    public AlbumArtCache(ModConfig config) {
        this.config = config;
        this.cacheDirectory = config.configPath().getParent().resolve("spotifycontrol").resolve("artcache");
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpotifyControl-Art");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(worker)
                .build();
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException exception) {
            LOGGER.warn("Cannot create the album art cache directory {}: {}", cacheDirectory,
                    exception.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the texture for the given artwork URL, scheduling a download when it is not resident
     * yet.
     *
     * <p>Never blocks: {@code null} simply means "not ready yet, try again next frame".</p>
     */
    @Nullable
    public Identifier textureFor(@Nullable String url) {
        if (url == null || url.isBlank() || closed.get()) {
            return null;
        }
        Entry existing = textures.get(url);
        if (existing != null) {
            Entry touched = existing.touch();
            textures.put(url, touched);
            return touched.id();
        }
        schedule(url);
        return null;
    }

    /** Releases every GL texture owned by this cache; call from the client thread on shutdown. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.shutdownNow();
        Minecraft client = Minecraft.getInstance();
        Runnable release = () -> {
            net.minecraft.client.renderer.texture.TextureManager vanilla = client.getTextureManager();
            for (Entry entry : textures.values()) {
                try {
                    vanilla.release(entry.id());
                } catch (RuntimeException exception) {
                    LOGGER.debug("Could not release {}: {}", entry.id(), exception.toString());
                }
            }
            textures.clear();
        };
        if (client.isSameThread()) {
            release.run();
        } else {
            client.execute(release);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Download pipeline
    // ---------------------------------------------------------------------------------------------

    private void schedule(String url) {
        long now = System.currentTimeMillis();
        Long previous = inFlight.get(url);
        if (previous != null && now - previous < 30_000L) {
            // A download for this URL is already pending (or failed very recently).
            return;
        }
        inFlight.put(url, now);
        worker.execute(() -> {
            try {
                byte[] payload = load(url);
                if (payload == null) {
                    return;
                }
                NativeImage image;
                try {
                    image = NativeImage.read(payload);
                } catch (IOException exception) {
                    LOGGER.warn("Album art for {} is not a readable image: {}", url, exception.toString());
                    return;
                }
                if (!valid(image, url)) {
                    image.close();
                    return;
                }
                register(url, image);
            } catch (RuntimeException exception) {
                LOGGER.warn("Album art download failed for {}: {}", url, exception.toString());
            }
        });
    }

    /** Reads the artwork from the disk cache, falling back to a network download. */
    @Nullable
    private byte[] load(String url) {
        // Artwork that is already a local file (the Windows SMTC bridge writes the session
        // thumbnail to %LOCALAPPDATA%\spotifycontrol-art) is read straight from disk: there is
        // nothing to cache and nothing to download.
        Path local = localFile(url);
        if (local != null) {
            try {
                if (!Files.isRegularFile(local)) {
                    LOGGER.debug("Local artwork {} is not on disk.", local);
                    return null;
                }
                long size = Files.size(local);
                if (size <= 0 || size > MAX_IMAGE_BYTES) {
                    LOGGER.warn("Local artwork {} is {} bytes, refusing to decode it.", local, size);
                    return null;
                }
                byte[] bytes = Files.readAllBytes(local);
                if (!looksLikeImage(bytes)) {
                    LOGGER.warn("Local artwork {} does not start with a known image signature.", local);
                    return null;
                }
                return bytes;
            } catch (IOException exception) {
                LOGGER.warn("Local artwork {} unreadable: {}", local, exception.toString());
                return null;
            }
        }

        Path cached = cacheFile(url);
        if (cached != null && Files.isRegularFile(cached)) {
            try {
                byte[] bytes = Files.readAllBytes(cached);
                if (bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES && looksLikeImage(bytes)) {
                    return bytes;
                }
                LOGGER.debug("Discarding unusable cached artwork {}.", cached);
                Files.deleteIfExists(cached);
            } catch (IOException exception) {
                LOGGER.debug("Cached artwork {} unreadable: {}", cached, exception.toString());
            }
        }

        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            LOGGER.debug("Artwork reference {} is neither remote nor a local file; ignoring.", url);
            return null;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/png,image/jpeg,image/webp,*/*;q=0.5")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warn("Album art request for {} answered HTTP {}.", url, response.statusCode());
                return null;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.isEmpty() && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                LOGGER.warn("Album art request for {} returned '{}' instead of an image.", url,
                        contentType);
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                return null;
            }
            if (body.length > MAX_IMAGE_BYTES) {
                LOGGER.warn("Album art for {} is {} bytes, refusing to decode it.", url, body.length);
                return null;
            }
            if (!looksLikeImage(body)) {
                LOGGER.warn("Album art for {} does not start with a known image signature.", url);
                return null;
            }
            if (cached != null) {
                try {
                    Files.createDirectories(cached.getParent());
                    Files.write(cached, body);
                } catch (IOException exception) {
                    LOGGER.debug("Could not cache artwork {}: {}", cached, exception.toString());
                }
            }
            return body;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            LOGGER.warn("Album art download for {} failed: {}", url, exception.toString());
            return null;
        }
    }

    /**
     * Resolves an artwork reference that points at the local filesystem, or {@code null} when it is a
     * remote URL.
     *
     * <p>Accepted forms are {@code file:///C:/...}, {@code file:/C:/...} and a bare absolute path. The
     * Windows SMTC bridge hands the session thumbnail over as a {@code file:///} URL, so this is the
     * normal path for local playback.</p>
     */
    @Nullable
    private static Path localFile(String url) {
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return Path.of(URI.create(trimmed.replace(" ", "%20")));
            } catch (RuntimeException exception) {
                LOGGER.warn("Artwork reference {} is not a usable file URL: {}", url,
                        exception.toString());
                return null;
            }
        }
        // A Windows drive letter or a POSIX absolute path: no scheme, so it is a plain path.
        if (trimmed.matches("^[A-Za-z]:[\\\\/].*") || trimmed.startsWith("\\\\")) {
            return Path.of(trimmed);
        }
        return null;
    }

    private static boolean looksLikeImage(byte[] body) {
        if (body.length < 12) {
            return false;
        }
        boolean png = (body[0] & 0xFF) == 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G';
        boolean jpeg = (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8;
        boolean gif = body[0] == 'G' && body[1] == 'I' && body[2] == 'F';
        boolean webp = body[0] == 'R' && body[1] == 'I' && body[2] == 'F' && body[3] == 'F'
                && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P';
        return png || jpeg || gif || webp;
    }

    private static boolean valid(NativeImage image, String url) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            LOGGER.warn("Album art for {} decoded to an empty image.", url);
            return false;
        }
        if (width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE) {
            LOGGER.warn("Album art for {} is {}x{}, larger than the {}px limit.", url, width, height,
                    MAX_IMAGE_EDGE);
            return false;
        }
        return true;
    }

    /**
     * Uploads the decoded image. Must run on the client thread, so the work is dispatched through
     * {@link Minecraft#execute(Runnable)}; if the game is already shutting down the pixels are
     * simply released.
     */
    private void register(String url, NativeImage image) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            image.close();
            return;
        }
        client.execute(() -> {
            if (closed.get()) {
                image.close();
                return;
            }
            Identifier id = Identifier.fromNamespaceAndPath("spotifycontrol", "album_art/" + Integer.toHexString(url.hashCode()));
            try {
                net.minecraft.client.renderer.texture.TextureManager vanilla = client.getTextureManager();
                if (textures.containsKey(url)) {
                    vanilla.release(id);
                }
                // 1.21.9+ rebuilt texture filtering around GpuSampler, which is created internally by
                // DynamicTexture and is not exposed for mutation any more. The default sampler
                // is used as-is; artwork is drawn at its native size, so the loss is negligible.
                DynamicTexture texture = new DynamicTexture(id::toString, image);
                vanilla.register(id, texture);
                textures.put(url, new Entry(id, System.nanoTime()));
                evictIfNeeded(vanilla);
            } catch (RuntimeException exception) {
                LOGGER.error("Could not register album art texture: {}", exception.toString());
                image.close();
            }
        });
    }

    /** Drops the least recently used textures once the resident count exceeds the cap. */
    private void evictIfNeeded(net.minecraft.client.renderer.texture.TextureManager vanilla) {
        while (textures.size() > MAX_CACHED_TEXTURES) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> candidate : textures.entrySet()) {
                if (candidate.getValue().lastUsed() < oldest) {
                    oldest = candidate.getValue().lastUsed();
                    oldestKey = candidate.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            Entry removed = textures.remove(oldestKey);
            if (removed != null) {
                try {
                    vanilla.release(removed.id());
                } catch (RuntimeException exception) {
                    LOGGER.debug("Could not evict {}: {}", removed.id(), exception.toString());
                }
            }
        }
    }

    /** Maps an artwork URL onto a stable cache file name. */
    @Nullable
    private Path cacheFile(String url) {
        if (cacheDirectory == null) {
            return null;
        }
        String name = Integer.toHexString(url.hashCode()) + "-" + Integer.toHexString(url.length());
        return cacheDirectory.resolve(name + ".img");
    }

    /** Diagnostic helper used by the config screen's debug section. */
    public String describeCache() {
        String[] files = Files.isDirectory(cacheDirectory) ? cacheDirectory.toFile().list() : null;
        int cached = files == null ? 0 : files.length;
        return cached + " cached file(s), " + textures.size() + " resident texture(s)";
    }

    /** Parses the {@code images} array of a Spotify album payload into the best usable URL. */
    @Nullable
    public static String pickArtworkUrl(@Nullable String albumJson) {
        if (albumJson == null || albumJson.isBlank()) {
            return null;
        }
        try {
            JsonObject album = JsonParser.parseString(albumJson).getAsJsonObject();
            if (!album.has("images") || !album.get("images").isJsonArray()) {
                return null;
            }
            var images = album.getAsJsonArray("images");
            String best = null;
            for (int index = 1; index < images.size(); index++) {
                JsonObject image = images.get(index).getAsJsonObject();
                if (image.has("url") && !image.get("url").isJsonNull()) {
                    best = image.get("url").getAsString();
                    break;
                }
            }
            if (best == null && !images.isEmpty()) {
                JsonObject first = images.get(0).getAsJsonObject();
                if (first.has("url") && !first.get("url").isJsonNull()) {
                    best = first.get("url").getAsString();
                }
            }
            return best;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Exposed for diagnostics: number of resident textures. */
    public int residentTextures() {
        return textures.size();
    }

    /** Convenience: the config this cache was built from. */
    public ModConfig config() {
        return config;
    }
}
