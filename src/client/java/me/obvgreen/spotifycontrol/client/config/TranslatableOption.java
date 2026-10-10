package me.obvgreen.spotifycontrol.client.config;

/**
 * Implemented by every enum that is shown in the Cloth Config screen.
 *
 * <p>The config screen needs two things from an option: a stable translation key (so the label can
 * be localised) and a plain-text fallback (so a missing language entry still renders something
 * readable). Keeping both on the enum means the selector provider stays a single generic helper
 * instead of one switch statement per enum.</p>
 */
public interface TranslatableOption {

    /** Translation key looked up in the language file. */
    String translationKey();

    /** English label used when the translation key is missing. */
    String displayName();
}
