package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Announcements the website sends to the game.
 *
 * Unlike {@link ActionQueue}, this is not a work queue where one server claims
 * each job — a broadcast has to appear on <i>every</i> server, so every server
 * reads every row. It stays exactly-once per server with a cursor: on startup the
 * cursor is set to the newest row that already exists, so a restart never replays
 * old announcements, and from then on each server shows every row past its cursor
 * once and moves the cursor past it — whether the send worked or not, because a
 * missed announcement is better than one that repeats every second forever.
 *
 * Both kinds broadcast locally on each server rather than through the core's
 * network-wide staff channel, precisely because every server runs this: a
 * network-wide send from all of them would print N copies. Local sends, one per
 * server, add up to the whole network seeing it once.
 */
public class Broadcaster {

    private static final int BATCH = 25;
    private static final String STAFF_PERMISSION = "core.staff";

    private static final String CREATE = ""
            + "CREATE TABLE IF NOT EXISTS `network_broadcasts` ("
            + "  `id`          BIGINT       NOT NULL AUTO_INCREMENT,"
            + "  `kind`        VARCHAR(16)  NOT NULL,"   // all | staff
            + "  `message`     VARCHAR(512) NOT NULL,"
            + "  `actor_label` VARCHAR(100),"
            + "  `created_at`  DATETIME     NOT NULL,"
            + "  PRIMARY KEY (`id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    private final Main plugin;
    private final DatabaseManager database;
    private volatile long cursor = 0L;

    public Broadcaster(Main plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void createTables() throws SQLException {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(CREATE);
        }
    }

    /** Skip everything that already exists — a fresh server has nothing to announce. */
    public void initCursor() {
        try (Connection conn = database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(`id`), 0) FROM `network_broadcasts`")) {
            if (rs.next()) cursor = rs.getLong(1);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the broadcast cursor: " + e.getMessage());
        }
    }

    private static final class Cast {
        long id;
        String kind;
        String message;
    }

    public void poll() {
        if (database == null) return;

        List<Cast> fresh = new ArrayList<>();
        long highest = cursor;
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT `id`, `kind`, `message` FROM `network_broadcasts` WHERE `id` > ? ORDER BY `id` LIMIT " + BATCH)) {
            ps.setLong(1, cursor);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Cast c = new Cast();
                    c.id = rs.getLong("id");
                    c.kind = rs.getString("kind");
                    c.message = rs.getString("message");
                    fresh.add(c);
                    highest = Math.max(highest, c.id);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read broadcasts: " + e.getMessage());
            return;
        }

        if (fresh.isEmpty()) return;
        // Advance the cursor before sending: a send that throws must not make the
        // same line repeat on the next poll.
        cursor = highest;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Cast c : fresh) send(c);
        });
    }

    private void send(Cast c) {
        try {
            String text = ChatColor.translateAlternateColorCodes('&', c.message == null ? "" : c.message);
            if ("staff".equalsIgnoreCase(c.kind)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission(STAFF_PERMISSION)) p.sendMessage(text);
                }
            } else {
                Bukkit.broadcastMessage(text);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Broadcast #" + c.id + " failed to send: " + e.getMessage());
        }
    }
}
