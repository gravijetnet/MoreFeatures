package net.gravijet.morefeatures.display;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A main-thread snapshot of where everyone is, read from anywhere.
 *
 * Both callers of {@link ArenaLookup} are off the main thread: chat arrives on
 * {@code AsyncPlayerChatEvent}, and TAB refreshes its placeholders
 * asynchronously. MBedwars' arena lookups walk live collections that the main
 * thread mutates, so calling them from those threads risks a
 * ConcurrentModificationException in the middle of someone's chat message.
 *
 * So the lookups happen on the main thread on a timer and everyone else reads
 * the result. A snapshot can be up to one refresh interval stale, which for a
 * player who just joined a game means their tablist position settles half a
 * second late — invisible, and a fair trade for never touching MBedwars from
 * the wrong thread.
 */
public class ArenaCache implements ArenaLookup {

    /** Half a second. Fast enough to be unnoticeable, slow enough to be free. */
    private static final long REFRESH_TICKS = 10L;

    private final ArenaLookup delegate;
    private final Map<UUID, TeamState> snapshots = new ConcurrentHashMap<>();

    private BukkitTask task;

    public ArenaCache(ArenaLookup delegate) {
        this.delegate = delegate;
    }

    public void start(Plugin plugin) {
        // Without MBedwars every answer is "not in a game" and the map stays
        // empty, which the getters already read as exactly that. Running the
        // timer anyway would walk every player twice a second to learn nothing.
        if (delegate == ArenaLookup.ABSENT) return;

        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int online = 0;
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                online++;
                snapshots.put(player.getUniqueId(), delegate.teamState(player));
            }
            // Anyone no longer online stops being tracked. Iterating the map
            // rather than hooking quit keeps this correct across kicks, world
            // transfers and a /reload, none of which reliably fire a quit here.
            //
            // Only when the map has grown past the player count, though: the old
            // unconditional removeIf called Bukkit.getPlayer(UUID) per entry, and
            // that walks the player list — quadratic, twice a second, forever, to
            // remove nothing on all but the one tick after somebody leaves.
            if (snapshots.size() > online) {
                Set<UUID> present = new HashSet<>(online * 2);
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    present.add(player.getUniqueId());
                }
                snapshots.keySet().retainAll(present);
            }
        }, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) task.cancel();
        snapshots.clear();
    }

    @Override
    public int teamIndex(Player player) {
        return teamState(player).index();
    }

    @Override
    public String teamColor(Player player) {
        return teamState(player).color();
    }

    @Override
    public boolean inGame(Player player) {
        return teamState(player).index() != NONE;
    }

    /** The snapshot itself — a map read, so a caller needing all three pays for one. */
    @Override
    public TeamState teamState(Player player) {
        TeamState snapshot = snapshots.get(player.getUniqueId());
        return snapshot != null ? snapshot : TeamState.OUTSIDE;
    }
}
