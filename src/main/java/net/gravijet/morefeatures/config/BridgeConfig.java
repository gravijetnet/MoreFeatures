package net.gravijet.morefeatures.config;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

@Getter
public class BridgeConfig {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final long syncIntervalTicks;

    public BridgeConfig(FileConfiguration cfg) {
        this.host              = cfg.getString("database.host", "localhost");
        this.port              = cfg.getInt("database.port", 3306);
        this.database          = cfg.getString("database.name", "bridge");
        this.username          = cfg.getString("database.username", "root");
        this.password          = cfg.getString("database.password", "");
        this.poolSize          = cfg.getInt("database.pool-size", 10);
        this.syncIntervalTicks = cfg.getLong("sync.interval-ticks", 6000L);
    }

    public String buildJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false"
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8"
                + "&autoReconnect=true";
    }
}
