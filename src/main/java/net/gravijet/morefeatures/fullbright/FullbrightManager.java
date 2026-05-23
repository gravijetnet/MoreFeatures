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

    private static final int CHUNKS_PER_TICK = 20; // process in batches to avoid stalling the main thread

    /** Called on plugin enable / toggle — processes every already-loaded chunk in batches. */
    public void relightAllLoaded() {
        java.util.List<Chunk> toProcess = new java.util.ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            toProcess.addAll(java.util.Arrays.asList(world.getLoadedChunks()));
        }

        if (toProcess.isEmpty()) return;
        final boolean snap = enabled;
        scheduleBatch(toProcess, 0, snap, toProcess.size());
    }

    private void scheduleBatch(java.util.List<Chunk> chunks, int offset, boolean lightEnabled, int total) {
        if (offset >= chunks.size()) {
            logger.info("Fullbright " + (lightEnabled ? "enabled" : "disabled")
                    + " — refreshed " + total + " loaded chunk(s).");
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int end = Math.min(offset + CHUNKS_PER_TICK, chunks.size());
            for (int i = offset; i < end; i++) {
                Chunk chunk = chunks.get(i);
                // Force-load the chunk briefly so we can apply or revert lighting on it.
                // This ensures chunks that unloaded between collection and this batch are
                // still correctly reverted when fullbright is disabled (M3).
                boolean wasLoaded = chunk.isLoaded();
                if (!wasLoaded) chunk.load(false); // load without generating new terrain
                try {
                    if (lightEnabled) applyMaxLight(chunk);
                    else revertLight(chunk);
                    chunk.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
                } finally {
                    // Unload again if we loaded it ourselves, to avoid inflating memory.
                    if (!wasLoaded) chunk.unload(false);
                }
            }
            scheduleBatch(chunks, end, lightEnabled, total);
        });
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

    private static void revertLight(Chunk chunk) {
        // initLighting() recomputes sky/block light from scratch for the chunk.
        ((CraftChunk) chunk).getHandle().initLighting();
    }
}
