package net.gravijet.morefeatures.task;

import org.bukkit.scheduler.BukkitRunnable;
import net.gravijet.morefeatures.Main;
import xyz.refinedev.phoenix.Phoenix;

import java.util.logging.Level;

public class SyncTask extends BukkitRunnable {

    private final Main plugin;

    public SyncTask(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            doSync();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Network stats sync threw an exception", e);
        }
    }

    private void doSync() {
        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) return;

        plugin.syncNetworkStats(phoenix);
    }
}
