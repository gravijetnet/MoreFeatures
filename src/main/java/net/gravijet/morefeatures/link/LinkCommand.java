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
import java.util.regex.Pattern;

/**
 * /link &lt;code&gt; — take the code example.invalid gave you and prove this account is
 * yours.
 *
 * This replaces Sync's /link, which pointed at a Nova panel on localhost:3000.
 */
public class LinkCommand implements CommandExecutor, TabCompleter {

    private static final String SITE = "https://example.invalid/link";

    // Exactly what the website mints: nothing else is worth a database round
    // trip, and a "code" full of punctuation is somebody trying it on.
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9]{" + LinkStore.CODE_LENGTH + "}$");

    private final Plugin plugin;
    private final LinkStore store;

    public LinkCommand(Plugin plugin, LinkStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly a player can link an account.");
            return true;
        }
        final Player player = (Player) sender;

        if (args.length != 1) {
            player.sendMessage("§7Link your Minecraft account to Discord:");
            player.sendMessage("§7 1. Sign in at §b" + SITE + " §7with Discord");
            player.sendMessage("§7 2. It gives you a code");
            player.sendMessage("§7 3. Type §f/link <code> §7here");
            return true;
        }

        final String code = args[0].trim();
        if (!CODE.matcher(code).matches()) {
            player.sendMessage("§cThat is not a code. It is "
                    + LinkStore.CODE_LENGTH + " letters and numbers, from §b" + SITE + "§c.");
            return true;
        }

        // Off the main thread: this is several queries and a row lock, and the
        // server does not get to stop ticking while MySQL thinks about it.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            LinkStore.Outcome outcome;
            try {
                outcome = store.redeem(player.getUniqueId(), player.getName(), code.toUpperCase());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Link failed for " + player.getName(), t);
                say(player, "§cSomething broke on our side. Try again in a moment.");
                return;
            }

            switch (outcome.result) {
                case LINKED:
                    say(player, "§aLinked to Discord as §f"
                            + (outcome.discordName == null ? "your account" : outcome.discordName) + "§a.");
                    break;
                case UNKNOWN_CODE:
                    say(player, "§cNo such code. They are used once and expire — get a fresh one at §b" + SITE + "§c.");
                    break;
                case EXPIRED:
                    say(player, "§cThat code has expired. Get a new one at §b" + SITE + "§c.");
                    break;
                case ALREADY_LINKED:
                    say(player, "§cThis Minecraft account is already linked. Use §f/unlink §cfirst.");
                    break;
                case DISCORD_TAKEN:
                    say(player, "§cThat Discord account is already linked to a different Minecraft account.");
                    break;
                default:
                    say(player, "§cSomething broke on our side. Try again in a moment.");
            }
        });
        return true;
    }

    /** Chat has to happen on the main thread; the database work does not. */
    private void say(Player player, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.sendMessage(message);
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Nothing to suggest: the one argument is a secret the server should not
        // be helping anyone guess.
        return Collections.emptyList();
    }
}
