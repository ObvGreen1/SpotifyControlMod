package me.obvgreen.spotifycontrol.client.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the LRC timestamped lyric format.
 *
 * <p>LRC looks like this:</p>
 * <pre>
 * [ar:Radiohead]
 * [offset:-500]
 * [00:19.16] When you were here before
 * [00:24.09][01:02.40] Repeated chorus line
 * </pre>
 *
 * <p>Supported details, all of which show up in real-world files served by LRCLIB:</p>
 * <ul>
 *   <li>one or more timestamps in front of a single line (the line is emitted once per timestamp);</li>
 *   <li>{@code mm:ss}, {@code mm:ss.xx}, {@code mm:ss.xxx} and the {@code mm:ss:xx} variant;</li>
 *   <li>minutes wider than two digits, as used by long DJ sets;</li>
 *   <li>metadata tags ({@code [ar:]}, {@code [ti:]} ...) are ignored;</li>
 *   <li>the global {@code [offset:ms]} tag is applied to every line.</li>
 * </ul>
 */
public final class LrcParser {

    /** Matches a single {@code [mm:ss.xx]} timestamp. */
    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** Matches the optional global offset tag. */
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {
    }

    /**
     * Parses an LRC document.
     *
     * @param lrc raw synced lyrics, may be {@code null}
     * @return the parsed lines sorted by timestamp; empty when nothing could be parsed
     */
    public static List<LyricsLine> parse(String lrc) {
        if (lrc == null || lrc.isBlank()) {
            return List.of();
        }

        long offset = 0L;
        Matcher offsetMatcher = OFFSET_TAG.matcher(lrc);
        if (offsetMatcher.find()) {
            try {
                // LRC defines a positive offset as "shift the lyrics earlier".
                offset = -Long.parseLong(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {
                offset = 0L;
            }
        }

        List<LyricsLine> parsed = new ArrayList<>();
        for (String rawLine : lrc.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            Matcher matcher = TIMESTAMP.matcher(rawLine);
            List<Integer> stamps = new ArrayList<>(2);
            int consumed = 0;
            while (matcher.find() && matcher.start() == consumed) {
                int time = toMillis(matcher.group(1), matcher.group(2), matcher.group(3));
                if (time >= 0) {
                    stamps.add((int) Math.max(0L, time + offset));
                }
                consumed = matcher.end();
            }
            if (stamps.isEmpty()) {
                // Metadata tag or a plain line inside a synced document; nothing to anchor it to.
                continue;
            }
            String text = rawLine.substring(consumed).trim();
            for (int stamp : stamps) {
                parsed.add(new LyricsLine(stamp, text));
            }
        }

        parsed.sort(Comparator.comparingInt(LyricsLine::timeMs));
        return List.copyOf(parsed);
    }

    /**
     * Wraps plain, untimed lyrics into un-synced {@link LyricsLine}s so both flavours can travel
     * through the same rendering path.
     *
     * @param plain raw plain lyrics, may be {@code null}
     * @return one line per source line, all with {@code timeMs == -1}
     */
    public static List<LyricsLine> parsePlain(String plain) {
        if (plain == null || plain.isBlank()) {
            return List.of();
        }
        List<LyricsLine> lines = new ArrayList<>();
        for (String rawLine : plain.split("\\R")) {
            String text = rawLine.strip();
            if (!text.isEmpty()) {
                lines.add(new LyricsLine(-1, text));
            }
        }
        return List.copyOf(lines);
    }

    /** Converts a {@code mm:ss[.fraction]} triple into milliseconds, or {@code -1} when invalid. */
    private static int toMillis(String minutes, String seconds, String fraction) {
        try {
            int min = Integer.parseInt(minutes);
            int sec = Integer.parseInt(seconds);
            int millis = 0;
            if (fraction != null) {
                millis = switch (fraction.length()) {
                    case 1 -> Integer.parseInt(fraction) * 100;
                    case 2 -> Integer.parseInt(fraction) * 10;
                    default -> Integer.parseInt(fraction.substring(0, 3));
                };
            }
            return min * 60_000 + sec * 1_000 + millis;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
