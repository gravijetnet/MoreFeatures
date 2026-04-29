package net.gravijet.morefeatures.phoenix;

import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.handler.ILoginHandler;
import xyz.refinedev.phoenix.handler.IProfileHandler;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.login.ILogin;

import java.util.List;
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
     */
    public void syncPlaytime(UUID uuid) {
        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) return;

        IProfileHandler profileHandler = phoenix.getProfileHandler();
        ILoginHandler loginHandler = phoenix.getLoginHandler();

        IProfile profile = profileHandler.getProfile(uuid);
        if (profile == null) return;

        // getLogins() = cache-first with DB fallback; avoids the empty-cache / 0-playtime bug
        List<ILogin> logins = loginHandler.getLogins(uuid);
        if (logins == null || logins.isEmpty()) {
            // One retry via the database directly to guard against a race on first join
            try {
                logins = loginHandler.getDatabaseLoginsSync(uuid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not fetch logins from DB for " + uuid, e);
                return;
            }
        }

        if (logins == null || logins.isEmpty()) return;

        long playtimeMs = profile.getPlayTime(logins);
        if (playtimeMs <= 0) return; // still 0 → login record not finalised yet, skip

        int playtimeSeconds = (int) (playtimeMs / 1000L);
        databaseManager.updatePlayerPlaytime(uuid.toString(), playtimeSeconds);
    }
}
