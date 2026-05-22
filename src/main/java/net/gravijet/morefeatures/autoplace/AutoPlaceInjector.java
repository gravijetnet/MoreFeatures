package net.gravijet.morefeatures.autoplace;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of AutoPlaceDecoder instances — one per online player.
 */
public class AutoPlaceInjector {

    private static final String HANDLER_NAME = "morefeatures-autoplace";

    private final JavaPlugin plugin;
    private final AutoPlaceConfig config;
    private final Map<Player, AutoPlaceDecoder> decoders = new ConcurrentHashMap<>();

    public AutoPlaceInjector(JavaPlugin plugin, AutoPlaceConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void inject(Player player) {
        // Re-injecting (reload, or onEnable looping online players while a join
        // fires) would otherwise throw "Duplicate handler name" — clear first.
        uninject(player);

        AutoPlaceDecoder decoder = new AutoPlaceDecoder(player, plugin, config);
        decoders.put(player, decoder);

        // BUG-26: pipeline mutations must run on the channel's own event loop to avoid
        // ConcurrentModificationException when a packet arrives during insertion
        Channel channel = getChannel(player);
        if (channel.eventLoop().inEventLoop()) {
            channel.pipeline().addAfter("decoder", HANDLER_NAME, decoder);
        } else {
            channel.eventLoop().execute(() ->
                    channel.pipeline().addAfter("decoder", HANDLER_NAME, decoder));
        }
    }

    public void uninject(Player player) {
        decoders.remove(player);
        // BUG-26: same — do pipeline removal on the event loop
        Channel channel = getChannel(player);
        Runnable remove = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            remove.run();
        } else {
            channel.eventLoop().execute(remove);
        }
    }

    private static Channel getChannel(Player player) {
        return ((CraftPlayer) player)
                .getHandle()
                .playerConnection
                .networkManager
                .channel;
    }
}
