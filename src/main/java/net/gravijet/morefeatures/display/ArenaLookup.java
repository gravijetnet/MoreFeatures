package net.gravijet.morefeatures.display;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where a player stands in a bedwars game, if they stand in one at all.
 *
 * Lobby and bedwars are worlds on the same server, so "which mode is this
 * server in" is the wrong question — the answer is per player, and it is
 * whatever MBedwars says about them right now.
 *
 * MBedwars is optional. Every reference to its classes lives in the
 * implementation below, which is only ever constructed once the plugin has been
 * found, so a server without it never triggers the class load.
 */
public interface ArenaLookup {

    /** Team slot for a player who is spectating rather than playing. */
    int SPECTATOR = 98;

    /** Team slot for a player who is not in an arena at all. */
    int NONE = 99;

    /**
     * Which team the player belongs to, as a stable index.
     *
     * @return 0-15 for a team, {@link #SPECTATOR}, or {@link #NONE}
     */
    int teamIndex(Player player);

    /** The team's colour code, or an empty string outside a game. */
    String teamColor(Player player);

    /** Is the player in a game right now? Drives lobby-vs-game behaviour. */
    boolean inGame(Player player);

    /**
     * Slot and colour together, so a caller that needs both asks MBedwars once.
     *
     * {@link ArenaCache} refreshes every player on a timer, and separate
     * teamIndex/teamColor calls made that two arena lookups per player per
     * refresh for an answer that comes out of the same arena either way.
     */
    default TeamState teamState(Player player) {
        return new TeamState(teamIndex(player), teamColor(player));
    }

    /** One player's place in a game, as a value that can be handed between threads. */
    final class TeamState {

        /** Not in an arena. Shared rather than allocated — it is the common answer. */
        public static final TeamState OUTSIDE = new TeamState(NONE, "");

        private final int index;
        private final String color;

        public TeamState(int index, String color) {
            this.index = index;
            this.color = color == null ? "" : color;
        }

        public int index() {
            return index;
        }

        public String color() {
            return color;
        }
    }

    // -------------------------------------------------------------------------

    ArenaLookup ABSENT = new ArenaLookup() {
        @Override public int teamIndex(Player player)    { return NONE; }
        @Override public String teamColor(Player player) { return ""; }
        @Override public boolean inGame(Player player)   { return false; }
        @Override public TeamState teamState(Player p)   { return TeamState.OUTSIDE; }
    };

    static ArenaLookup create(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("MBedwars") == null) {
            logger.info("MBedwars not installed — tablist sorting falls back to rank priority only.");
            return ABSENT;
        }
        try {
            return new MBedwarsArenaLookup();
        } catch (Throwable t) {
            // Catching Throwable on purpose: a version mismatch surfaces as
            // NoClassDefFoundError or NoSuchMethodError, not an Exception, and
            // losing team sorting is not a reason to lose the whole feature.
            logger.log(Level.WARNING, "MBedwars is installed but its API did not load — "
                    + "tablist sorting falls back to rank priority only.", t);
            return ABSENT;
        }
    }
}
