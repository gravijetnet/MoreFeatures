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

    // Fires when a player first joins the whole network (not on server switches)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNetworkJoin(ProfileNetworkJoinEvent event) {
        UUID uuid = event.getUuid();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix == null || !phoenix.isApiEnabled()) return;

            IProfileHandler profileHandler = phoenix.getProfileHandler();
            ILoginHandler loginHandler = phoenix.getLoginHandler();

            IProfile profile = profileHandler.getProfile(uuid);
            if (profile == null) return;

            IRank highestRank = profile.getHighestRank();
            String rank = highestRank != null
                    ? ChatColor.stripColor(highestRank.getDisplayName())
                    : null;

            Timestamp firstSeen = firstSeenFromPhoenix(loginHandler, uuid);

            plugin.getDatabaseManager().upsertPlayer(
                    uuid.toString(), profile.getName(), rank, true, firstSeen);

            plugin.syncNetworkStats(phoenix);
        });
    }

    // Fires on every server join — starts (or restarts) the per-player 5-minute playtime timer
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.startPlaytimeTimer(event.getPlayer().getUniqueId());
    }

    // Fires on every server quit — stops the per-player playtime timer
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.cancelPlaytimeTimer(event.getPlayer().getUniqueId());
    }

    // Fires when a player disconnects from the whole network (not on server switches)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNetworkLeave(ProfileNetworkLeaveEvent event) {
        UUID uuid = event.getUuid();

        // Short delay so Phoenix can finalise the logout record before we read it
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix == null || !phoenix.isApiEnabled()) {
                plugin.getDatabaseManager().setPlayerOnline(uuid.toString(), false);
                return;
            }

            plugin.getDatabaseManager().setPlayerOnline(uuid.toString(), false);
            plugin.syncNetworkStats(phoenix);
        }, 5L);
    }

    // -------------------------------------------------------------------------

    // Returns the earliest login timestamp from the full Phoenix login history
    private static Timestamp firstSeenFromPhoenix(ILoginHandler loginHandler, UUID uuid) {
        try {
            List<ILogin> allLogins = loginHandler.getDatabaseLoginsSync(uuid);
            if (allLogins != null && !allLogins.isEmpty()) {
                long min = Long.MAX_VALUE;
                for (ILogin login : allLogins) {
                    if (login.getLogin() < min) min = login.getLogin();
                }
                return new Timestamp(min);
            }
        } catch (Exception e) {
            // fall through to current time
        }
        return new Timestamp(System.currentTimeMillis());
    }
}
