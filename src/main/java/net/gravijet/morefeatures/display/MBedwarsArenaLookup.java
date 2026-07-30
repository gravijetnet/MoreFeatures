package net.gravijet.morefeatures.display;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import org.bukkit.entity.Player;

/**
 * The MBedwars-backed {@link ArenaLookup}.
 *
 * Only loaded when the MBedwars plugin is present — see
 * {@link ArenaLookup#create(java.util.logging.Logger)}.
 */
final class MBedwarsArenaLookup implements ArenaLookup {

    @Override
    public int teamIndex(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            // Spectators are not returned by getArenaByPlayer, so they need
            // their own lookup. They sort below every team rather than
            // scattering themselves through the teams they are watching.
            return BedwarsAPI.getGameAPI().getArenaBySpectator(player) != null ? SPECTATOR : NONE;
        }
        Team team = arena.getPlayerTeam(player);
        // In the waiting lobby a player is in the arena but has no team yet.
        return team != null ? team.ordinal() : SPECTATOR;
    }

    @Override
    public String teamColor(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return "";
        Team team = arena.getPlayerTeam(player);
        return team != null ? team.getBungeeChatColor().toString() : "";
    }

    @Override
    public boolean inGame(Player player) {
        return BedwarsAPI.getGameAPI().getArenaByPlayer(player) != null
                || BedwarsAPI.getGameAPI().getArenaBySpectator(player) != null;
    }

    /**
     * Both answers off one arena lookup.
     *
     * The two methods above each walk MBedwars' arenas to find the same player,
     * so asking for slot and colour separately costs twice what it needs to —
     * and {@link ArenaCache} asks for both, for everyone, twice a second.
     */
    @Override
    public TeamState teamState(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            return BedwarsAPI.getGameAPI().getArenaBySpectator(player) != null
                    ? new TeamState(SPECTATOR, "")
                    : TeamState.OUTSIDE;
        }
        Team team = arena.getPlayerTeam(player);
        if (team == null) return new TeamState(SPECTATOR, "");
        return new TeamState(team.ordinal(), team.getBungeeChatColor().toString());
    }
}
