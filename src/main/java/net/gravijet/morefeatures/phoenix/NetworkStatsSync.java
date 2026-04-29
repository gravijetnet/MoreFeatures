package net.gravijet.morefeatures.phoenix;

import net.gravijet.morefeatures.database.DatabaseManager;
import xyz.refinedev.phoenix.Phoenix;

/**
 * Syncs network-level stats (online count, total players) from Phoenix to MySQL.
 */
public class NetworkStatsSync {

    private final DatabaseManager databaseManager;

    public NetworkStatsSync(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** Must be called from an async thread. */
    public void sync(Phoenix phoenix) {
        if (phoenix == null || !phoenix.isApiEnabled()) return;
        long currentOnline = phoenix.getNetworkHandler().getOnline();
        long totalPlayers = databaseManager.countPlayers();
        databaseManager.updateNetworkStats(currentOnline, totalPlayers);
    }
}
