package net.gravijet.morefeatures.music;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads and holds the music.yml configuration.
 */
public class MusicConfig {

    private final List<SongEntry> songs = new ArrayList<>();
    private final String rickrollFile;
    private final byte volume;

    /**
     * Loads music.yml from the plugin's data folder.
     * If it doesn't exist, the default from the jar is saved first.
     */
    public MusicConfig(JavaPlugin plugin) {
        Logger logger = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), "music.yml");

        if (!file.exists()) {
            plugin.saveResource("music.yml", false);
            logger.info("Created default music.yml — edit it to configure song downloads.");
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        for (Object obj : cfg.getList("songs", Collections.emptyList())) {
            if (!(obj instanceof java.util.Map)) continue;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) obj;

            Object rawUrl      = map.get("url");
            Object rawFilename = map.get("filename");
            String url      = (rawUrl      instanceof String) ? (String) rawUrl      : "";
            String filename = (rawFilename instanceof String) ? (String) rawFilename : "";

            if (!url.isEmpty() && !filename.isEmpty()) {
                songs.add(new SongEntry(url, filename));
            } else {
                logger.warning("Skipping malformed song entry in music.yml: url=" + url + " filename=" + filename);
            }
        }

        logger.info("Loaded " + songs.size() + " song entries from music.yml.");

        // BUG-30 fix: validate rickroll_file at load time so a misconfigured or
        // adversarially-set value cannot slip past the downstream isSafeFilename() check.
        String rawRickroll = cfg.getString("rickroll_file", "rickroll.nbs");
        if (!isSafeFilename(rawRickroll)) {
            logger.warning("rickroll_file '" + rawRickroll + "' is unsafe — falling back to 'rickroll.nbs'.");
            rawRickroll = "rickroll.nbs";
        }
        this.rickrollFile = rawRickroll;

        // Volume — clamp to 1-100
        int rawVolume = cfg.getInt("volume", 80);
        this.volume = (byte) Math.max(1, Math.min(100, rawVolume));
    }

    public List<SongEntry> getSongs() {
        return Collections.unmodifiableList(songs);
    }

    /**
     * Returns the filename that /rickroll should play.
     * Configured via {@code rickroll_file} in music.yml; defaults to "rickroll.nbs".
     */
    public String getRickrollFile() {
        return rickrollFile;
    }

    /**
     * Returns the default playback volume (1-100).
     * Configured via {@code volume} in music.yml; defaults to 80.
     */
    public byte getVolume() {
        return volume;
    }

    private static boolean isSafeFilename(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        if (name.contains("..")) return false;
        return new java.io.File(name).getName().equals(name);
    }

    /**
     * A single song entry: remote URL → local filename.
     */
    public static class SongEntry {
        private final String url;
        private final String filename;

        public SongEntry(String url, String filename) {
            this.url = url;
            this.filename = filename;
        }

        public String getUrl() {
            return url;
        }

        public String getFilename() {
            return filename;
        }
    }
}
