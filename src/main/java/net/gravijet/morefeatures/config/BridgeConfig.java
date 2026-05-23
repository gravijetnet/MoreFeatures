package net.gravijet.morefeatures.config;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.regex.Pattern;

@Getter
public class BridgeConfig {

    // Each DNS label: starts and ends with alphanumeric, hyphens only in the middle.
    // Underscore allowed for non-standard but common hostnames (e.g. docker service names).
    private static final Pattern SAFE_HOST     = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9\\-_]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-_]*[a-zA-Z0-9])?)*$");
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

        this.host = rawHost;
        int rawPort = cfg.getInt("database.port", 3306);
        if (rawPort < 1 || rawPort > 65535) {
            throw new IllegalArgumentException(
                    "database.port is out of range (1-65535): " + rawPort);
        }
        this.port              = rawPort;
        this.database          = rawDb;
        this.username          = cfg.getString("database.username", "root");
        this.password          = cfg.getString("database.password", "");
        this.poolSize          = cfg.getInt("database.pool-size", 10);
        this.syncIntervalTicks = cfg.getLong("sync.interval-ticks", 6000L);
        this.useSSL            = cfg.getBoolean("database.use-ssl", false);
    }

    public String buildJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + (useSSL ? "" : "&allowPublicKeyRetrieval=true")
                + "&characterEncoding=utf8";
    }
}
