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
        AutoPlaceDecoder decoder = new AutoPlaceDecoder(player, plugin, config);
        Channel channel = getChannel(player);

        if (channel.eventLoop().inEventLoop()) {
            // Already on the event loop: remove old handler synchronously then add the new one.
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
                decoders.remove(player);
            }
            pipeline.addAfter("decoder", HANDLER_NAME, decoder);
            decoders.put(player, decoder);
        } else {
            // BUG-09/10 fix: submit removal and add as two sequential tasks on the same
            // single-threaded event loop. Tasks submitted to a single-threaded EventLoop
            // execute strictly in submission order, so the add is guaranteed to run after
            // the remove — no Future.get() needed, which would deadlock on the same thread.
            channel.eventLoop().submit(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    pipeline.remove(HANDLER_NAME);
                    decoders.remove(player);
                }
            });
            channel.eventLoop().submit(() -> {
                channel.pipeline().addAfter("decoder", HANDLER_NAME, decoder);
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
        // BUG-09/10 fix: use submit() (not execute()) so the returned Future can be used
        // by a subsequent inject() to schedule the add *after* this removal completes,
        // preventing a "duplicate handler name" Netty exception on rapid reinjects.
        Runnable remove = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            remove.run();
        } else {
            channel.eventLoop().submit(remove);
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
