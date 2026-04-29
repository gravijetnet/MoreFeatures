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

    public String getAlertMessage(Player player) {
        String raw = cfg.getString("alerts.message",
                "&c%player% has been flagged for AutoPlace!");
        return ChatColor.translateAlternateColorCodes('&', applyPlaceholders(raw, player));
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
