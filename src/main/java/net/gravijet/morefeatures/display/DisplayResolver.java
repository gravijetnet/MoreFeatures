package net.gravijet.morefeatures.display;

import org.bukkit.entity.Player;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.disguise.IDisguiseData;
import xyz.refinedev.phoenix.profile.tag.ITag;
import xyz.refinedev.phoenix.rank.IRank;

import java.util.UUID;

/**
 * Everything the tablist, the nametag and the chat need to know about a player,
 * resolved from Phoenix and MBedwars.
 *
 * This is the single source of truth. TAB renders it, chat renders it, and
 * neither of them decides anything for itself — which is the point: the rank a
 * player sees above their head, in the tablist and in front of their chat
 * messages now comes from one place and cannot drift apart.
 *
 * Nothing here returns null. A missing profile or a rank with no prefix is
 * normal during the first ticks of a login and on a Phoenix that is still
 * loading; returning empty text there is exactly the bug being fixed, because
 * an empty prefix is what renders a name white with no rank in front of it.
 */
public class DisplayResolver {

    /**
     * Priorities are clamped into this range before being inverted for the sort
     * key. Four digits keeps every key the same width, which is what makes a
     * plain alphabetical comparison order them numerically.
     */
    private static final int MAX_PRIORITY = 9999;

    /**
     * Width of one team's band in the numeric sort weight. Every rank score fits
     * inside a band, so a better rank can never lift a player out of their team.
     */
    private static final int TEAM_BAND = MAX_PRIORITY + 1;

    private final ArenaLookup   arenas;
    private final DisplayConfig config;

    public DisplayResolver(ArenaLookup arenas, DisplayConfig config) {
        this.arenas = arenas;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    //  Sorting
    // -------------------------------------------------------------------------

    /**
     * The one value TAB sorts on, for both the lobby and a running game.
     *
     * TAB cannot switch sorting rules per world, so the rule has to live inside
     * the value instead. The key is fixed-width and ordered so that a plain
     * A-to-Z comparison produces:
     *
     * <pre>
     *   0 | 03 | 0002 | notch
     *   ^    ^     ^      ^
     *   |    |     |      name, so two equal ranks never swap places at random
     *   |    |     inverted rank priority — highest rank first
     *   |    team slot — a game groups by team colour before rank
     *   in a game before lobby players
     * </pre>
     *
     * Outside a game the team slot is a constant, so the key collapses to rank
     * priority and the lobby sorts purely by rank.
     */
    /**
     * The numeric sort weight, for TAB's {@code PLACEHOLDER_HIGH_TO_LOW}.
     *
     * One number carrying both rules, highest first:
     *
     * <pre>
     *   991000  yellow team, owner      team band 99, rank 1000
     *   990000  yellow team, default    team band 99, rank 0
     *   971000  red team, owner         team band 97, rank 1000
     *    10000  spectator               team band 1
     *     1000  lobby, owner            team band 0, rank 1000
     *        0  lobby, default
     * </pre>
     *
     * The team band counts down from 99 because HIGH_TO_LOW puts the biggest
     * number on top, while teams should read in their normal order. A rank score
     * can never reach {@link #TEAM_BAND}, so no rank can promote a player out of
     * their own team — which is the whole point of grouping by team first.
     *
     * Ties are left to a second sorting type in TAB, normally
     * {@code PLACEHOLDER_A_TO_Z:%player%}.
     */
    public String sortWeight(Player player) {
        return Integer.toString(buildSortWeight(arenas.teamIndex(player), rankScore(player)));
    }

    /** The weight arithmetic, split out so it can be exercised without a server. */
    static int buildSortWeight(int teamIndex, int rankScore) {
        int band = ArenaLookup.NONE - teamIndex;
        return band * TEAM_BAND + rankScore;
    }

    public String sortKey(Player player) {
        return buildSortKey(arenas.teamIndex(player), MAX_PRIORITY - rankScore(player),
                player.getName());
    }

    /** The key layout itself, split out so it can be exercised without a server. */
    static String buildSortKey(int teamIndex, int invertedPriority, String name) {
        char context = teamIndex == ArenaLookup.NONE ? '1' : '0';

        return context
                + pad(teamIndex, 2)
                + pad(invertedPriority, 4)
                + name.toLowerCase();
    }

    /**
     * How high the player's rank should place them, from 0 to {@value #MAX_PRIORITY},
     * higher being further up the tablist.
     *
     * Phoenix numbers ranks so that a higher priority is a higher rank — its own
     * whitelist check reads {@code getGeneralRank().getPriority() >= rank.getPriority()},
     * which only holds that way round. {@code sorting.higher-priority-first} in
     * display.yml exists for a network that numbered them the other way.
     *
     * {@code getFakePriority} rather than {@code getPriority}: a disguised staff
     * member has to sort where their disguise says they sort, or the disguise is
     * given away by their position in the tablist.
     */
    private int rankScore(Player player) {
        IProfile profile = profile(player);
        int priority = 0;
        if (profile != null) {
            try {
                priority = profile.getFakePriority();
            } catch (Throwable ignored) {
                // Phoenix resolves the disguise rank here and can throw while a
                // profile is still cold. The real priority is a fine answer.
                IRank rank = profile.getHighestRank();
                if (rank != null) priority = rank.getPriority();
            }
        }
        priority = Math.max(0, Math.min(MAX_PRIORITY, priority));
        return config.isHigherPriorityFirst() ? priority : MAX_PRIORITY - priority;
    }

    // -------------------------------------------------------------------------
    //  Rank text
    // -------------------------------------------------------------------------

    /** The rank prefix, for the nametag and chat. Never empty. */
    public String prefix(Player player) {
        IRank rank = displayRank(player);
        if (rank == null) return config.getPrefixFallback();
        return orFallback(firstNonBlank(rank.getPrefix(), rank.getPrefixLegacy()));
    }

    /**
     * The tablist prefix.
     *
     * Phoenix keeps a separate playerlist prefix because the tablist is narrow
     * and most networks shorten the rank there. Falling back to the normal
     * prefix when it is unset is what stops the tablist going bare while the
     * nametag is fine.
     */
    public String tabPrefix(Player player) {
        IRank rank = displayRank(player);
        if (rank == null) return config.getPrefixFallback();
        return orFallback(firstNonBlank(
                rank.getPlayerListPrefix(), rank.getPlayerListPrefixLegacy(),
                rank.getPrefix(), rank.getPrefixLegacy()));
    }

    public String suffix(Player player) {
        IRank rank = displayRank(player);
        if (rank == null) return "";
        return firstNonBlank(rank.getSuffix(), rank.getSuffixLegacy());
    }

    /**
     * The colour the player's own name is drawn in.
     *
     * In a game this is the team colour — a bedwars tablist that colours names
     * by rank instead of by team is unreadable mid-fight. Outside one it is the
     * rank colour.
     */
    public String nameColor(Player player) {
        String team = arenas.teamColor(player);
        if (!team.isEmpty()) return team;

        IRank rank = displayRank(player);
        if (rank == null) return config.getPrefixFallback();
        return orFallback(firstNonBlank(rank.getColor(), rank.getColorLegacy()));
    }

    /** The player's chosen Phoenix tag, or empty if they have none. */
    public String tag(Player player) {
        IProfile profile = profile(player);
        if (profile == null) return "";
        ITag tag = profile.getTag();
        if (tag == null) return "";
        return firstNonBlank(tag.getPrefix(), tag.getDisplayName());
    }

    /** The team colour code inside a game, empty outside one. */
    public String teamColor(Player player) {
        return arenas.teamColor(player);
    }

    /** Is the player in a bedwars arena? Chat leaves those players alone. */
    public boolean inGame(Player player) {
        return arenas.inGame(player);
    }

    // -------------------------------------------------------------------------

    /**
     * The rank a player should be shown as having.
     *
     * A rank-disguised player is shown as their disguise, not as themselves —
     * the same reason the sort key uses the fake priority.
     */
    private IRank displayRank(Player player) {
        IProfile profile = profile(player);
        if (profile == null) return null;

        IDisguiseData disguise = profile.getDisguiseData();
        if (disguise != null && disguise.isRankDisguised()) {
            UUID rankId = disguise.getRankId();
            Phoenix phoenix = Phoenix.getInstance();
            if (rankId != null && phoenix != null && phoenix.getRankHandler() != null) {
                IRank disguised = phoenix.getRankHandler().getRank(rankId);
                if (disguised != null) return disguised;
            }
        }
        return profile.getHighestRank();
    }

    private static IProfile profile(Player player) {
        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) return null;
        if (phoenix.getProfileHandler() == null) return null;
        return phoenix.getProfileHandler().getProfile(player.getUniqueId());
    }

    private String orFallback(String value) {
        return value.isEmpty() ? config.getPrefixFallback() : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String pad(int value, int width) {
        StringBuilder sb = new StringBuilder(Integer.toString(value));
        while (sb.length() < width) sb.insert(0, '0');
        return sb.toString();
    }
}
