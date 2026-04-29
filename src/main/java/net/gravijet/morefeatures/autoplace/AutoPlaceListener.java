package net.gravijet.morefeatures.autoplace;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class AutoPlaceListener implements Listener {

    private final AutoPlaceInjector injector;

    public AutoPlaceListener(AutoPlaceInjector injector) {
        this.injector = injector;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        injector.inject(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        injector.uninject(event.getPlayer());
    }
}
