package net.gravijet.morefeatures.fullbright;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * /fullbright [on|off|toggle|status]   — server-wide, via NMS chunk lighting
 * /fullbright <player> [on|off|toggle] — one player only, via night vision
 */
public class FullbrightCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("on", "off", "toggle", "status");

    private final FullbrightManager fullbrightManager;

    public FullbrightCommand(FullbrightManager fullbrightManager) {
        this.fullbrightManager = fullbrightManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("morefeatures.fullbright")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        // Anything that is not one of the global subcommands is read as a player name,
        // which keeps /fullbright on|off|toggle|status meaning exactly what it used to.
        if (args.length > 0 && !SUBCOMMANDS.contains(args[0].toLowerCase())) {
            return handlePersonal(sender, args);
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "toggle";

        switch (sub) {
            case "on":
                fullbrightManager.setEnabled(true);
                sender.sendMessage("§aFullbright §2enabled§a for the whole server.");
                break;
            case "off":
                fullbrightManager.setEnabled(false);
                sender.sendMessage("§aFullbright §cdisabled§a for the whole server.");
                break;
            case "toggle":
                boolean next = !fullbrightManager.isEnabled();
                fullbrightManager.setEnabled(next);
                sender.sendMessage("§aServer-wide fullbright "
                        + (next ? "§2enabled" : "§cdisabled") + "§a.");
                break;
            case "status":
                sender.sendMessage("§7Server-wide fullbright is currently "
                        + (fullbrightManager.isEnabled() ? "§2enabled" : "§cdisabled") + "§7.");
                break;
            default:
                sender.sendMessage("§cUnknown option. Usage: /fullbright [on|off|toggle|status] "
                        + "or /fullbright <player> [on|off|toggle]");
        }
        return true;
    }

    private boolean handlePersonal(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer §f" + args[0] + " §cis not online.");
            return true;
        }
        boolean self = (sender instanceof Player)
                && ((Player) sender).getUniqueId().equals(target.getUniqueId());
        if (!self && !sender.hasPermission("morefeatures.fullbright.others")) {
            sender.sendMessage("§cYou may only change your own fullbright.");
            return true;
        }

        String sub = args.length > 1 ? args[1].toLowerCase() : "toggle";
        boolean value;
        switch (sub) {
            case "on":     value = true;  break;
            case "off":    value = false; break;
            case "toggle": value = !fullbrightManager.isPersonalEnabled(target.getUniqueId()); break;
            case "status":
                sender.sendMessage("§7Fullbright for §f" + target.getName() + " §7is currently "
                        + (fullbrightManager.isPersonalEnabled(target.getUniqueId())
                           ? "§2enabled" : "§cdisabled") + "§7.");
                return true;
            default:
                sender.sendMessage("§cUnknown option. Usage: /fullbright <player> [on|off|toggle|status]");
                return true;
        }

        fullbrightManager.setPersonalEnabled(target, value);

        String state = value ? "§2enabled" : "§cdisabled";
        if (self) {
            sender.sendMessage("§aFullbright " + state + "§a for you.");
        } else {
            sender.sendMessage("§aFullbright " + state + "§a for §f" + target.getName() + "§a.");
            target.sendMessage("§aFullbright was " + state + "§a for you by §f" + sender.getName() + "§a.");
        }
        // Worth saying out loud: the grant is gone after a restart, and while the
        // server-wide mode is on it changes nothing the player can see.
        if (value && fullbrightManager.isEnabled()) {
            sender.sendMessage("§7Note: server-wide fullbright is already on, so this has no visible effect.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> matches = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) matches.add(sub);
            }
            if (sender.hasPermission("morefeatures.fullbright.others")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(prefix)) matches.add(player.getName());
                }
            }
            return matches;
        }

        if (args.length == 2 && !SUBCOMMANDS.contains(args[0].toLowerCase())) {
            String prefix = args[1].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) matches.add(sub);
            }
            return matches;
        }

        return matches;
    }
}
