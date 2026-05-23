package net.gravijet.morefeatures.music.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-readable duration strings into total seconds.
 * <p>
 * Supported formats (case-insensitive):
 * <ul>
 *   <li>{@code 30s}          — 30 seconds</li>
 *   <li>{@code 1m30s}        — 90 seconds</li>
 *   <li>{@code 1m}           — 60 seconds</li>
 *   <li>{@code 1h}           — 3600 seconds</li>
 *   <li>{@code 1h30m}        — 5400 seconds</li>
 *   <li>{@code 2h30m15s}     — 9015 seconds</li>
 * </ul>
 * The hours, minutes, and seconds components are parsed positionally and
 * summed; the order ({@code h m s}) is fixed but each is optional.
 */
public final class TimeParser {

    // Captures hours, minutes, seconds; requires at least one component to be present.
    // BUG-19 fix: the lookahead (?=...) ensures the all-optional groups cannot match
    // the empty string, so a bare number or whitespace-only input correctly fails here.
    private static final Pattern PATTERN =
            Pattern.compile("(?=\\d)(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?",
                            Pattern.CASE_INSENSITIVE);

    private TimeParser() {
        // utility class
    }

    /**
     * Parses a duration string and returns the total number of seconds.
     *
     * @param input the raw string from the command argument
     * @return total seconds (always > 0)
     * @throws IllegalArgumentException if the input is blank or contains no
     *                                  recognised time components
     */
    // Maximum permitted duration: 24 hours. Prevents long-overflow in tick calculations.
    private static final long MAX_SECONDS = 86_400L;

    public static long parseSeconds(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Time string must not be empty.");
        }

        Matcher m = PATTERN.matcher(input.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Invalid time format '" + input + "'. "
                    + "Use combinations like 30s, 1m30s, or 1h."
            );
        }

        long hours   = parseGroup(m, 1);
        long minutes = parseGroup(m, 2);
        long seconds = parseGroup(m, 3);

        // Clamp each component before multiplying to prevent long overflow reaching
        // the post-sum check with a wrapped negative value.  MAX_SECONDS / unit is
        // the tightest upper bound that still allows the multiplication to be safe.
        if (hours   > MAX_SECONDS / 3600) {
            throw new IllegalArgumentException("Time '" + input + "' exceeds the maximum of 24h.");
        }
        if (minutes > MAX_SECONDS / 60) {
            throw new IllegalArgumentException("Time '" + input + "' exceeds the maximum of 24h.");
        }
        if (seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Time '" + input + "' exceeds the maximum of 24h.");
        }
        long total = hours * 3600L + minutes * 60L + seconds;
        if (total <= 0) {
            throw new IllegalArgumentException(
                    "Invalid time '" + input + "' — value must be greater than zero. "
                    + "Use a unit suffix: e.g. 30s, 1m30s, 1h.");
        }
        if (total > MAX_SECONDS) {
            throw new IllegalArgumentException(
                    "Time '" + input + "' exceeds the maximum of 24h.");
        }
        return total;
    }

    private static long parseGroup(Matcher m, int group) {
        String val = m.group(group);
        if (val == null) return 0;
        try {
            long v = Long.parseLong(val);
            if (v < 0) throw new IllegalArgumentException(
                    "Time component must not be negative: '" + val + "'.");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Time component too large: '" + val + "'. Use smaller values.");
        }
    }
}
