package net.gravijet.morefeatures.config;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.regex.Pattern;

@Getter
public class BridgeConfig {

    // BUG-08: validate host/database to prevent JDBC URL parameter injection
    private static final Pattern SAFE_HOST     = Pattern.compile("^[a-zA-Z0-9.\\-_]+$");
    private static final Pattern SAFE_DATABASE = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final long syncIntervalTicks;
    private final boolean useSSL;

    public BridgeConfig(FileConfiguration cfg) {
        String rawHost = cfg.getString("database.host", "localhost");
        String rawDb   = cfg.getString("database.name", "bridge");

        if (!SAFE_HOST.matcher(rawHost).matches()) {
            throw new IllegalArgumentException(
                    "database.host contains unsafe characters: " + rawHost);
        }
        if (!SAFE_DATABASE.matcher(rawDb).matches()) {
            throw new IllegalArgumentException(
                    "database.name contains unsafe characters: " + rawDb);
        }

        this.host              = rawHost;
        this.port              = cfg.getInt("database.port", 3306);
        this.database          = rawDb;
        this.username          = cfg.getString("database.username", "root");
        this.password          = cfg.getString("database.password", "");
        this.poolSize          = cfg.getInt("database.pool-size", 10);
        this.syncIntervalTicks = cfg.getLong("sync.interval-ticks", 6000L);
        this.useSSL            = cfg.getBoolean("database.use-ssl", false);
    }

    public String buildJdbcUrl() {
        // BUG-09: removed &autoReconnect=true — deprecated, masks errors, HikariCP handles reconnection
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + (useSSL ? "" : "&allowPublicKeyRetrieval=true")
                + "&characterEncoding=utf8";
    }
}
