package net.gravijet.morefeatures.display;

import org.bukkit.entity.Player;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.disguise.IDisguiseData;
import xyz.refinedev.phoenix.profile.tag.ITag;
import xyz.refinedev.phoenix.rank.IRank;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Everything below is answered out of here.
     *
     * TAB asks for nine placeholders per player and refreshes them for every
     * viewer, so the old shape — each getter resolving the Phoenix profile for
     * itself — meant six profile lookups and a disguise resolution per player
     * per refresh, on TAB's thread, all producing the same answer. One resolve
     * per player per {@link DisplayConfig#getCacheTtlMs()} produces the same
     * tablist; at the default 200ms nobody can see the difference, and a rank
     * change still lands within a fifth of a second.
     */
    private final Map<UUID, Resolved> cache = new ConcurrentHashMap<>();

    public DisplayResolver(ArenaLookup arenas, DisplayConfig config) {
        this.arenas = arenas;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    //  The snapshot
    // -------------------------------------------------------------------------

    /** One player, fully resolved, valid until {@link #expiresAt}. */
    private static final class Resolved {
        final long   expiresAt;
        final String sortWeight;
        final String sortKey;
        final String prefix;
        final String tabPrefix;
        final String suffix;
        final String nameColor;
        final String tag;
        final String teamColor;
        final boolean inGame;

        Resolved(long expiresAt, String sortWeight, String sortKey, String prefix, String tabPrefix,
                 String suffix, String nameColor, String tag, String teamColor, boolean inGame) {
            this.expiresAt  = expiresAt;
            this.sortWeight = sortWeight;
            this.sortKey    = sortKey;
            this.prefix     = prefix;
            this.tabPrefix  = tabPrefix;
            this.suffix     = suffix;
            this.nameColor  = nameColor;
            this.tag        = tag;
            this.teamColor  = teamColor;
            this.inGame     = inGame;
        }
    }

    private Resolved resolved(Player player) {
        UUID id = player.getUniqueId();
        Resolved cached = cache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expiresAt) return cached;

        // Two threads racing here both compute the same answer and one wins the
        // put. That is a wasted resolve, not a wrong one, and it is cheaper than
        // holding a lock across a Phoenix lookup on TAB's thread.
        Resolved fresh = resolve(player, now + config.getCacheTtlMs());
        cache.put(id, fresh);
        return fresh;
    }

    /** The single pass. One profile lookup, one arena lookup, one rank resolution. */
    private Resolved resolve(Player player, long expiresAt) {
        IProfile profile = profile(player);
        ArenaLookup.TeamState team = arenas.teamState(player);
        IRank rank = displayRank(profile);

        int score = rankScore(profile);
        String sortWeight = Integer.toString(buildSortWeight(team.index(), score));
        String sortKey    = buildSortKey(team.index(), MAX_PRIORITY - score, player.getName());

        String prefix = rank == null
                ? config.getPrefixFallback()
                : orFallback(firstNonBlank(rank.getPrefix(), rank.getPrefixLegacy()));

        String tabPrefix = rank == null
                ? config.getPrefixFallback()
                : orFallback(firstNonBlank(
                        rank.getPlayerListPrefix(), rank.getPlayerListPrefixLegacy(),
                        rank.getPrefix(), rank.getPrefixLegacy()));

        String suffix = rank == null
                ? ""
                : firstNonBlank(rank.getSuffix(), rank.getSuffixLegacy());

        // In a game the name is drawn in the team colour — a bedwars tablist
        // coloured by rank instead of by team is unreadable mid-fight.
        String nameColor = !team.color().isEmpty()
                ? team.color()
                : (rank == null ? config.getPrefixFallback()
                                : orFallback(firstNonBlank(rank.getColor(), rank.getColorLegacy())));

        String tag = "";
        if (profile != null) {
            ITag chosen = profile.getTag();
            if (chosen != null) tag = firstNonBlank(chosen.getPrefix(), chosen.getDisplayName());
        }

        return new Resolved(expiresAt, sortWeight, sortKey, prefix, tabPrefix, suffix,
                nameColor, tag, team.color(), team.index() != ArenaLookup.NONE);
    }

    /** Drops everyone who is no longer online, so the map cannot grow unbounded. */
    public void sweep(Collection<UUID> online) {
        if (cache.size() > online.size()) cache.keySet().retainAll(online);
    }

    /** Forget one player immediately — used when their rank changes under them. */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
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
        return resolved(player).sortWeight;
    }

    /** The weight arithmetic, split out so it can be exercised without a server. */
    static int buildSortWeight(int teamIndex, int rankScore) {
        int band = ArenaLookup.NONE - teamIndex;
        return band * TEAM_BAND + rankScore;
    }

    public String sortKey(Player player) {
        return resolved(player).sortKey;
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
    private int rankScore(IProfile profile) {
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
        return resolved(player).prefix;
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
        return resolved(player).tabPrefix;
    }

    public String suffix(Player player) {
        return resolved(player).suffix;
    }

    /**
     * The colour the player's own name is drawn in.
     *
     * In a game this is the team colour — a bedwars tablist that colours names
     * by rank instead of by team is unreadable mid-fight. Outside one it is the
     * rank colour.
     */
    public String nameColor(Player player) {
        return resolved(player).nameColor;
    }

    /** The player's chosen Phoenix tag, or empty if they have none. */
    public String tag(Player player) {
        return resolved(player).tag;
    }

    /** The team colour code inside a game, empty outside one. */
    public String teamColor(Player player) {
        return resolved(player).teamColor;
    }

    /** Is the player in a bedwars arena? Chat leaves those players alone. */
    public boolean inGame(Player player) {
        return resolved(player).inGame;
    }

    // -------------------------------------------------------------------------

    /**
     * The rank a player should be shown as having.
     *
     * A rank-disguised player is shown as their disguise, not as themselves —
     * the same reason the sort key uses the fake priority.
     */
    private IRank displayRank(IProfile profile) {
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
        String digits = Integer.toString(value);
        if (digits.length() >= width) return digits;
        // Appending the zeros rather than insert(0, ..)-ing them: insert shifts
        // the whole buffer every time round, and this runs for every player.
        StringBuilder sb = new StringBuilder(width);
        for (int i = digits.length(); i < width; i++) sb.append('0');
        return sb.append(digits).toString();
    }
}
