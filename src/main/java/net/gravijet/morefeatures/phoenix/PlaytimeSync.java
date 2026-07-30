package net.gravijet.morefeatures.phoenix;

import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.handler.ILoginHandler;
import xyz.refinedev.phoenix.handler.IProfileHandler;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.login.ILogin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles playtime synchronisation from PhoenixAPI to the bridge database.
 *
 * Root cause of the "playtime 0" bug: getCachedLogins() only returns logins
 * that are already in Phoenix's in-memory cache. On a fresh join (or after a
 * reload) that cache is empty, so getPlayTime() sees an empty list and returns 0.
 * getLogins() is the correct call — it checks the cache first and falls back to
 * the database when the cache is cold.
 */
public class PlaytimeSync {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final Logger logger;

    public PlaytimeSync(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logger = plugin.getLogger();
    }

    /**
     * Reads the current playtime for {@code uuid} from Phoenix and writes it to
     * the database.  Must be called from an async thread.
     *
     * This is the single-player path — a player leaving, whose last few minutes
     * would otherwise be lost. It is allowed the blocking database fallback
     * because it happens once and matters.
     */
    public void syncPlaytime(UUID uuid) {
        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) return;

        Long seconds = playtimeSeconds(phoenix, uuid, true);
        if (seconds == null) return;
        databaseManager.updatePlayerPlaytime(uuid.toString(), seconds);
    }

    /**
     * Everyone currently on, in one batch. Must be called from an async thread.
     *
     * The old shape was one repeating scheduler task per player, each firing a
     * single-row UPDATE on its own connection. That is a task per player to keep
     * alive, a borrow per player, and a round trip per player, for a write that
     * batches perfectly — so it batches now, which is what makes it affordable to
     * run this several times more often than it used to.
     */
    public void syncAll(Collection<UUID> uuids) {
        if (uuids.isEmpty()) return;
        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) return;

        Map<String, Long> batch = new LinkedHashMap<>();
        for (UUID uuid : uuids) {
            // No database fallback in bulk: for a player who is online the logins
            // are in the core's cache, and a cold one is not worth turning a
            // batch into a hundred blocking reads against Phoenix's own database.
            // They are picked up on the next run, or on their way out.
            Long seconds = playtimeSeconds(phoenix, uuid, false);
            if (seconds != null) batch.put(uuid.toString(), seconds);
        }
        databaseManager.updatePlaytimes(batch);
    }

    /** @return the player's playtime in whole seconds, or null if it cannot be told. */
    private Long playtimeSeconds(Phoenix phoenix, UUID uuid, boolean allowDatabaseFallback) {
        IProfileHandler profileHandler = phoenix.getProfileHandler();
        ILoginHandler loginHandler = phoenix.getLoginHandler();

        IProfile profile = profileHandler.getProfile(uuid);
        if (profile == null) return null;

        // getLogins() = cache-first with DB fallback; avoids the empty-cache / 0-playtime bug
        List<ILogin> logins = loginHandler.getLogins(uuid);
        if (logins == null || logins.isEmpty()) {
            if (!allowDatabaseFallback) return null;
            // One retry via the database directly to guard against a race on first join
            try {
                logins = loginHandler.getDatabaseLoginsSync(uuid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not fetch logins from DB for " + uuid, e);
                return null;
            }
        }

        if (logins == null || logins.isEmpty()) return null;

        long playtimeMs = profile.getPlayTime(logins);
        if (playtimeMs < 0) return null;

        // BUG-29 fix: round rather than truncate so accumulated sub-second remainder
        // does not silently under-count playtime across many syncs.
        return (playtimeMs + 500L) / 1000L;
    }
}
