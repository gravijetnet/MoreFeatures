package net.gravijet.morefeatures.config;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;
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
        this(cfg, null);
    }

    public BridgeConfig(FileConfiguration cfg, Logger logger) {
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

        if (logger != null && "root".equals(this.username) && this.password.isEmpty()) {
            logger.warning("database.username/password are using default values (root / empty). "
                    + "Change these in phoenix.yml before deploying to production.");
        }

        int rawPoolSize = cfg.getInt("database.pool-size", 10);
        if (rawPoolSize < 1) {
            throw new IllegalArgumentException(
                    "database.pool-size must be at least 1, got: " + rawPoolSize);
        }
        this.poolSize          = rawPoolSize;
        this.syncIntervalTicks = cfg.getLong("sync.interval-ticks", 6000L);
        this.useSSL            = cfg.getBoolean("database.use-ssl", false);

        // BUG-23 fix: warn operators when SSL is disabled so they are aware of the risk.
        if (logger != null && !this.useSSL) {
            logger.warning("database.use-ssl is false. Database credentials will be sent "
                    + "in plaintext. Set use-ssl: true in phoenix.yml for production deployments.");
        }
    }

    public String buildJdbcUrl() {
        // BUG-23 fix: do not append allowPublicKeyRetrieval=true when SSL is disabled.
        // That parameter allows the server to send its RSA public key over an unencrypted
        // channel, enabling a MITM to intercept the password. Omitting it means the
        // connector will refuse to authenticate without SSL if the server requires RSA key
        // exchange — the correct secure failure mode.
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&characterEncoding=utf8";
    }
}
