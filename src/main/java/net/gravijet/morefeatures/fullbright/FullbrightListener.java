package net.gravijet.morefeatures.fullbright;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

public class FullbrightListener implements Listener {

    private final FullbrightManager fullbrightManager;

    public FullbrightListener(FullbrightManager fullbrightManager) {
        this.fullbrightManager = fullbrightManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        fullbrightManager.relightChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        fullbrightManager.restorePersonal(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Bukkit writes playerdata after this event, so dropping the effect here is
        // what stops a grant from being saved to disk and surviving a restart.
        fullbrightManager.suspendPersonal(event.getPlayer());
    }
}
