package net.gravijet.morefeatures.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.gravijet.morefeatures.Main;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.profile.punishment.PunishmentType;
import xyz.refinedev.phoenix.utils.events.punishment.ProfilePunishmentEvent;

public class PunishmentListener implements Listener {

    private final Main plugin;

    public PunishmentListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPunishment(ProfilePunishmentEvent event) {
        if (plugin.getDatabaseManager() == null) return; // BUG-14: sync may be disabled
        PunishmentType type = event.getPunishment().getPunishmentType();

        String statKey;
        switch (type) {
            case BAN:  statKey = "total_bans";  break;
            case MUTE: statKey = "total_mutes"; break;
            case KICK: statKey = "total_kicks"; break;
            default:   return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().incrementStat(statKey, 1L);

            // Update network stats immediately — bans and kicks remove the player from the count
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix != null && phoenix.isApiEnabled()) {
                plugin.syncNetworkStats(phoenix);
            }
        });
    }
}
