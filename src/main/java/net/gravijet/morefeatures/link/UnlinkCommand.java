package net.gravijet.morefeatures.link;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * /unlink — cut this account loose from its Discord.
 *
 * This replaces Sync's /unlink.
 */
public class UnlinkCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final LinkStore store;

    public UnlinkCommand(Plugin plugin, LinkStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly a player can unlink an account.");
            return true;
        }
        final Player player = (Player) sender;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Named before it is gone: after the delete there is nothing left
                // to tell them what they just gave up.
                String discord = store.linkedDiscord(player.getUniqueId());
                if (discord == null) {
                    say(player, "§cThis account is not linked to anything.");
                    return;
                }
                if (store.unlink(player.getUniqueId())) {
                    say(player, "§aUnlinked from §f" + discord + "§a. Link again any time at §bhttps://example.invalid/link§a.");
                } else {
                    say(player, "§cThis account is not linked to anything.");
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Unlink failed for " + player.getName(), t);
                say(player, "§cSomething broke on our side. Try again in a moment.");
            }
        });
        return true;
    }

    private void say(Player player, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.sendMessage(message);
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
