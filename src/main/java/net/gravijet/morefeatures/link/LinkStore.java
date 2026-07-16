package net.gravijet.morefeatures.link;

import net.gravijet.morefeatures.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Minecraft ↔ Discord account links, in the same `phoenixbridge` schema the rest
 * of the bridge writes.
 *
 * The website cannot prove on its own that a visitor owns a Minecraft account:
 * most Gravijet servers run online-mode=false, so Mojang is not a witness, and a
 * name typed into a form is only a claim. A code typed in game is proof, because
 * only whoever is holding that account can type it there.
 *
 * So the website mints a code against a signed-in Discord session and puts it
 * here; /link redeems it from inside the game. Each side proves the half it owns
 * and neither takes the other's word for it.
 */
public class LinkStore {

    /** Short enough to retype off a screen, long enough not to be guessed at. */
    public static final int CODE_LENGTH = 6;

    private static final String CREATE_LINKS = ""
            + "CREATE TABLE IF NOT EXISTS `account_links` ("
            + "  `uuid`         VARCHAR(36)  NOT NULL,"
            + "  `name`         VARCHAR(100) NOT NULL,"
            + "  `discord_id`   VARCHAR(32)  NOT NULL,"
            + "  `discord_name` VARCHAR(100),"
            + "  `linked_at`    DATETIME     NOT NULL,"
            + "  PRIMARY KEY (`uuid`),"
            // One Discord account to one Minecraft account, enforced by the
            // database rather than by whoever remembers to check. A Discord id
            // wearing two Minecraft accounts is two sets of ranks and an appeal
            // nobody can attribute.
            + "  UNIQUE KEY `uq_discord` (`discord_id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    private static final String CREATE_CODES = ""
            + "CREATE TABLE IF NOT EXISTS `link_codes` ("
            + "  `code`         VARCHAR(16)  NOT NULL,"
            + "  `discord_id`   VARCHAR(32)  NOT NULL,"
            + "  `discord_name` VARCHAR(100),"
            + "  `created_at`   DATETIME     NOT NULL,"
            + "  `expires_at`   DATETIME     NOT NULL,"
            + "  PRIMARY KEY (`code`),"
            + "  INDEX `idx_discord` (`discord_id`),"
            + "  INDEX `idx_expires` (`expires_at`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    private final DatabaseManager database;

    public LinkStore(DatabaseManager database) {
        this.database = database;
    }

    public void createTables() throws SQLException {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(CREATE_LINKS);
            st.executeUpdate(CREATE_CODES);
        }
    }

    /** Why a /link attempt ended the way it did. The command turns these into sentences. */
    public enum Result {
        LINKED,
        UNKNOWN_CODE,
        EXPIRED,
        ALREADY_LINKED,
        DISCORD_TAKEN,
        ERROR
    }

    public static class Outcome {
        public final Result result;
        public final String discordName;

        Outcome(Result result, String discordName) {
            this.result = result;
            this.discordName = discordName;
        }
    }

    /**
     * Redeems a code for this player. The code is consumed whether or not the
     * link lands, so one that went past in chat cannot be replayed by whoever
     * was reading.
     */
    public Outcome redeem(UUID uuid, String name, String code) {
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String discordId;
                String discordName;
                Timestamp expiresAt;

                // FOR UPDATE: two players racing the same code must not both get
                // it. The loser waits here and then finds it gone.
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT `discord_id`, `discord_name`, `expires_at` FROM `link_codes` WHERE `code` = ? FOR UPDATE")) {
                    ps.setString(1, code);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return new Outcome(Result.UNKNOWN_CODE, null);
                        }
                        discordId = rs.getString("discord_id");
                        discordName = rs.getString("discord_name");
                        expiresAt = rs.getTimestamp("expires_at");
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM `link_codes` WHERE `code` = ?")) {
                    ps.setString(1, code);
                    ps.executeUpdate();
                }

                if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
                    conn.commit();
                    return new Outcome(Result.EXPIRED, discordName);
                }
                if (isTaken(conn, "uuid", uuid.toString())) {
                    conn.commit();
                    return new Outcome(Result.ALREADY_LINKED, discordName);
                }
                if (isTaken(conn, "discord_id", discordId)) {
                    conn.commit();
                    return new Outcome(Result.DISCORD_TAKEN, discordName);
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO `account_links` (`uuid`, `name`, `discord_id`, `discord_name`, `linked_at`)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, discordId);
                    ps.setString(4, discordName);
                    ps.setTimestamp(5, Timestamp.from(Instant.now()));
                    ps.executeUpdate();
                }

                conn.commit();
                return new Outcome(Result.LINKED, discordName);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return new Outcome(Result.ERROR, null);
        }
    }

    private boolean isTaken(Connection conn, String column, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM `account_links` WHERE `" + column + "` = ? LIMIT 1")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The Discord name this player is linked to, or null if they are not. */
    public String linkedDiscord(UUID uuid) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT `discord_name`, `discord_id` FROM `account_links` WHERE `uuid` = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String name = rs.getString("discord_name");
                return name != null ? name : rs.getString("discord_id");
            }
        }
    }

    /** @return true if a link was actually removed. */
    public boolean unlink(UUID uuid) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM `account_links` WHERE `uuid` = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    /** Codes nobody redeemed. Left alone they are guesses waiting to land. */
    public int purgeExpired() throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM `link_codes` WHERE `expires_at` < ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            return ps.executeUpdate();
        }
    }
}
