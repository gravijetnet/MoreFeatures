package net.gravijet.morefeatures.listener;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import net.gravijet.morefeatures.Main;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.handler.ILoginHandler;
import xyz.refinedev.phoenix.handler.IProfileHandler;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.login.ILogin;
import xyz.refinedev.phoenix.rank.IRank;
import xyz.refinedev.phoenix.utils.events.misc.ProfileNetworkJoinEvent;
import xyz.refinedev.phoenix.utils.events.misc.ProfileNetworkLeaveEvent;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final Main plugin;

    public PlayerListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNetworkJoin(ProfileNetworkJoinEvent event) {
        if (plugin.getDatabaseManager() == null) return;

        UUID uuid = event.getUuid();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix == null || !phoenix.isApiEnabled()) return;

            IProfileHandler profileHandler = phoenix.getProfileHandler();
            ILoginHandler loginHandler = phoenix.getLoginHandler();

            IProfile profile = profileHandler.getProfile(uuid);
            if (profile == null) return;

            String profileName = profile.getName();
            if (profileName == null || profileName.isEmpty()) return;

            IRank highestRank = profile.getHighestRank();
            String rankDisplayName = (highestRank != null) ? highestRank.getDisplayName() : null;
            String rank = (rankDisplayName != null) ? ChatColor.stripColor(rankDisplayName) : null;

            Timestamp firstSeen = firstSeenFromPhoenix(loginHandler, uuid);

            plugin.getDatabaseManager().upsertPlayer(
                    uuid.toString(), profileName, rank, true, firstSeen);

            plugin.syncNetworkStats(phoenix);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.startPlaytimeTimer(event.getPlayer().getUniqueId());
        // Network stats sync on join is already handled by onNetworkJoin above
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // BUG-27 fix: cancel the periodic timer first so it cannot fire a concurrent write
        // between the cancel and the final one-shot sync below.
        plugin.cancelPlaytimeTimer(uuid);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.getPlaytimeSync() != null) plugin.getPlaytimeSync().syncPlaytime(uuid);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNetworkLeave(ProfileNetworkLeaveEvent event) {
        if (plugin.getDatabaseManager() == null) return;

        UUID uuid = event.getUuid();

        // BUG-28 fix: increased delay from 5 to 40 ticks (2 s) to survive heavy-load
        // ticks. The task is registered with the plugin so onDisable()'s cancelTasks()
        // will kill it before the DB pool is closed, preventing a write-after-close.
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (plugin.getDatabaseManager() == null) return;
            plugin.getDatabaseManager().setPlayerOnline(uuid.toString(), false);
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix != null && phoenix.isApiEnabled()) plugin.syncNetworkStats(phoenix);
        }, 40L);
    }

    // -------------------------------------------------------------------------

    private static Timestamp firstSeenFromPhoenix(ILoginHandler loginHandler, UUID uuid) {
        try {
            List<ILogin> logins = loginHandler.getDatabaseLoginsSync(uuid);
            if (logins != null && !logins.isEmpty()) {
                long min = Long.MAX_VALUE;
                for (ILogin login : logins) {
                    if (login.getLogin() < min) min = login.getLogin();
                }
                return new Timestamp(min);
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("MoreFeatures")
                    .log(java.util.logging.Level.WARNING, "Could not fetch logins for " + uuid + " — using current time as first_seen", e);
        }
        return new Timestamp(System.currentTimeMillis());
    }
}
