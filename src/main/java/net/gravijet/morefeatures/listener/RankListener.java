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

        // The tablist resolves a player once and reuses that answer for a short
        // window. A grant is the one moment where waiting it out would be
        // visible, so the player is dropped from the cache here and the next
        // placeholder request resolves them fresh.
        if (plugin.getDisplayResolver() != null) {
            plugin.getDisplayResolver().invalidate(profile.getUniqueId());
        }

        if (plugin.getDatabaseManager() == null) return;
        String uuid = profile.getUniqueId().toString();
        IRank rank = profile.getHighestRank();
        String rankDisplayName = (rank != null) ? rank.getDisplayName() : null;
        String rankName = (rankDisplayName != null) ? ChatColor.stripColor(rankDisplayName) : null;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.getDatabaseManager() == null) return;
            plugin.getDatabaseManager().updatePlayerRank(uuid, rankName);
        });
    }
}
