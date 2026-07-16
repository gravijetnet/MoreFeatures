package net.gravijet.morefeatures.link;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

/**
 * Takes a command label off whoever currently owns it and gives it to us.
 *
 * Sync declares no commands in its plugin.yml — it registers /link and /unlink at
 * runtime through its own command framework — so load order in plugin.yml cannot
 * win this argument. The only thing that does is reaching into the live
 * CommandMap after Sync has registered (softdepend: Sync is what makes us enable
 * second) and replacing what it put there.
 *
 * All of it is reflection, on purpose: this plugin compiles against the 1.8.8 API
 * and runs on modern Paper, and getCommandMap, getKnownCommands and
 * PluginCommand's constructor are reachable from neither.
 *
 * Every failure here is survivable and none of them is fatal. If the takeover
 * cannot happen, Sync's /link keeps working and ours is still reachable as
 * /morefeatures:link — a worse outcome than intended, but not a broken server.
 */
public final class CommandOverride {

    private CommandOverride() {
    }

    public static boolean take(Plugin plugin, String label, CommandExecutor executor,
                               TabCompleter completer, String description, String usage) {
        try {
            CommandMap map = commandMap();
            if (map == null) {
                plugin.getLogger().warning("No command map reachable — /" + label + " stays with whoever has it.");
                return false;
            }

            Map<String, Command> known = knownCommands(map);
            if (known != null) {
                // The bare label, and the namespaced aliases Bukkit also files a
                // command under (sync:link and friends). Ours is registered after
                // this, so removing our own namespaced entry costs nothing.
                known.remove(label.toLowerCase());
                for (Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator(); it.hasNext(); ) {
                    if (it.next().getKey().toLowerCase().endsWith(":" + label.toLowerCase())) it.remove();
                }
            }

            // plugin.yml already made one of these for us, complete with its
            // permission; only fall back to building one if it did not.
            PluginCommand command = plugin instanceof JavaPlugin
                    ? ((JavaPlugin) plugin).getCommand(label)
                    : null;
            if (command == null) command = newPluginCommand(label, plugin);

            command.setExecutor(executor);
            if (completer != null) command.setTabCompleter(completer);
            if (description != null) command.setDescription(description);
            if (usage != null) command.setUsage(usage);

            map.register(plugin.getName().toLowerCase(), command);
            syncCommands();
            plugin.getLogger().info("/" + label + " is ours now.");
            return true;
        } catch (Throwable t) {
            // Throwable, not Exception: a reflective reach into internals can
            // throw NoSuchMethodError or InaccessibleObjectException on a version
            // that moved something, and a failed takeover must not take the whole
            // plugin down with it.
            plugin.getLogger().log(Level.WARNING,
                    "Could not take over /" + label + " — Sync's version stays; ours is at /"
                            + plugin.getName().toLowerCase() + ":" + label + ".", t);
            return false;
        }
    }

    private static CommandMap commandMap() throws Exception {
        // Paper exposes this on Server directly these days; CraftServer has had
        // it since forever. Try the polite way first.
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            m.setAccessible(true);
            return (CommandMap) m.invoke(Bukkit.getServer());
        } catch (NoSuchMethodException ignored) {
            // older or non-Paper: fall through to the field
        }
        Field f = findField(Bukkit.getServer().getClass(), "commandMap");
        if (f == null) return null;
        f.setAccessible(true);
        return (CommandMap) f.get(Bukkit.getServer());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap map) throws Exception {
        try {
            Method m = map.getClass().getMethod("getKnownCommands");
            m.setAccessible(true);
            return (Map<String, Command>) m.invoke(map);
        } catch (NoSuchMethodException ignored) {
            // pre-1.13: the field is there, the getter is not
        }
        Field f = findField(map.getClass(), "knownCommands");
        if (f == null) return null;
        f.setAccessible(true);
        return (Map<String, Command>) f.get(map);
    }

    /** Fields live on SimpleCommandMap, and what we hold is often a subclass of it. */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static PluginCommand newPluginCommand(String name, Plugin plugin) throws Exception {
        Constructor<PluginCommand> c = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        c.setAccessible(true);
        return c.newInstance(name, plugin);
    }

    /**
     * Pushes the command tree back to connected clients. Without it, anyone
     * already online keeps Sync's tab-completion for /link until they reconnect.
     */
    private static void syncCommands() {
        try {
            Method m = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            m.setAccessible(true);
            m.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {
            // 1.8 has no client-side command tree to refresh, and nothing else
            // here depends on this working.
        }
    }
}
