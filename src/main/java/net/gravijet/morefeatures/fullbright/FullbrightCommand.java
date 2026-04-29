package net.gravijet.morefeatures.fullbright;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * /fullbright [on|off|toggle|status]
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

        String sub = args.length > 0 ? args[0].toLowerCase() : "toggle";

        switch (sub) {
            case "on":
                fullbrightManager.setEnabled(true);
                sender.sendMessage("§aFullbright §2enabled§a.");
                break;
            case "off":
                fullbrightManager.setEnabled(false);
                sender.sendMessage("§aFullbright §cdisabled§a.");
                break;
            case "toggle":
                boolean next = !fullbrightManager.isEnabled();
                fullbrightManager.setEnabled(next);
                sender.sendMessage("§aFullbright " + (next ? "§2enabled" : "§cdisabled") + "§a.");
                break;
            case "status":
                sender.sendMessage("§7Fullbright is currently "
                        + (fullbrightManager.isEnabled() ? "§2enabled" : "§cdisabled") + "§7.");
                break;
            default:
                sender.sendMessage("§cUsage: /fullbright [on|off|toggle|status]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) matches.add(sub);
            }
            return matches;
        }
        return new ArrayList<>();
    }
}
