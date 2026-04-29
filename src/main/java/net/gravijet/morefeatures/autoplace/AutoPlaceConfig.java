package net.gravijet.morefeatures.autoplace;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Reads AutoPlace detection settings from antiautoplace.yml.
 * The enabled flag lives in config.yml and is checked by Main before this class is used.
 */
public class AutoPlaceConfig {

    private final FileConfiguration cfg;

    public AutoPlaceConfig(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "antiautoplace.yml");
        if (!file.exists()) {
            plugin.saveResource("antiautoplace.yml", false);
        }
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public boolean shouldCancel() {
        return cfg.getBoolean("cancel", false);
    }

    public boolean shouldAlert() {
        return cfg.getBoolean("alerts.enabled", true);
    }

    public boolean shouldPunish() {
        return cfg.getBoolean("punishments.enabled", false);
    }

    /**
     * Minimum time (ms) allowed to place {@code PLACE_WINDOW_SIZE} (10) blocks.
     * Default 400 ms → allows up to 25 blocks/s before flagging as FastPlace.
     */
    public long getFastPlaceWindowMs() {
        return cfg.getLong("fastplace.window-ms", 400L);
    }

    /**
     * Number of flags required before an alert fires.
     * Reduces false-positive noise from lag spikes.
     */
    public int getFlagThreshold() {
        return Math.max(1, cfg.getInt("flags.alert-threshold", 3));
    }

    /**
     * Number of flags required before punishment is executed.
     * Should be >= alert-threshold.
     */
    public int getPunishThreshold() {
        return Math.max(getFlagThreshold(), cfg.getInt("flags.punish-threshold", 10));
    }

    public String getAlertMessage(Player player, String type, int flagCount) {
        String raw = cfg.getString("alerts.message",
                "&c[AutoPlace] %player% flagged for %type% (flags: %flags%)");
        return ChatColor.translateAlternateColorCodes('&',
                applyPlaceholders(raw, player)
                        .replace("%type%", type)
                        .replace("%flags%", String.valueOf(flagCount)));
    }

    public String getPunishmentCommand(Player player) {
        String raw = cfg.getString("punishments.command",
                "ban %player% 30d AutoPlace");
        return applyPlaceholders(raw, player);
    }

    private static String applyPlaceholders(String s, Player player) {
        return s.replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString());
    }
}
