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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core music engine that wraps NoteBlockAPI.
 *
 * Each player can have at most one active song.  When a new song starts the
 * previous one is stopped first.  Cleanup on natural song-end is handled by
 * {@link SongEndEvent} so timing is always exact — no manual Bukkit-scheduler
 * timers that drift relative to the NBS playback tempo.
 */
public class MusicManager implements Listener {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final File songsFolder;

    private volatile byte volume;

    private final Map<UUID, SongPlayer> activePlayers = new ConcurrentHashMap<>();

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
        // Remove the entry whose value matches this SongPlayer
        activePlayers.values().remove(sp);
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    /**
     * Plays a song for a player. Stops any currently-playing song first.
     *
     * @return true if the song started successfully
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

        stopSongInternal(player.getUniqueId());

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
            return false;
        }

        activePlayers.put(player.getUniqueId(), songPlayer);

        logger.info("Playing '" + filename + "' for " + player.getName()
                + " (" + song.getLength() + " NBS ticks, speed=" + song.getSpeed() + ")");
        return true;
    }

    public void stopSong(Player player) {
        stopSongInternal(player.getUniqueId());
    }

    public void stopAll() {
        List<UUID> uuids = new ArrayList<>(activePlayers.keySet());
        for (UUID uuid : uuids) {
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
        return songs.get((int) (Math.random() * songs.size()));
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
