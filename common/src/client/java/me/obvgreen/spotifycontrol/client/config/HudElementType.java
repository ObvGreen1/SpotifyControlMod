package me.obvgreen.spotifycontrol.client.config;

import me.obvgreen.spotifycontrol.client.config.ModConfig.Anchor;

/**
 * The independent widgets the HUD is made of.
 *
 * <p>Every element carries its own switch, anchor, offsets and scale (see {@link HudElementSettings}),
 * so a player can pin a full card to one corner, a one-line mini bar to another and the lyrics
 * window somewhere else entirely.</p>
 *
 * <p>The declaration order is also the draw order: later elements are painted on top.</p>
 */
public enum HudElementType implements TranslatableOption {

    /** Album art, title, artist, album and the progress bar in a single panel. */
    CARD("Card", "spotifycontrol.hud.element.card", Anchor.TOP_LEFT, 160, true, true),
    /** One-line "title - artist" strip with a hairline progress indicator underneath. */
    MINI("Mini Bar", "spotifycontrol.hud.element.mini", Anchor.TOP_RIGHT, 140, false, true),
    /** Stand-alone progress bar with the elapsed / total time. */
    PROGRESS("Progress Bar", "spotifycontrol.hud.element.progress", Anchor.BOTTOM_CENTER, 160, false, false),
    /** Album artwork only. */
    ART("Album Art", "spotifycontrol.hud.element.art", Anchor.TOP_RIGHT, 48, false, false),
    /** Time-synced lyrics window. */
    LYRICS("Lyrics", "spotifycontrol.hud.element.lyrics", Anchor.BOTTOM_CENTER, 220, true, false);

    private final String displayName;
    private final String translationKey;
    private final Anchor defaultAnchor;
    private final int defaultSize;
    private final boolean defaultEnabled;
    private final boolean defaultMarquee;

    HudElementType(String displayName, String translationKey, Anchor defaultAnchor, int defaultSize,
                   boolean defaultEnabled, boolean defaultMarquee) {
        this.displayName = displayName;
        this.translationKey = translationKey;
        this.defaultAnchor = defaultAnchor;
        this.defaultSize = defaultSize;
        this.defaultEnabled = defaultEnabled;
        this.defaultMarquee = defaultMarquee;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String translationKey() {
        return translationKey;
    }

    /** Corner used when the user has not overridden the anchor. */
    public Anchor defaultAnchor() {
        return defaultAnchor;
    }

    /** Local-unit size used when the user left the size at {@code 0}: panel width, bar width or art size. */
    public int defaultSize() {
        return defaultSize;
    }

    /** Whether the element is switched on for a brand new configuration. */
    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    /** Whether long text scrolls by default for this element. */
    public boolean defaultMarquee() {
        return defaultMarquee;
    }

    /** Safe parse for hand-edited configuration files. */
    public static HudElementType fromName(String name, HudElementType fallback) {
        if (name == null) {
            return fallback;
        }
        for (HudElementType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return fallback;
    }
}
