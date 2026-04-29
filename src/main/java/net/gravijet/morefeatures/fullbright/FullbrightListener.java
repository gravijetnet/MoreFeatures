package net.gravijet.morefeatures.fullbright;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
}
