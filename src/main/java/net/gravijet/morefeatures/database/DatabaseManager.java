package net.gravijet.morefeatures.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.gravijet.morefeatures.config.BridgeConfig;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {

    private static final ZoneId STORE_ZONE = ZoneId.of("UTC");

    // -------------------------------------------------------------------------
    // DDL
    // -------------------------------------------------------------------------

    private static final String CREATE_PLAYERS = ""
            + "CREATE TABLE IF NOT EXISTS `players` ("
            + "  `uuid`       VARCHAR(36)  NOT NULL,"
            + "  `name`       VARCHAR(100) NOT NULL,"
            + "  `rank`       VARCHAR(100),"
            + "  `playtime`   BIGINT,"
            + "  `online`     BOOLEAN,"
            + "  `first_seen` DATETIME,"
            + "  `last_seen`  DATETIME,"
            + "  PRIMARY KEY (`uuid`),"
            + "  INDEX `idx_name` (`name`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    // Single-row table: one column per stat, always id = 1
    private static final String CREATE_NETWORK_STATS = ""
            + "CREATE TABLE IF NOT EXISTS `network_stats` ("
            + "  `id`             TINYINT NOT NULL,"
            + "  `total_players`  BIGINT,"
            + "  `total_bans`     BIGINT,"
            + "  `total_mutes`    BIGINT,"
            + "  `total_kicks`    BIGINT,"
            + "  `peak_online`    BIGINT,"
            + "  `current_online` BIGINT,"
            + "  PRIMARY KEY (`id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    private static final String SEED_NETWORK_STATS =
            "INSERT IGNORE INTO `network_stats` (`id`) VALUES (1);";

    // Playtime is intentionally excluded — updated only by the per-player 5-minute timer
    // first_seen uses LEAST+COALESCE so a Phoenix-derived earlier date can overwrite a stale one
    private static final String UPSERT_PLAYER =
            "INSERT INTO `players` (`uuid`, `name`, `rank`, `online`, `first_seen`, `last_seen`)"
            + " VALUES (?, ?, ?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE"
            + "   `name`       = VALUES(`name`),"
            + "   `rank`       = VALUES(`rank`),"
            + "   `online`     = VALUES(`online`),"
            + "   `first_seen` = LEAST(COALESCE(`first_seen`, VALUES(`first_seen`)), VALUES(`first_seen`)),"
            + "   `last_seen`  = VALUES(`last_seen`);";

    // Same as UPSERT_PLAYER minus the rank, for the Bukkit-only join path where no
    // Phoenix profile is available yet. Leaving `rank` out of both the insert and the
    // update keeps a cold join from blanking a rank Phoenix already told us about.
    private static final String UPSERT_PLAYER_BASIC =
            "INSERT INTO `players` (`uuid`, `name`, `online`, `first_seen`, `last_seen`)"
            + " VALUES (?, ?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE"
            + "   `name`       = VALUES(`name`),"
            + "   `online`     = VALUES(`online`),"
            + "   `first_seen` = LEAST(COALESCE(`first_seen`, VALUES(`first_seen`)), VALUES(`first_seen`)),"
            + "   `last_seen`  = VALUES(`last_seen`);";

    private static final String SET_PLAYER_ONLINE =
            "UPDATE `players` SET `online` = ?, `last_seen` = ? WHERE `uuid` = ?;";

    private static final String UPDATE_PLAYER_PLAYTIME =
            "UPDATE `players` SET `playtime` = ?, `last_seen` = ? WHERE `uuid` = ?;";

    private static final String UPDATE_PLAYER_RANK =
            "UPDATE `players` SET `rank` = ? WHERE `uuid` = ?;";

    // total_players comes from countPlayers(); peak_online only ever increases
    private static final String UPDATE_NETWORK_ONLINE =
            "UPDATE `network_stats` SET"
            + "  `current_online` = ?,"
            + "  `total_players`  = ?,"
            + "  `peak_online`    = GREATEST(COALESCE(`peak_online`, 0), ?)"
            + " WHERE `id` = 1;";

    // -------------------------------------------------------------------------

    private final HikariDataSource dataSource;
    private final Logger logger;

    public DatabaseManager(BridgeConfig config, Logger logger) {
        this.logger = logger;

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.buildJdbcUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());
        int poolSize = config.getPoolSize(); // BridgeConfig already validates >= 1
        hikari.setMaximumPoolSize(poolSize);
        // Use half the pool size (min 1) so idle connections shrink when a large
        // pool is configured, while still keeping at least one warm connection.
        hikari.setMinimumIdle(Math.max(1, poolSize / 2));
        hikari.setConnectionTimeout(5_000L);
        hikari.setIdleTimeout(300_000L);
        hikari.setMaxLifetime(600_000L);
        hikari.setPoolName("Bridge-Pool");

        hikari.addDataSourceProperty("cachePrepStmts",          "true");
        hikari.addDataSourceProperty("prepStmtCacheSize",        "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit",    "2048");
        hikari.addDataSourceProperty("useServerPrepStmts",       "true");
        hikari.addDataSourceProperty("rewriteBatchedStatements", "true");

        this.dataSource = new HikariDataSource(hikari);
    }

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    public void createTables() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(CREATE_PLAYERS);
            stmt.execute(CREATE_NETWORK_STATS);

            try (PreparedStatement ps = conn.prepareStatement(SEED_NETWORK_STATS)) {
                ps.executeUpdate();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Player operations
    // -------------------------------------------------------------------------

    public void upsertPlayer(String uuid, String name, String rank,
                             boolean online, Timestamp firstSeen) {
        Timestamp now = now();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_PLAYER)) {

            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, rank);
            ps.setBoolean(4, online);
            ps.setTimestamp(5, firstSeen);
            ps.setTimestamp(6, now);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to upsert player " + uuid, e);
        }
    }

    /**
     * Writes the row a plain Bukkit join can fill on its own — no rank, no playtime.
     * Phoenix's own join event enriches those when it arrives; the upsert's LEAST()
     * keeps whichever first_seen is earlier, so seeding it with "now" here is safe
     * even for a player whose real first_seen is years old.
     */
    public void upsertPlayerBasic(String uuid, String name, boolean online) {
        Timestamp now = now();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_PLAYER_BASIC)) {

            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setBoolean(3, online);
            ps.setTimestamp(4, now);
            ps.setTimestamp(5, now);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to upsert base row for player " + uuid, e);
        }
    }

    public void setPlayerOnline(String uuid, boolean online) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SET_PLAYER_ONLINE)) {

            ps.setBoolean(1, online);
            ps.setTimestamp(2, now());
            ps.setString(3, uuid);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set online status for " + uuid, e);
        }
    }

    public void updatePlayerPlaytime(String uuid, long playtime) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_PLAYER_PLAYTIME)) {

            ps.setLong(1, playtime);
            ps.setTimestamp(2, now());
            ps.setString(3, uuid);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update playtime for " + uuid, e);
        }
    }

    public void updatePlayerRank(String uuid, String rank) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_PLAYER_RANK)) {

            ps.setString(1, rank);
            ps.setString(2, uuid);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update rank for " + uuid, e);
        }
    }

    public long countPlayers() {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `players`");
             ResultSet rs = ps.executeQuery()) {

            return rs.next() ? rs.getLong(1) : 0L;

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to count players", e);
            return 0L;
        }
    }

    // -------------------------------------------------------------------------
    // Network stats operations
    // -------------------------------------------------------------------------

    public Long getStatValue(String key) {
        String col = keyToColumn(key); // validate key — throws if unknown
        String sql = GET_STAT_SQL.get(col);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                long val = rs.getLong(1);
                return rs.wasNull() ? null : val;
            }
            return null;

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read stat " + key, e);
            return null;
        }
    }

    public void updateStat(String key, long value) {
        String col = keyToColumn(key); // validate key
        String sql = UPDATE_STAT_SQL.get(col);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, value);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update stat " + key, e);
        }
    }

    public void incrementStat(String key, long amount) {
        String col = keyToColumn(key); // validate key
        String sql = INCREMENT_STAT_SQL.get(col);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, amount);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to increment stat " + key, e);
        }
    }

    public void updateNetworkStats(long currentOnline, long totalPlayers) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_NETWORK_ONLINE)) {

            ps.setLong(1, currentOnline);
            ps.setLong(2, totalPlayers);
            ps.setLong(3, currentOnline);
            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update network stats", e);
        }
    }

    private static final java.util.Map<String, String> GET_STAT_SQL;
    private static final java.util.Map<String, String> UPDATE_STAT_SQL;
    private static final java.util.Map<String, String> INCREMENT_STAT_SQL;

    static {
        String[] cols = {"total_players", "total_bans", "total_mutes",
                         "total_kicks", "peak_online", "current_online"};
        java.util.Map<String, String> get  = new java.util.HashMap<>();
        java.util.Map<String, String> upd  = new java.util.HashMap<>();
        java.util.Map<String, String> incr = new java.util.HashMap<>();
        for (String c : cols) {
            get.put(c,  "SELECT `" + c + "` FROM `network_stats` WHERE `id` = 1;");
            upd.put(c,  "UPDATE `network_stats` SET `" + c + "` = ? WHERE `id` = 1;");
            incr.put(c, "UPDATE `network_stats` SET `" + c + "` = COALESCE(`" + c + "`, 0) + ? WHERE `id` = 1;");
        }
        GET_STAT_SQL      = java.util.Collections.unmodifiableMap(get);
        UPDATE_STAT_SQL   = java.util.Collections.unmodifiableMap(upd);
        INCREMENT_STAT_SQL = java.util.Collections.unmodifiableMap(incr);
    }

    // Whitelist to prevent any possibility of SQL injection via key strings
    private static String keyToColumn(String key) {
        switch (key) {
            case "total_players":  return "total_players";
            case "total_bans":     return "total_bans";
            case "total_mutes":    return "total_mutes";
            case "total_kicks":    return "total_kicks";
            case "peak_online":    return "peak_online";
            case "current_online": return "current_online";
            default: throw new IllegalArgumentException("Unknown stat key: " + key);
        }
    }

    // -------------------------------------------------------------------------

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /** Public so the link package can borrow the same pool rather than open a second one. */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(STORE_ZONE));
    }
}
