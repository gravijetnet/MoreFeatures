package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    /** The proxy's own channel. Registered in Main, or a Connect goes nowhere. */
    public static final String BUNGEE_CHANNEL = "BungeeCord";

    private static final String CREATE = ""
            + "CREATE TABLE IF NOT EXISTS `network_broadcasts` ("
            + "  `id`          BIGINT       NOT NULL AUTO_INCREMENT,"
            + "  `kind`        VARCHAR(16)  NOT NULL,"   // all | staff | player | send
            + "  `message`     VARCHAR(512) NOT NULL,"
            + "  `target_uuid` VARCHAR(36),"             // player/send only: who it is for
            + "  `actor_label` VARCHAR(100),"
            + "  `created_at`  DATETIME     NOT NULL,"
            + "  PRIMARY KEY (`id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    // Installs that predate the player-targeted kinds have the table without this
    // column. Adding it here keeps the plugin the single owner of the schema.
    private static final String MIGRATE =
            "ALTER TABLE `network_broadcasts` ADD COLUMN IF NOT EXISTS `target_uuid` VARCHAR(36)";

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
            try {
                st.executeUpdate(MIGRATE);
            } catch (SQLException ignored) {
                // Older MySQL has no ADD COLUMN IF NOT EXISTS; on those the column
                // either already exists or the server predates the feature. Either
                // way this must not stop announcements working.
            }
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
        String target;
    }

    private static final String SELECT_FRESH =
            "SELECT `id`, `kind`, `message`, `target_uuid` FROM `network_broadcasts`"
            + " WHERE `id` > ? ORDER BY `id` LIMIT " + BATCH;

    /** Off the main thread, on a connection shared with the action queues. */
    public void poll(Connection conn) throws SQLException {
        if (database == null) return;

        List<Cast> fresh = null;
        long highest = cursor;
        try (PreparedStatement ps = conn.prepareStatement(SELECT_FRESH)) {
            ps.setLong(1, cursor);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Cast c = new Cast();
                    c.id = rs.getLong("id");
                    c.kind = rs.getString("kind");
                    c.message = rs.getString("message");
                    c.target = rs.getString("target_uuid");
                    // Allocated only when there is something to announce, which
                    // on a normal server is approximately never.
                    if (fresh == null) fresh = new ArrayList<>(4);
                    fresh.add(c);
                    highest = Math.max(highest, c.id);
                }
            }
        }

        if (fresh == null) return;
        final List<Cast> batch = fresh;
        // Advance the cursor before sending: a send that throws must not make the
        // same line repeat on the next poll.
        cursor = highest;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Cast c : batch) send(c);
        });
    }

    private void send(Cast c) {
        try {
            String text = ChatColor.translateAlternateColorCodes('&', c.message == null ? "" : c.message);
            String kind = c.kind == null ? "all" : c.kind.toLowerCase();

            // The two player-targeted kinds are why every server reads every row:
            // only the one the player is actually on will find them, so exactly
            // one server acts and no lookup of "which box are they on" is needed.
            if ("player".equals(kind) || "send".equals(kind)) {
                Player target = findLocal(c.target);
                if (target == null) return; // they are on another server, or offline
                if ("player".equals(kind)) target.sendMessage(text);
                else connect(target, c.message);
                return;
            }

            if ("staff".equals(kind)) {
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

    private Player findLocal(String uuid) {
        if (uuid == null) return null;
        try {
            return Bukkit.getPlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Moves a player to another server, the way the proxy expects to be asked. */
    private void connect(Player player, String server) {
        if (server == null || server.isEmpty()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not send " + player.getName() + " to " + server + ": " + e.getMessage());
        }
    }
}
