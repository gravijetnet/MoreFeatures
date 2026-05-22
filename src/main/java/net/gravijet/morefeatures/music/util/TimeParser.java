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

    // Captures optional hours, minutes, seconds in that order.
    private static final Pattern PATTERN =
            Pattern.compile("(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?",
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

        long total = hours * 3600L + minutes * 60L + seconds;
        if (total <= 0) {
            throw new IllegalArgumentException(
                    "Time value must be greater than zero. Got: " + input);
        }
        return total;
    }

    private static long parseGroup(Matcher m, int group) {
        String val = m.group(group);
        if (val == null) return 0;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            // BUG-38: overflow (e.g. 99999999999h) silently returned 0 before; now it throws
            // a user-readable error instead of producing a silent wrong result
            throw new IllegalArgumentException(
                    "Time component too large to parse: '" + val + "'. Use smaller values.");
        }
    }
}
