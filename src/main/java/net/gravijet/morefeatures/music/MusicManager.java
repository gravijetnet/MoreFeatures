package net.gravijet.morefeatures.music;

import com.xxmicloxx.NoteBlockAPI.NBSDecoder;
import com.xxmicloxx.NoteBlockAPI.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.Song;
import com.xxmicloxx.NoteBlockAPI.SongPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core music engine that wraps NoteBlockAPI.
 * <p>
 * Each player can have at most one active song. When a new song is played,
 * the previous one is stopped and cleaned up automatically.
 */
public class MusicManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final File songsFolder;

    /** Default playback volume (1-100). */
    private volatile byte volume;

    /** Currently active SongPlayer per player UUID. */
    private final Map<UUID, SongPlayer> activePlayers = new ConcurrentHashMap<>();

    /** Scheduled auto-stop task IDs per player UUID. */
    private final Map<UUID, Integer> stopTasks = new ConcurrentHashMap<>();

    public MusicManager(JavaPlugin plugin, File songsFolder, byte volume) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.songsFolder = songsFolder;
        this.volume = volume;
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    /**
     * Plays a song for a player. Stops any currently-playing song first.
     *
     * @param player   the target player
     * @param filename the .nbs filename (e.g. "rickroll.nbs")
     * @return true if the song was loaded and playback started
     */
    public boolean playSong(Player player, String filename) {
        File file = new File(songsFolder, filename);
        if (!file.exists()) {
            player.sendMessage("§cSong file not found: " + filename);
            logger.warning("Song file missing: " + file.getAbsolutePath());
            return false;
        }

        Song song;
        try {
            song = NBSDecoder.parse(file);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse .nbs file: " + filename, e);
            player.sendMessage("§cFailed to load song: " + filename);
            return false;
        }

        if (song == null) {
            player.sendMessage("§cFailed to parse song file: " + filename);
            return false;
        }

        // Stop current song if any
        stopSongInternal(player.getUniqueId());

        // Create and start the player
        SongPlayer songPlayer;
        try {
            songPlayer = new RadioSongPlayer(song);

            // ---- 1.8.8 smooth-playback optimisations ----
            songPlayer.setAutoDestroy(true);
            songPlayer.setVolume(volume);
            songPlayer.setFadeStart((byte) 100);   // disable fade-in
            songPlayer.setFadeTarget((byte) 100);  // disable fade-out

            songPlayer.addPlayer(player);
            songPlayer.setPlaying(true);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to start song player for " + filename, e);
            player.sendMessage("§cFailed to start playback.");
            return false;
        }

        activePlayers.put(player.getUniqueId(), songPlayer);

        // Schedule auto-cleanup when the song naturally ends
        short lengthTicks = song.getLength();
        if (lengthTicks > 0) {
            // Convert ticks to server ticks (1 tick = 1/20s). Add a small buffer.
            long delayTicks = Math.max(lengthTicks + 10, lengthTicks + 1);
            int taskId = plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> onSongFinished(player.getUniqueId()), delayTicks).getTaskId();
            stopTasks.put(player.getUniqueId(), taskId);
        }

        logger.info("Playing '" + filename + "' for " + player.getName()
                + " (" + lengthTicks + " ticks)");
        return true;
    }

    /**
     * Stops the currently-playing song for a player.
     *
     * @param player the player whose song should stop
     */
    public void stopSong(Player player) {
        stopSongInternal(player.getUniqueId());
    }

    /**
     * Stops all active songs across all players.
     */
    public void stopAll() {
        // Copy keys to avoid ConcurrentModificationException
        List<UUID> uuids = new ArrayList<>(activePlayers.keySet());
        for (UUID uuid : uuids) {
            stopSongInternal(uuid);
        }
        logger.info("Stopped all active songs (" + uuids.size() + " player(s)).");
    }

    /**
     * Returns the currently playing song info for a player, or null.
     */
    public SongPlayer getActiveSong(UUID uuid) {
        return activePlayers.get(uuid);
    }

    /**
     * Lists all .nbs filenames in the songs folder.
     *
     * @return list of filenames (sorted alphabetically)
     */
    public List<String> getAvailableSongs() {
        List<String> list = new ArrayList<>();
        if (!songsFolder.exists() || !songsFolder.isDirectory()) {
            return list;
        }

        File[] files = songsFolder.listFiles(
                (dir, name) -> name.toLowerCase().endsWith(".nbs"));
        if (files == null) return list;

        for (File f : files) {
            list.add(f.getName());
        }
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    /**
     * Returns a random song filename from the available .nbs files,
     * or null if no songs exist.
     */
    public String getRandomSong() {
        List<String> songs = getAvailableSongs();
        if (songs.isEmpty()) return null;
        return songs.get((int) (Math.random() * songs.size()));
    }

    /**
     * Sets the default volume for new SongPlayers (1-100).
     * Does not affect already-playing songs.
     */
    public void setVolume(byte volume) {
        this.volume = (byte) Math.max(1, Math.min(100, volume));
    }

    /**
     * Returns the current default volume.
     */
    public byte getVolume() {
        return volume;
    }

    /**
     * Returns the plugin instance for scheduling tasks.
     */
    public JavaPlugin getPlugin() {
        return plugin;
    }

    /**
     * Returns the songs folder for use by other components.
     */
    public File getSongsFolder() {
        return songsFolder;
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private void stopSongInternal(UUID uuid) {
        // Cancel the scheduled auto-stop task
        Integer taskId = stopTasks.remove(uuid);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }

        // Stop and destroy the SongPlayer
        SongPlayer sp = activePlayers.remove(uuid);
        if (sp != null) {
            try {
                sp.setPlaying(false);
                sp.destroy();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error destroying SongPlayer for " + uuid, e);
            }
        }
    }

    /**
     * Called by the scheduled task when a song finishes naturally.
     */
    private void onSongFinished(UUID uuid) {
        stopTasks.remove(uuid);
        SongPlayer sp = activePlayers.remove(uuid);
        if (sp != null) {
            try {
                sp.setPlaying(false);
                sp.destroy();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error cleaning up finished SongPlayer for " + uuid, e);
            }
        }
    }
}
