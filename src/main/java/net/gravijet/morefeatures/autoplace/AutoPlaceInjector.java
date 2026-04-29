package net.gravijet.morefeatures.autoplace;

import io.netty.channel.ChannelPipeline;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the lifecycle of AutoPlaceDecoder instances — one per online player.
 */
public class AutoPlaceInjector {

    private static final String HANDLER_NAME = "morefeatures-autoplace";

    private final JavaPlugin plugin;
    private final AutoPlaceConfig config;
    private final Map<Player, AutoPlaceDecoder> decoders = new HashMap<>();

    public AutoPlaceInjector(JavaPlugin plugin, AutoPlaceConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void inject(Player player) {
        AutoPlaceDecoder decoder = new AutoPlaceDecoder(player, plugin, config);
        decoders.put(player, decoder);
        getPipeline(player).addAfter("decoder", HANDLER_NAME, decoder);
    }

    public void uninject(Player player) {
        decoders.remove(player);
        ChannelPipeline pipeline = getPipeline(player);
        if (pipeline.get(HANDLER_NAME) != null) {
            pipeline.remove(HANDLER_NAME);
        }
    }

    private static ChannelPipeline getPipeline(Player player) {
        return ((CraftPlayer) player)
                .getHandle()
                .playerConnection
                .networkManager
                .channel
                .pipeline();
    }
}
