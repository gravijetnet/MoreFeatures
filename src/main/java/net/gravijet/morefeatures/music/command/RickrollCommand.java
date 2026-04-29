package net.gravijet.morefeatures.music.command;

import net.gravijet.morefeatures.music.MusicConfig;
import net.gravijet.morefeatures.music.MusicManager;
import net.gravijet.morefeatures.music.util.TimeParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /rickroll [player] <time>
 * <p>
 * Plays "rickroll.nbs" for the sender or a targeted player.
 * If {@code <time>} is provided (e.g. "30s", "1m30s", "1h"),
 * playback stops automatically after that duration.
 * Without {@code <time>} the song plays until it ends naturally.
 */
public class RickrollCommand implements CommandExecutor, TabCompleter {

    private final MusicManager musicManager;
    private final MusicConfig musicConfig;

    public RickrollCommand(MusicManager musicManager, MusicConfig musicConfig) {
        this.musicManager = musicManager;
        this.musicConfig = musicConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        Player target;
        String timeArg;
        boolean targetingOthers;

        // ---- parse [player] and <time> ----
        if (args.length == 0) {
            // /rickroll — self, no time limit
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cConsole must specify a player: /rickroll <player> [time]");
                return true;
            }
            target = (Player) sender;
            timeArg = null;
            targetingOthers = false;
        } else if (args.length == 1) {
            // Could be /rickroll <player>  or  /rickroll <time>
            // Try to parse as time first; if that fails, treat as player
            boolean isTimeArg = false;
            try {
                TimeParser.parseSeconds(args[0]);
                isTimeArg = true;
            } catch (IllegalArgumentException ignored) {
                // Not a valid time string — treat as player name
            }

            if (isTimeArg) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cConsole must specify a player: /rickroll <player> [time]");
                    return true;
                }
                target = (Player) sender;
                timeArg = args[0];
                targetingOthers = false;
            } else {
                target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found: " + args[0]);
                    return true;
                }
                timeArg = null;
                targetingOthers = !target.equals(sender);
            }
        } else {
            // /rickroll <player> <time>
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[0]);
                return true;
            }
            timeArg = args[1];
            targetingOthers = !target.equals(sender);
        }

        // ---- permission check ----
        if (targetingOthers) {
            if (!sender.hasPermission("morefeatures.rickroll.others")) {
                sender.sendMessage("§cYou don't have permission to rickroll other players.");
                return true;
            }
        } else {
            if (!sender.hasPermission("morefeatures.rickroll")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }
        }

        // ---- parse the time if provided ----
        long stopAfterSeconds = -1;
        if (timeArg != null) {
            try {
                stopAfterSeconds = TimeParser.parseSeconds(timeArg);
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§c" + e.getMessage());
                return true;
            }
        }

        // ---- play the song ----
        String rickrollFile = musicConfig.getRickrollFile();
        boolean success = musicManager.playSong(target, rickrollFile);
        if (!success) {
            sender.sendMessage("§cRickroll song not available. "
                    + "Make sure '" + rickrollFile + "' exists in the songs folder.");
            return true;
        }

        if (targetingOthers) {
            sender.sendMessage("§aRickrolling " + target.getName() + "...");
        } else if (!sender.equals(target)) {
            // Console rickrolling someone
            sender.sendMessage("§aRickrolling " + target.getName() + "...");
        }
        // When self-targeting, the song just starts — no message needed

        // ---- schedule auto-stop if time limit is set ----
        if (stopAfterSeconds > 0) {
            long delayTicks = stopAfterSeconds * 20L; // seconds → ticks
            Bukkit.getScheduler().runTaskLater(
                    musicManager.getPlugin(),
                    () -> {
                        musicManager.stopSong(target);
                        if (target.isOnline()) {
                            target.sendMessage("§cThe rickroll has ended. (time limit reached)");
                        }
                    },
                    delayTicks
            );
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            // Suggest online player names
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        if (args.length == 2) {
            // Suggest common time formats
            List<String> times = new ArrayList<>();
            for (String t : new String[]{"30s", "1m", "1m30s", "5m", "1h"}) {
                if (t.startsWith(args[1].toLowerCase())) {
                    times.add(t);
                }
            }
            return times;
        }
        return new ArrayList<>();
    }
}
