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
        // Remove from the pipeline before removing from the map so that the event-loop
        // removal task sees the correct state and a concurrent inject() cannot race
        // past the guard and then have its handler removed by a stale removal task (H6).
        uninject(player);

        AutoPlaceDecoder decoder = new AutoPlaceDecoder(player, plugin, config);

        Channel channel = getChannel(player);
        if (channel.eventLoop().inEventLoop()) {
            channel.pipeline().addAfter("decoder", HANDLER_NAME, decoder);
            decoders.put(player, decoder);
        } else {
            channel.eventLoop().execute(() -> {
                channel.pipeline().addAfter("decoder", HANDLER_NAME, decoder);
                // Store in map only after the handler is actually in the pipeline so
                // uninject() cannot find the entry before the pipeline add completes.
                decoders.put(player, decoder);
            });
        }
    }

    public void uninject(Player player) {
        // Remove from map first so no new packets are dispatched to the old decoder
        // after we've started the removal.
        AutoPlaceDecoder removed = decoders.remove(player);
        if (removed == null) return; // nothing to remove

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

    /** Returns the active decoder for a player, or null if not injected. */
    public AutoPlaceDecoder getDecoder(Player player) {
        return decoders.get(player);
    }

    private static Channel getChannel(Player player) {
        return ((CraftPlayer) player)
                .getHandle()
                .playerConnection
                .networkManager
                .channel;
    }
}
