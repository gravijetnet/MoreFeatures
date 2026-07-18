package net.gravijet.morefeatures.display;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Publishes {@link DisplayResolver} to PlaceholderAPI as {@code %morefeatures_*%}.
 *
 * TAB owns the tablist and the nametags on this server, and it owns them by
 * sending packets — a second plugin writing scoreboard teams behind its back
 * loses the race, which is what a name flickering white with no prefix looks
 * like. So MoreFeatures does not fight TAB for the rendering. It answers the
 * question TAB is asking, and TAB keeps drawing.
 *
 * Placeholders:
 * <ul>
 *   <li>{@code %morefeatures_sortweight%} — numeric, for PLACEHOLDER_HIGH_TO_LOW</li>
 *   <li>{@code %morefeatures_sortkey%}   — string, for PLACEHOLDER_A_TO_Z</li>
 *   <li>{@code %morefeatures_prefix%}    — rank prefix, never empty</li>
 *   <li>{@code %morefeatures_tabprefix%} — playerlist prefix, never empty</li>
 *   <li>{@code %morefeatures_suffix%}    — rank suffix</li>
 *   <li>{@code %morefeatures_namecolor%} — team colour in a game, rank colour outside</li>
 *   <li>{@code %morefeatures_tag%}       — the player's Phoenix tag</li>
 *   <li>{@code %morefeatures_teamcolor%} — team colour, empty outside a game</li>
 *   <li>{@code %morefeatures_ingame%}    — true/false</li>
 * </ul>
 */
public class DisplayExpansion extends PlaceholderExpansion {

    private final JavaPlugin      plugin;
    private final DisplayResolver resolver;

    public DisplayExpansion(JavaPlugin plugin, DisplayResolver resolver) {
        this.plugin   = plugin;
        this.resolver = resolver;
    }

    @Override
    public String getIdentifier() {
        return "morefeatures";
    }

    @Override
    public String getAuthor() {
        return "gravijet";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /**
     * Survive a /papi reload. Without this the expansion is dropped and every
     * placeholder starts resolving to nothing — which would empty out the
     * tablist prefixes and flatten the sorting until the next restart.
     */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";

        switch (params.toLowerCase()) {
            case "sortweight": return resolver.sortWeight(player);
            case "sortkey":   return resolver.sortKey(player);
            case "prefix":    return resolver.prefix(player);
            case "tabprefix": return resolver.tabPrefix(player);
            case "suffix":    return resolver.suffix(player);
            case "namecolor": return resolver.nameColor(player);
            case "tag":       return resolver.tag(player);
            case "teamcolor": return resolver.teamColor(player);
            case "ingame":    return String.valueOf(resolver.inGame(player));
            // null, not "", so PlaceholderAPI leaves an unknown placeholder
            // visible instead of silently swallowing a typo in the TAB config.
            default:          return null;
        }
    }
}
