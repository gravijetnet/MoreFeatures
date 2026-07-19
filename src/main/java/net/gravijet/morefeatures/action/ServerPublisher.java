package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.Bukkit;
import xyz.refinedev.phoenix.Phoenix;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * What the network looks like right now, written down where the website can see it.
 *
 * Phoenix knows every server and who is on each — but only from inside the game.
 * None of it lands in a database the website can read (px-serverHops is the only
 * server-shaped collection and it is empty), so Spielplatz had no way to show the
 * network at all.
 *
 * Each server publishes its own row and only its own. That means no two servers
 * ever race for the same row, no server has to be nominated as the one that
 * reports, and a row that stops being touched is simply a server that stopped —
 * which the website can say plainly rather than having to guess from a silence.
 */
public class ServerPublisher {

    private static final String CREATE = ""
            + "CREATE TABLE IF NOT EXISTS `network_servers` ("
            + "  `name`         VARCHAR(64) NOT NULL,"
            + "  `server_group` VARCHAR(64),"
            + "  `online`       INT         NOT NULL DEFAULT 0,"
            + "  `max_players`  INT         NOT NULL DEFAULT 0,"
            + "  `whitelisted`  BOOLEAN     NOT NULL DEFAULT 0,"
            + "  `updated_at`   DATETIME    NOT NULL,"
            + "  PRIMARY KEY (`name`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    private static final String UPSERT = ""
            + "INSERT INTO `network_servers`"
            + " (`name`, `server_group`, `online`, `max_players`, `whitelisted`, `updated_at`)"
            + " VALUES (?, ?, ?, ?, ?, NOW())"
            + " ON DUPLICATE KEY UPDATE `server_group` = VALUES(`server_group`),"
            + " `online` = VALUES(`online`), `max_players` = VALUES(`max_players`),"
            + " `whitelisted` = VALUES(`whitelisted`), `updated_at` = NOW()";

    private final Main plugin;
    private final DatabaseManager database;
    private final String node;

    public ServerPublisher(Main plugin, DatabaseManager database, String node) {
        this.plugin = plugin;
        this.database = database;
        this.node = node;
    }

    public void createTables() throws SQLException {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(CREATE);
        }
    }

    /**
     * Called on the server thread — the player list and the core's handlers are
     * read here, and only the write is handed off, because reading Bukkit's world
     * from a pool thread is how you get an intermittent crash nobody can reproduce.
     */
    public void tick() {
        if (database == null) return;

        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        String group = null;
        boolean whitelisted = false;

        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix != null && phoenix.isApiEnabled()) {
            try { group = phoenix.getNetworkHandler().getServerGroup(); } catch (Exception ignored) { /* not fatal */ }
            try { whitelisted = phoenix.getWhitelistHandler().isWhitelisted(); } catch (Exception ignored) { /* not fatal */ }
        }

        final String g = group;
        final boolean w = whitelisted;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(online, max, g, w));
    }

    private void write(int online, int max, String group, boolean whitelisted) {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setString(1, node);
            ps.setString(2, group);
            ps.setInt(3, online);
            ps.setInt(4, max);
            ps.setBoolean(5, whitelisted);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not publish server status: " + e.getMessage());
        }
    }
}
