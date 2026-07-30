package net.gravijet.morefeatures.display;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Reads display.yml. The enabled flag lives in config.yml and is checked by Main
 * before this class is used.
 */
public class DisplayConfig {

    /**
     * Clamped so a mistyped value cannot either hammer Phoenix on every
     * placeholder request or freeze the tablist for a minute.
     */
    private static final long MIN_CACHE_TTL_MS = 50L;
    private static final long MAX_CACHE_TTL_MS = 5_000L;

    private final boolean higherPriorityFirst;
    private final String  prefixFallback;
    private final boolean chatEnabled;
    private final String  chatFormat;
    private final String  chatColorPermission;
    private final long    cacheTtlMs;

    public DisplayConfig(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "display.yml");
        if (!file.exists()) {
            plugin.saveResource("display.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        this.higherPriorityFirst = cfg.getBoolean("sorting.higher-priority-first", true);
        this.prefixFallback      = cfg.getString("prefix.fallback", "&7");
        this.chatEnabled         = cfg.getBoolean("chat.enabled", true);
        this.chatColorPermission = cfg.getString("chat.color-permission", "morefeatures.chat.color");
        this.cacheTtlMs          = Math.max(MIN_CACHE_TTL_MS,
                Math.min(MAX_CACHE_TTL_MS, cfg.getLong("cache.ttl-ms", 200L)));

        String format = cfg.getString("chat.format",
                "<prefix>%pxcosmetics_player_color%%phoenix_player_name%<suffix>"
                        + "%phoenix_player_tag%&7: %pxcosmetics_player_chat_color%<message>");
        // Without the token there is nowhere to put what the player typed, and
        // chat would go out as a prefix and nothing else. Appending it is a
        // better answer than silently swallowing every message on the server.
        if (!format.contains("<message>")) {
            plugin.getLogger().warning("chat.format in display.yml has no <message> token — "
                    + "appending it, otherwise no chat message would ever be shown.");
            format = format + "<message>";
        }
        this.chatFormat = format;
    }

    /** True when a higher Phoenix priority number means a higher rank. */
    public boolean isHigherPriorityFirst() {
        return higherPriorityFirst;
    }

    /**
     * Prefix used when Phoenix has nothing for a player.
     *
     * An empty prefix is what makes a name render white with no rank in front
     * of it, so this is never allowed to be empty in practice.
     */
    public String getPrefixFallback() {
        return prefixFallback;
    }

    public boolean isChatEnabled() {
        return chatEnabled;
    }

    /** The lobby chat format. Inside an arena MBedwars keeps its own. */
    public String getChatFormat() {
        return chatFormat;
    }

    /** Permission that lets a player use &-colour codes in their own messages. */
    public String getChatColorPermission() {
        return chatColorPermission;
    }

    /**
     * How long a resolved player stays valid before Phoenix is asked again.
     *
     * TAB refreshes nine placeholders per player per viewer, so this is the
     * difference between one Phoenix lookup per player per fifth of a second
     * and several hundred per second on a full server. A rank change still
     * appears within this window, which at the default is not perceptible.
     */
    public long getCacheTtlMs() {
        return cacheTtlMs;
    }
}
