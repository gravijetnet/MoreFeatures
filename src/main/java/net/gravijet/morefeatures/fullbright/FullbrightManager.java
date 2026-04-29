package net.gravijet.morefeatures.fullbright;

import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.NibbleArray;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftChunk;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Applies maximum sky-light and block-light to every chunk section via NMS.
 * Relighting is done once per chunk load; already-loaded chunks are relit
 * on enable and reverted on disable/toggle-off.
 */
public class FullbrightManager {

    private static final byte[] MAX_LIGHT = new byte[2048];

    static {
        Arrays.fill(MAX_LIGHT, (byte) 0xFF);
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private volatile boolean enabled;

    public FullbrightManager(JavaPlugin plugin, boolean enabled) {
        this.plugin  = plugin;
        this.logger  = plugin.getLogger();
        this.enabled = enabled;
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    /** Toggles fullbright and relights/refreshes all loaded chunks. */
    public void setEnabled(boolean value) {
        this.enabled = value;
        relightAllLoaded();
    }

    /** Applies max lighting to all sections of a freshly loaded chunk. */
    public void relightChunk(Chunk chunk) {
        if (!enabled) return;
        applyMaxLight(chunk);
    }

    /** Called on plugin enable — processes every already-loaded chunk. */
    public void relightAllLoaded() {
        int total = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (enabled) {
                    applyMaxLight(chunk);
                }
                // Force the client to re-render the chunk
                world.refreshChunk(chunk.getX(), chunk.getZ());
                total++;
            }
        }
        if (total > 0) {
            logger.info("Fullbright " + (enabled ? "enabled" : "disabled")
                    + " — refreshed " + total + " loaded chunk(s).");
        }
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private static void applyMaxLight(Chunk chunk) {
        net.minecraft.server.v1_8_R3.Chunk nmsChunk = ((CraftChunk) chunk).getHandle();
        for (ChunkSection section : nmsChunk.getSections()) {
            if (section != null) {
                section.b(new NibbleArray(MAX_LIGHT.clone())); // sky-light
                section.a(new NibbleArray(MAX_LIGHT.clone())); // block-light
            }
        }
    }
}
