package me.obvgreen.spotifycontrol.client.gui.hud;

import me.obvgreen.spotifycontrol.client.config.ColorUtil;
import me.obvgreen.spotifycontrol.client.config.HudElementSettings;
import me.obvgreen.spotifycontrol.client.config.HudElementType;
import me.obvgreen.spotifycontrol.client.config.ModConfig;

/**
 * One element's settings with every "inherit" override already resolved against the global
 * configuration, so renderers never have to look at {@link ModConfig} fallback rules themselves.
 *
 * @param type              which widget this is
 * @param anchor            resolved screen corner
 * @param offsetX           resolved horizontal offset in pixels
 * @param offsetY           resolved vertical offset in pixels
 * @param scale             resolved gui scale multiplier
 * @param size              resolved element size in local units
 * @param textColor         resolved primary text colour
 * @param secondaryColor    resolved secondary text colour
 * @param tertiaryColor     resolved tertiary text colour
 * @param accentColor       resolved accent colour
 * @param background        resolved panel background colour, opacity already applied
 * @param border            resolved panel border colour
 * @param backgroundOpacity resolved raw opacity, needed when an element wants a fraction of it
 * @param textShadow        resolved drop-shadow flag
 * @param marquee           resolved scrolling flag
 * @param lyricsLines       resolved number of lyric lines to show
 * @param cornerRadius      resolved panel corner radius
 */
public record HudLayout(HudElementType type,
                        ModConfig.Anchor anchor,
                        int offsetX,
                        int offsetY,
                        float scale,
                        int size,
                        int textColor,
                        int secondaryColor,
                        int tertiaryColor,
                        int accentColor,
                        int background,
                        int border,
                        int backgroundOpacity,
                        boolean textShadow,
                        boolean marquee,
                        int lyricsLines,
                        int cornerRadius) {

    /** Builds the resolved layout for one element. */
    public static HudLayout resolve(ModConfig config, HudElementType type) {
        HudElementSettings settings = config.element(type);
        int textColor = settings.textColor != null
                ? ColorUtil.parseArgb(settings.textColor, config.hudTextColorArgb())
                : config.hudTextColorArgb();
        int opacity = Math.max(0, Math.min(255, settings.resolveBackgroundOpacity(config.hudBackgroundOpacity)));
        int background = ColorUtil.withAlpha(
                ColorUtil.parseArgb(config.hudBackgroundColor, 0xFF000000), opacity);
        int size = settings.size > 0
                ? settings.size
                : (type == HudElementType.CARD ? config.hudCardWidth : type.defaultSize());
        return new HudLayout(
                type,
                settings.resolveAnchor(type.defaultAnchor()),
                settings.resolveOffsetX(config.hudOffsetX),
                settings.resolveOffsetY(config.hudOffsetY),
                settings.resolveScale(config.hudScale),
                Math.max(16, size),
                textColor,
                config.hudSecondaryTextColorArgb(),
                config.hudTertiaryTextColorArgb(),
                config.hudAccentColorArgb(),
                background,
                config.hudBorderArgb(),
                opacity,
                settings.resolveTextShadow(config.hudTextShadow),
                config.hudMarquee && settings.resolveMarquee(type.defaultMarquee()),
                Math.max(1, Math.min(11, settings.resolveLyricsLines(config.lyricsLines))),
                Math.max(0, Math.min(12, config.hudCornerRadius)));
    }

    /** @return the configured opacity scaled by {@code factor}, still in {@code 0..255}. */
    public int opacityScaled(float factor) {
        return Math.max(0, Math.min(255, Math.round(backgroundOpacity * factor)));
    }

    /** @return the panel background colour at a fraction of the configured opacity. */
    public int backgroundScaled(float factor) {
        return ColorUtil.withAlpha(background, opacityScaled(factor));
    }

    /** @return the text colour at a fraction of its own alpha, used for lyric fade-out. */
    public int fade(int color, float factor) {
        return ColorUtil.scaleAlpha(color, factor);
    }
}
