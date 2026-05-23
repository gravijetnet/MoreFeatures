package net.gravijet.morefeatures.phoenix;

import net.gravijet.morefeatures.database.DatabaseManager;
import xyz.refinedev.phoenix.Phoenix;

/**
 * Syncs network-level stats (online count, total players) from Phoenix to MySQL.
 */
public class NetworkStatsSync {

    // Re-run COUNT(*) at most once every 30 seconds to avoid a full index scan on every event.
    private static final long COUNT_CACHE_TTL_MS = 30_000L;

    private final DatabaseManager databaseManager;

    // BUG-22 fix: plain longs guarded by synchronized; the volatile keyword alone cannot
    // make a check-then-act sequence atomic, so two concurrent sync() calls could both
    // see a stale lastCountTime, both call countPlayers(), and double the DB load.
    private long cachedTotalPlayers = 0L;
    private long lastCountTime      = 0L;

    public NetworkStatsSync(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** Must be called from an async thread. */
    public void sync(Phoenix phoenix) {
        if (phoenix == null || !phoenix.isApiEnabled()) return;
        long currentOnline = phoenix.getNetworkHandler().getOnline();
        long totalPlayers  = getCachedTotalPlayers();
        databaseManager.updateNetworkStats(currentOnline, totalPlayers);
    }

    private synchronized long getCachedTotalPlayers() {
        long now = System.currentTimeMillis();
        if (now - lastCountTime >= COUNT_CACHE_TTL_MS) {
            cachedTotalPlayers = databaseManager.countPlayers();
            lastCountTime      = now;
        }
        return cachedTotalPlayers;
    }
}
