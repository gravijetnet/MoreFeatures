package net.gravijet.morefeatures.music;

import com.xxmicloxx.NoteBlockAPI.event.SongEndEvent;
import com.xxmicloxx.NoteBlockAPI.model.SoundCategory;
import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.songplayer.SongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core music engine that wraps NoteBlockAPI.
 *
 * NBS parsing is done async to avoid main-thread I/O lag. The RadioSongPlayer
 * is then created and started on the main thread — NoteBlockAPI's internal
 * scheduler integration is not thread-safe, and starting playback off-thread
 * causes the stuttering / skipping symptom.
 */
public class MusicManager implements Listener {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final File songsFolder;

    private volatile byte volume;

    private final Map<UUID, SongPlayer> activePlayers = new ConcurrentHashMap<>();
    // BUG-36: tracks pending auto-stop task IDs so they can be cancelled if the song ends early
    private final Map<UUID, Integer> stopTasks = new ConcurrentHashMap<>();

    public MusicManager(JavaPlugin plugin, File songsFolder, byte volume) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.songsFolder = songsFolder;
        this.volume = volume;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // -----------------------------------------------------------------
    //  SongEndEvent — fired by NoteBlockAPI when a song ends naturally
    // -----------------------------------------------------------------

    @EventHandler
    public void onSongEnd(SongEndEvent event) {
        SongPlayer sp = event.getSongPlayer();
        // BUG-27: use .equals() instead of == in case NoteBlockAPI wraps the instance
        activePlayers.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(sp)) {
                // BUG-36: cancel any pending auto-stop task for this player when the song ends naturally
                cancelStopTask(entry.getKey());
                return true;
            }
            return false;
        });
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    /**
     * Parses the NBS file async, then starts playback on the main thread.
     * This avoids both main-thread I/O lag and NoteBlockAPI thread-safety issues.
     */
    public void playSongAsync(Player player, String filename, Runnable onSuccess, Runnable onFailure) {
        if (!isSafeFilename(filename)) {
            player.sendMessage("§cInvalid song name: " + filename);
            if (onFailure != null) onFailure.run();
            return;
        }
        File file = new File(songsFolder, filename);
        if (!file.exists()) {
            player.sendMessage("§cSong file not found: " + filename);
            logger.warning("Song file missing: " + file.getAbsolutePath());
            if (onFailure != null) onFailure.run();
            return;
        }

        UUID uuid = player.getUniqueId();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Song song;
            try {
                song = NBSDecoder.parse(file);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to parse .nbs file: " + filename, e);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cFailed to load song: " + filename);
                    if (onFailure != null) onFailure.run();
                });
                return;
            }

            if (song == null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cFailed to parse song file: " + filename);
                    if (onFailure != null) onFailure.run();
                });
                return;
            }

            // NoteBlockAPI must be started on the main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                RadioSongPlayer songPlayer;
                try {
                    songPlayer = new RadioSongPlayer(song, SoundCategory.MASTER);
                    songPlayer.setAutoDestroy(true);
                    songPlayer.setVolume(volume);
                    songPlayer.addPlayer(player);
                    songPlayer.setPlaying(true);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to start song player for " + filename, e);
                    player.sendMessage("§cFailed to start playback.");
                    if (onFailure != null) onFailure.run();
                    return;
                }

                // BUG-30: stop the old song and atomically replace it; if two concurrent
                // playSongAsync calls race to this point the compute() is atomic so only one wins
                activePlayers.compute(uuid, (id, existing) -> {
                    if (existing != null) {
                        try { existing.setPlaying(false); existing.destroy(); }
                        catch (Exception ignored) {}
                    }
                    return songPlayer;
                });
                logger.info("Playing '" + filename + "' for " + player.getName()
                        + " (" + song.getLength() + " NBS ticks, speed=" + song.getSpeed() + ")");
                if (onSuccess != null) onSuccess.run();
            });
        });
    }

    public void stopSong(Player player) {
        UUID uuid = player.getUniqueId();
        cancelStopTask(uuid); // BUG-36: cancel any pending auto-stop before stopping
        stopSongInternal(uuid);
    }

    /** Registers a scheduled stop task so it can be cancelled if the song ends naturally. */
    public void registerStopTask(UUID uuid, int taskId) {
        cancelStopTask(uuid); // replace any existing stop task
        stopTasks.put(uuid, taskId);
    }

    private void cancelStopTask(UUID uuid) {
        Integer taskId = stopTasks.remove(uuid);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
    }

    public void stopAll() {
        List<UUID> uuids = new ArrayList<>(activePlayers.keySet());
        for (UUID uuid : uuids) {
            cancelStopTask(uuid); // BUG-36: clear any pending auto-stop tasks
            stopSongInternal(uuid);
        }
        logger.info("Stopped all active songs (" + uuids.size() + " player(s)).");
    }

    public SongPlayer getActiveSong(UUID uuid) {
        return activePlayers.get(uuid);
    }

    public List<String> getAvailableSongs() {
        List<String> list = new ArrayList<>();
        if (!songsFolder.exists() || !songsFolder.isDirectory()) return list;

        File[] files = songsFolder.listFiles(
                (dir, name) -> name.toLowerCase().endsWith(".nbs"));
        if (files == null) return list;

        for (File f : files) list.add(f.getName());
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public String getRandomSong() {
        List<String> songs = getAvailableSongs();
        if (songs.isEmpty()) return null;
        // BUG-28: ThreadLocalRandom avoids the synchronised shared Random in Math.random()
        return songs.get(ThreadLocalRandom.current().nextInt(songs.size()));
    }

    public void setVolume(byte v) {
        this.volume = (byte) Math.max(1, Math.min(100, v));
    }

    public byte getVolume() {
        return volume;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public File getSongsFolder() {
        return songsFolder;
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    // Rejects path traversal — player-supplied names must be a plain file name
    // directly inside the songs folder, never "../" or an absolute path.
    private static boolean isSafeFilename(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        if (name.contains("..")) return false;
        return new File(name).getName().equals(name);
    }

    private void stopSongInternal(UUID uuid) {
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
}
