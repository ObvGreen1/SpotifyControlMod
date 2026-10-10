package me.obvgreen.spotifycontrol.client.config;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * ARGB hex parsing helpers shared by the configuration and the renderers.
 *
 * <p>Every colour in the mod is stored as a string so it stays readable in the JSON file and so the
 * Cloth Config colour picker round-trips it losslessly - the picker works with a single packed
 * {@code int}, which is exactly what {@link #parseArgb} produces.</p>
 *
 * <p>Accepted spellings: {@code RRGGBB}, {@code AARRGGBB}, optional leading {@code #} or
 * {@code 0x}. A six-digit value is treated as fully opaque.</p>
 */
public final class ColorUtil {

    private ColorUtil() {
    }

    /**
     * Parses an ARGB hex colour.
     *
     * @param raw      the string to parse, may be {@code null}
     * @param fallback value returned when the input is missing or malformed
     * @return the packed ARGB colour, or {@code fallback}
     */
    public static int parseArgb(@Nullable String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        } else if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        try {
            if (value.length() == 6) {
                return 0xFF000000 | (int) Long.parseLong(value, 16);
            }
            if (value.length() == 8) {
                return (int) Long.parseLong(value, 16);
            }
            if (value.length() == 3) {
                // #RGB shorthand, expanded the way CSS does it.
                int r = Integer.parseInt(value.substring(0, 1), 16);
                int g = Integer.parseInt(value.substring(1, 2), 16);
                int b = Integer.parseInt(value.substring(2, 3), 16);
                return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
            if (value.length() == 4) {
                int a = Integer.parseInt(value.substring(0, 1), 16);
                int r = Integer.parseInt(value.substring(1, 2), 16);
                int g = Integer.parseInt(value.substring(2, 3), 16);
                int b = Integer.parseInt(value.substring(3, 4), 16);
                return (a * 17 << 24) | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
        } catch (NumberFormatException ignored) {
            return fallback;
        }
        return fallback;
    }

    /** Formats a packed ARGB colour the way the configuration file stores it. */
    public static String toHex(int argb) {
        return String.format(Locale.ROOT, "%08X", argb);
    }

    /** Replaces the alpha channel of a colour. */
    public static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** Reads the alpha channel of a colour. */
    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** Blends two colours, {@code amount} 0 = {@code from}, 1 = {@code to}. */
    public static int lerp(int from, int to, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int a = Math.round(alpha(from) + (alpha(to) - alpha(from)) * t);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Scales the alpha channel by a factor in {@code 0..1}, used for fades. */
    public static int scaleAlpha(int argb, float factor) {
        return withAlpha(argb, Math.round(alpha(argb) * Math.max(0.0F, Math.min(1.0F, factor))));
    }
}
