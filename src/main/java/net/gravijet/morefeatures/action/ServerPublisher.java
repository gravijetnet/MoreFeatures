package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
            + "  `players`      MEDIUMTEXT,"   // who is on, one name per line
            + "  `tps`          DOUBLE      NOT NULL DEFAULT 0,"
            + "  `heap_used`    BIGINT      NOT NULL DEFAULT 0,"
            + "  `heap_max`     BIGINT      NOT NULL DEFAULT 0,"
            + "  `uptime_ms`    BIGINT      NOT NULL DEFAULT 0,"
            + "  `updated_at`   DATETIME    NOT NULL,"
            + "  PRIMARY KEY (`name`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    /** Existing installs predate these columns. */
    private static final String[] MIGRATE = {
            "ALTER TABLE `network_servers` ADD COLUMN IF NOT EXISTS `players` MEDIUMTEXT",
            "ALTER TABLE `network_servers` ADD COLUMN IF NOT EXISTS `tps` DOUBLE NOT NULL DEFAULT 0",
            "ALTER TABLE `network_servers` ADD COLUMN IF NOT EXISTS `heap_used` BIGINT NOT NULL DEFAULT 0",
            "ALTER TABLE `network_servers` ADD COLUMN IF NOT EXISTS `heap_max` BIGINT NOT NULL DEFAULT 0",
            "ALTER TABLE `network_servers` ADD COLUMN IF NOT EXISTS `uptime_ms` BIGINT NOT NULL DEFAULT 0",
    };

    private static final String UPSERT = ""
            + "INSERT INTO `network_servers`"
            + " (`name`, `server_group`, `online`, `max_players`, `whitelisted`, `players`,"
            + "  `tps`, `heap_used`, `heap_max`, `uptime_ms`, `updated_at`)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())"
            + " ON DUPLICATE KEY UPDATE `server_group` = VALUES(`server_group`),"
            + " `online` = VALUES(`online`), `max_players` = VALUES(`max_players`),"
            + " `whitelisted` = VALUES(`whitelisted`), `players` = VALUES(`players`),"
            + " `tps` = VALUES(`tps`), `heap_used` = VALUES(`heap_used`),"
            + " `heap_max` = VALUES(`heap_max`), `uptime_ms` = VALUES(`uptime_ms`),"
            + " `updated_at` = NOW()";

    private final Main plugin;
    private final DatabaseManager database;
    private final String node;
    private final long startedAt = System.currentTimeMillis();

    public ServerPublisher(Main plugin, DatabaseManager database, String node) {
        this.plugin = plugin;
        this.database = database;
        this.node = node;
    }

    public void createTables() throws SQLException {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(CREATE);
            for (String migration : MIGRATE) {
                try {
                    st.executeUpdate(migration);
                } catch (SQLException ignored) {
                    // Older MySQL lacks ADD COLUMN IF NOT EXISTS, or the column is
                    // already there. Status still publishes either way.
                }
            }
        }
    }

    // --- ticks ---------------------------------------------------------------
    //
    // TPS is the number that actually says whether a Minecraft server is well,
    // and 1.8's API does not expose it. Paper's Bukkit.getTPS() may or may not be
    // there depending on what each box runs, so rather than reflect at it we
    // measure: a task scheduled every second reports how long that second really
    // took. Twenty ticks that took 1000ms is 20 TPS; twenty ticks that took
    // 2000ms is 10, and the server is in trouble.
    private static final long TICKS_PER_SAMPLE = 20L;
    private volatile double tps = 20.0;
    private long lastSample = 0L;

    /** Called on the server thread, once a second. */
    public void sampleTps() {
        long now = System.nanoTime();
        if (lastSample != 0L) {
            double seconds = (now - lastSample) / 1_000_000_000.0;
            if (seconds > 0.0) {
                // Never report better than perfect: a scheduler that fires early
                // would otherwise show 21 TPS and make somebody doubt the number.
                tps = Math.min(20.0, TICKS_PER_SAMPLE / seconds);
            }
        }
        lastSample = now;
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

        // Who is on, by name. Vanished staff are still listed — this is a staff
        // console, and a moderator hunting somebody needs to know they are here.
        StringBuilder names = new StringBuilder();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (names.length() > 0) names.append('\n');
            names.append(p.getName());
        }
        final String playerList = names.toString();

        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix != null && phoenix.isApiEnabled()) {
            try { group = phoenix.getNetworkHandler().getServerGroup(); } catch (Exception ignored) { /* not fatal */ }
            try { whitelisted = phoenix.getWhitelistHandler().isWhitelisted(); } catch (Exception ignored) { /* not fatal */ }
        }

        // The JVM's own view of itself. This is the memory the server process is
        // actually using, which is a different and more useful number than the
        // ceiling the panel allocated it — a box given 6 GB and using 1.2 is
        // fine, and only this says so.
        Runtime rt = Runtime.getRuntime();
        final long heapUsed = rt.totalMemory() - rt.freeMemory();
        final long heapMax = rt.maxMemory();
        final long uptime = System.currentTimeMillis() - startedAt;
        final double nowTps = tps;

        final String g = group;
        final boolean w = whitelisted;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> write(online, max, g, w, playerList, nowTps, heapUsed, heapMax, uptime));
    }

    private void write(int online, int max, String group, boolean whitelisted, String players,
                       double tpsNow, long heapUsed, long heapMax, long uptimeMs) {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setString(1, node);
            ps.setString(2, group);
            ps.setInt(3, online);
            ps.setInt(4, max);
            ps.setBoolean(5, whitelisted);
            ps.setString(6, players);
            ps.setDouble(7, tpsNow);
            ps.setLong(8, heapUsed);
            ps.setLong(9, heapMax);
            ps.setLong(10, uptimeMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not publish server status: " + e.getMessage());
        }
    }
}
