package net.gravijet.morefeatures.listener;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.gravijet.morefeatures.Main;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.rank.IRank;
import xyz.refinedev.phoenix.utils.events.grant.ProfileGrantEvent;
import xyz.refinedev.phoenix.utils.events.grant.ProfileGrantRevokeEvent;

public class RankListener implements Listener {

    private final Main plugin;

    public RankListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrantAdded(ProfileGrantEvent event) {
        updateRank(event.getProfile());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrantRevoked(ProfileGrantRevokeEvent event) {
        updateRank(event.getProfile());
    }

    private void updateRank(IProfile profile) {
        if (profile == null) return;
        IRank rank = profile.getHighestRank();
        if (rank == null) return;
        String uuid = profile.getUniqueId().toString();
        String rankName = ChatColor.stripColor(rank.getDisplayName());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getDatabaseManager().updatePlayerRank(uuid, rankName));
    }
}
