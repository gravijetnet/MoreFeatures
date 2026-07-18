package net.gravijet.morefeatures.display;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
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
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    private BukkitTask task;

    public ArenaCache(ArenaLookup delegate) {
        this.delegate = delegate;
    }

    public void start(Plugin plugin) {
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                snapshots.put(player.getUniqueId(), new Snapshot(
                        delegate.teamIndex(player), delegate.teamColor(player)));
            }
            // Anyone no longer online stops being tracked. Iterating the map
            // rather than hooking quit keeps this correct across kicks, world
            // transfers and a /reload, none of which reliably fire a quit here.
            snapshots.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        }, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) task.cancel();
        snapshots.clear();
    }

    @Override
    public int teamIndex(Player player) {
        Snapshot snapshot = snapshots.get(player.getUniqueId());
        return snapshot != null ? snapshot.teamIndex : NONE;
    }

    @Override
    public String teamColor(Player player) {
        Snapshot snapshot = snapshots.get(player.getUniqueId());
        return snapshot != null ? snapshot.teamColor : "";
    }

    @Override
    public boolean inGame(Player player) {
        return teamIndex(player) != NONE;
    }

    private static final class Snapshot {
        final int    teamIndex;
        final String teamColor;

        Snapshot(int teamIndex, String teamColor) {
            this.teamIndex = teamIndex;
            this.teamColor = teamColor;
        }
    }
}
