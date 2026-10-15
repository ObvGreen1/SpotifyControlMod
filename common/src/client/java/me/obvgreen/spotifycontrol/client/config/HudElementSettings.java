package me.obvgreen.spotifycontrol.client.config;

import org.jetbrains.annotations.Nullable;

/**
 * Per-element HUD overrides.
 *
 * <p>Every visual field is nullable on purpose: {@code null} means "inherit the global HUD setting",
 * which keeps the configuration file short for players who never touch the per-element options while
 * still allowing one element to use a different colour, opacity or shadow than the rest.</p>
 *
 * <p>The class is a plain Gson-mapped bean, so it must keep a public no-argument constructor and
 * public fields.</p>
 */
public final class HudElementSettings {

    /** Which widget these overrides belong to. */
    public HudElementType type = HudElementType.CARD;

    /** Independent on/off switch for this element. */
    public boolean enabled = true;

    /** Screen corner override, {@code null} inherits {@link ModConfig#hudAnchor hudAnchor}. */
    @Nullable
    public ModConfig.Anchor anchor;

    /** Horizontal pixel offset override, {@code null} inherits {@link ModConfig#hudOffsetX}. */
    @Nullable
    public Integer offsetX;

    /** Vertical pixel offset override, {@code null} inherits {@link ModConfig#hudOffsetY}. */
    @Nullable
    public Integer offsetY;

    /** Scale override, {@code null} inherits {@link ModConfig#hudScale}. */
    @Nullable
    public Float scale;

    /**
     * Element size in local units: panel width for {@code CARD}, max text width for {@code MINI},
     * bar width for {@code PROGRESS}, art edge length for {@code ART} and max line width for
     * {@code LYRICS}. {@code 0} means "use the type default".
     */
    public int size;

    /** ARGB hex text colour override, {@code null} inherits {@link ModConfig#hudTextColor}. */
    @Nullable
    public String textColor;

    /** Background opacity override (0 - 255), {@code null} inherits {@link ModConfig#hudBackgroundOpacity}. */
    @Nullable
    public Integer backgroundOpacity;

    /** Drop-shadow override, {@code null} inherits {@link ModConfig#hudTextShadow}. */
    @Nullable
    public Boolean textShadow;

    /** Marquee override, {@code null} inherits {@link ModConfig#hudMarquee}. */
    @Nullable
    public Boolean marquee;

    /** Number of lyric lines shown around the current one; {@code 0} inherits the global value. */
    public int lyricsLines;

    public HudElementSettings() {
    }

    public HudElementSettings(HudElementType type) {
        this.type = type;
        this.enabled = type.defaultEnabled();
    }

    /** Factory used when a configuration file predates the element system. */
    public static HudElementSettings defaults(HudElementType type) {
        return new HudElementSettings(type);
    }

    // ---------------------------------------------------------------------------------------------
    // Resolution helpers - every getter falls back to the value the caller passes in
    // ---------------------------------------------------------------------------------------------

    public ModConfig.Anchor resolveAnchor(ModConfig.Anchor fallback) {
        return anchor != null ? anchor : fallback;
    }

    public int resolveOffsetX(int fallback) {
        return offsetX != null ? offsetX : fallback;
    }

    public int resolveOffsetY(int fallback) {
        return offsetY != null ? offsetY : fallback;
    }

    public float resolveScale(float fallback) {
        return scale != null && scale > 0.0F ? scale : fallback;
    }

    public int resolveBackgroundOpacity(int fallback) {
        return backgroundOpacity != null ? backgroundOpacity : fallback;
    }

    public boolean resolveTextShadow(boolean fallback) {
        return textShadow != null ? textShadow : fallback;
    }

    public boolean resolveMarquee(boolean fallback) {
        return marquee != null ? marquee : fallback;
    }

    public int resolveLyricsLines(int fallback) {
        return lyricsLines > 0 ? lyricsLines : fallback;
    }

    public int resolveSize() {
        return size > 0 ? size : type.defaultSize();
    }

    /** Clamps hand-edited values into a range the renderer can survive. */
    public void normalise() {
        if (type == null) {
            type = HudElementType.CARD;
        }
        if (size != 0) {
            size = Math.max(16, Math.min(600, size));
        }
        if (scale != null) {
            scale = Math.max(0.25F, Math.min(4.0F, scale));
        }
        if (offsetX != null) {
            offsetX = Math.max(-4000, Math.min(4000, offsetX));
        }
        if (offsetY != null) {
            offsetY = Math.max(-4000, Math.min(4000, offsetY));
        }
        if (backgroundOpacity != null) {
            backgroundOpacity = Math.max(0, Math.min(255, backgroundOpacity));
        }
        if (textColor != null && ColorUtil.parseArgb(textColor, 0) == 0) {
            textColor = null;
        }
        if (lyricsLines < 0) {
            lyricsLines = 0;
        }
        if (lyricsLines > 11) {
            lyricsLines = 11;
        }
    }
}
