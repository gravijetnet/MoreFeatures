package net.gravijet.morefeatures.music.command;

import net.gravijet.morefeatures.music.MusicConfig;
import net.gravijet.morefeatures.music.MusicManager;
import net.gravijet.morefeatures.music.util.SongDownloader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /music — main music control command with subcommands.
 *
 * <pre>
 * /music play [song]     — play random or specific song
 * /music stop            — stop your current song
 * /music force <player> [song] — force play for another player
 * /music list            — list available songs
 * /music download        — manually trigger song download
 * /music info            — show what's currently playing for you
 * </pre>
 */
public class MusicCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "play", "stop", "force", "list", "download", "info", "volume"
    );

    private final MusicManager musicManager;
    private final SongDownloader songDownloader;
    private final MusicConfig musicConfig;

    public MusicCommand(MusicManager musicManager, SongDownloader songDownloader,
                        MusicConfig musicConfig) {
        this.musicManager = musicManager;
        this.songDownloader = songDownloader;
        this.musicConfig = musicConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "play":
                return handlePlay(sender, args);
            case "stop":
                return handleStop(sender);
            case "force":
                return handleForce(sender, args);
            case "list":
                return handleList(sender);
            case "download":
                return handleDownload(sender);
            case "info":
                return handleInfo(sender);
            case "volume":
                return handleVolume(sender, args);
            default:
                sender.sendMessage("§cUnknown subcommand: /music " + sub);
                sendUsage(sender);
                return true;
        }
    }

    // -----------------------------------------------------------------
    //  Subcommand handlers
    // -----------------------------------------------------------------

    private boolean handlePlay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can play music.");
            return true;
        }
        if (!sender.hasPermission("morefeatures.music.play")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        Player player = (Player) sender;
        String filename;

        if (args.length >= 2) {
            filename = args[1];
            if (!filename.toLowerCase().endsWith(".nbs")) {
                filename += ".nbs";
            }
        } else {
            filename = musicManager.getRandomSong();
            if (filename == null) {
                sender.sendMessage("§cNo songs available. Use /music download first.");
                return true;
            }
        }

        final String finalFilename = filename;
        sender.sendMessage("§7Loading song...");
        musicManager.playSongAsync(player, finalFilename,
                () -> sender.sendMessage("§aNow playing: " + finalFilename),
                null);
        return true;
    }

    private boolean handleStop(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can stop music.");
            return true;
        }
        if (!sender.hasPermission("morefeatures.music.stop")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        Player player = (Player) sender;
        musicManager.stopSong(player);
        sender.sendMessage("§aMusic stopped.");
        return true;
    }

    private boolean handleForce(CommandSender sender, String[] args) {
        if (!sender.hasPermission("morefeatures.music.force")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /music force <player> [song]");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return true;
        }

        String filename;
        if (args.length >= 3) {
            filename = args[2];
            if (!filename.toLowerCase().endsWith(".nbs")) {
                filename += ".nbs";
            }
        } else {
            filename = musicManager.getRandomSong();
            if (filename == null) {
                sender.sendMessage("§cNo songs available.");
                return true;
            }
        }

        final String finalFilename = filename;
        final Player finalTarget = target;
        musicManager.playSongAsync(finalTarget, finalFilename,
                () -> {
                    sender.sendMessage("§aPlaying '" + finalFilename + "' for " + finalTarget.getName() + ".");
                    if (finalTarget.isOnline()) {
                        finalTarget.sendMessage("§a" + sender.getName() + " started playing: " + finalFilename);
                    }
                },
                () -> sender.sendMessage("§cFailed to start playback for " + finalTarget.getName() + "."));
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("morefeatures.music.list")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        List<String> songs = musicManager.getAvailableSongs();
        if (songs.isEmpty()) {
            sender.sendMessage("§cNo .nbs files in the songs folder.");
            return true;
        }

        sender.sendMessage("§aAvailable songs (" + songs.size() + "):");
        for (String name : songs) {
            sender.sendMessage("§7 - " + name);
        }
        return true;
    }

    private boolean handleDownload(CommandSender sender) {
        if (!sender.hasPermission("morefeatures.music.download")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        sender.sendMessage("§aStarting song download...");
        org.bukkit.plugin.java.JavaPlugin plugin = musicManager.getPlugin();
        Bukkit.getScheduler().runTaskAsynchronously(
                plugin,
                () -> {
                    int count = songDownloader.downloadMissing(musicConfig);
                    Bukkit.getScheduler().runTask(
                            plugin,
                            () -> sender.sendMessage("§aDownload complete. "
                                    + count + " song(s) downloaded.")
                    );
                }
        );
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can check music info.");
            return true;
        }
        if (!sender.hasPermission("morefeatures.music.info")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        Player player = (Player) sender;
        com.xxmicloxx.NoteBlockAPI.songplayer.SongPlayer sp = musicManager.getActiveSong(
                player.getUniqueId());
        if (sp == null) {
            sender.sendMessage("§7No song is currently playing for you.");
        } else {
            com.xxmicloxx.NoteBlockAPI.model.Song song = sp.getSong();
            if (song == null) {
                sender.sendMessage("§7No song is currently playing for you.");
            } else {
                String songTitle = song.getTitle();
                String displayTitle = (songTitle != null && !songTitle.isEmpty()) ? songTitle : "(untitled)";
                short length = song.getLength();
                float speed  = song.getSpeed();
                int durationSeconds = speed > 0 ? (int) (length / speed) : 0;
                sender.sendMessage("§aNow playing: " + displayTitle
                        + " (" + length + " ticks / " + durationSeconds + "s)");
            }
        }
        return true;
    }

    private boolean handleVolume(CommandSender sender, String[] args) {
        if (!sender.hasPermission("morefeatures.music.volume")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§7Current default volume: §f" + musicManager.getVolume());
            sender.sendMessage("§7Usage: §f/music volume <1-100>");
            return true;
        }

        int vol;
        try {
            vol = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid number: " + args[1]);
            return true;
        }

        if (vol < 1 || vol > 100) {
            sender.sendMessage("§cVolume must be between 1 and 100.");
            return true;
        }

        musicManager.setVolume(vol);
        sender.sendMessage("§aDefault volume set to §f" + vol + "§a. "
                + "Only affects songs started after this change.");
        return true;
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§e§l/music §r§e— Music control");
        sender.sendMessage("§7  /music play [song]  §fPlay a random or specific song");
        sender.sendMessage("§7  /music stop          §fStop your current song");
        sender.sendMessage("§7  /music force <player> [song]  §fForce play for another player");
        sender.sendMessage("§7  /music list          §fList available songs");
        sender.sendMessage("§7  /music info          §fShow what's playing");
        sender.sendMessage("§7  /music volume [1-100] §fSet default playback volume");
        if (sender.hasPermission("morefeatures.music.download")) {
            sender.sendMessage("§7  /music download      §fDownload missing songs");
        }
    }

    // -----------------------------------------------------------------
    //  Tab completion
    // -----------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    // Hide download from players without permission
                    if (sub.equals("download") && !sender.hasPermission("morefeatures.music.download")) {
                        continue;
                    }
                    matches.add(sub);
                }
            }
            return matches;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("force")) {
            // Suggest online player names
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    names.add(p.getName());
                }
            }
            return names;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("volume")) {
            // Suggest common volume values
            List<String> values = new ArrayList<>();
            for (String v : new String[]{"50", "80", "100"}) {
                if (v.startsWith(args[1])) {
                    values.add(v);
                }
            }
            return values;
        }

        if ((args.length == 2 && args[0].equalsIgnoreCase("play"))
                || (args.length == 3 && args[0].equalsIgnoreCase("force"))) {
            // Suggest song filenames
            List<String> matches = new ArrayList<>();
            String prefix = args[args.length - 1].toLowerCase();
            for (String name : musicManager.getAvailableSongs()) {
                if (name.toLowerCase().startsWith(prefix)) {
                    matches.add(name);
                }
            }
            return matches;
        }

        return new ArrayList<>();
    }
}
