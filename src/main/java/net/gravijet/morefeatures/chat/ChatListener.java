package net.gravijet.morefeatures.chat;

import me.clip.placeholderapi.PlaceholderAPI;
import net.gravijet.morefeatures.display.DisplayConfig;
import net.gravijet.morefeatures.display.DisplayResolver;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the chat format outside a bedwars game.
 *
 * Inside an arena the event is left exactly as it arrives. MBedwars splits chat
 * into team and spectator channels there and decides who receives what; putting
 * a lobby format over the top would render a team message as though it were
 * public, which is the opposite of what those channels are for.
 *
 * Runs at HIGHEST rather than MONITOR so it wins against anything else setting a
 * format, while still leaving MONITOR free for the loggers that need to see the
 * final result. Cancelled events are skipped, which is what keeps a Phoenix mute
 * a mute.
 */
public class ChatListener implements Listener {

    private final DisplayResolver resolver;
    private final DisplayConfig   config;
    private final Logger          logger;

    public ChatListener(DisplayResolver resolver, DisplayConfig config, Logger logger) {
        this.resolver = resolver;
        this.config   = config;
        this.logger   = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        try {
            if (resolver.inGame(player)) return;

            if (player.hasPermission(config.getChatColorPermission())) {
                event.setMessage(ChatColor.translateAlternateColorCodes('&', event.getMessage()));
            }
            event.setFormat(buildFormat(player));
        } catch (Throwable t) {
            // A broken format string is a reason to lose the formatting, not the
            // message. Leaving the event untouched sends it in Bukkit's default
            // shape rather than eating it.
            logger.log(Level.WARNING, "Could not apply the chat format — sending unformatted.", t);
        }
    }

    /**
     * Turns the configured format into a Bukkit chat format.
     *
     * The order below is the whole point of the method:
     *
     * 1. our own tokens, so a rank prefix is present before anything reads it
     * 2. PlaceholderAPI, for %phoenix_*% and %pxcosmetics_*%
     * 3. colour codes
     * 4. escape every remaining %, because Bukkit runs the format through
     *    String.format and an unresolved placeholder like %pxcosmetics_x% would
     *    otherwise throw and swallow the message
     * 5. only now the message slot
     *
     * The message is never part of steps 1-4. It goes in as a String.format
     * argument, so nothing a player types is ever read as a placeholder, a
     * colour code or a format specifier.
     */
    private String buildFormat(Player player) {
        return assemble(config.getChatFormat(),
                resolver.prefix(player),
                resolver.suffix(player),
                resolver.tag(player),
                player.getName(),
                raw -> PlaceholderAPI.setPlaceholders(player, raw));
    }

    /**
     * The assembly itself, with PlaceholderAPI passed in so the ordering can be
     * exercised without a server.
     */
    static String assemble(String format, String prefix, String suffix, String tag,
                           String name, UnaryOperator<String> placeholders) {
        String result = format
                .replace("<prefix>", prefix)
                .replace("<suffix>", suffix)
                .replace("<tag>",    tag)
                .replace("<name>",   name);

        result = placeholders.apply(result);
        result = ChatColor.translateAlternateColorCodes('&', result);
        result = result.replace("%", "%%");

        return result.replace("<message>", "%2$s");
    }
}
