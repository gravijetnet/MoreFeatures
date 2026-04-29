package net.gravijet.morefeatures.music.listener;

import net.gravijet.morefeatures.music.MusicManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cleans up music state when players leave the server.
 */
public class MusicListener implements Listener {

    private final MusicManager musicManager;

    public MusicListener(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        musicManager.stopSong(event.getPlayer());
    }
}
