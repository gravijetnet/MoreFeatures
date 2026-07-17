package net.gravijet.morefeatures.fullbright;

import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.NibbleArray;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftChunk;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Two independent fullbright modes:
 *
 * Server-wide — applies maximum sky-light and block-light to every chunk section
 * via NMS. Relighting is done once per chunk load; already-loaded chunks are relit
 * on enable and reverted on disable/toggle-off.
 *
 * Per player — an endless night-vision effect on one player only, which admins can
 * hand out with /fullbright &lt;player&gt;. Held in memory and never persisted: the set
 * dies with the server, and the effect is stripped on quit so it cannot be saved
 * into playerdata and outlive a restart.
 */
public class FullbrightManager {

    private static final byte[] MAX_LIGHT = new byte[2048];

    static {
        Arrays.fill(MAX_LIGHT, (byte) 0xFF);
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Set<UUID> personal = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;
    // BUG-26 fix: track whether fullbright has ever been switched on so that
    // calling setEnabled(false) before any enable does not revert lighting on
    // chunks that were never modified by this plugin.
    private volatile boolean everEnabled;

    public FullbrightManager(JavaPlugin plugin, boolean enabled) {
        this.plugin       = plugin;
        this.logger       = plugin.getLogger();
        this.enabled      = enabled;
        this.everEnabled  = enabled;
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    /** Toggles fullbright and relights/refreshes all loaded chunks. */
    public void setEnabled(boolean value) {
        if (value) everEnabled = true;
        this.enabled = value;
        relightAllLoaded();
    }

    // -----------------------------------------------------------------
    //  Per-player fullbright
    // -----------------------------------------------------------------

    public boolean isPersonalEnabled(UUID uuid) {
        return personal.contains(uuid);
    }

    /** Turns per-player fullbright on or off for one player. Main thread only. */
    public void setPersonalEnabled(Player player, boolean value) {
        if (value) {
            personal.add(player.getUniqueId());
            applyNightVision(player);
        } else {
            personal.remove(player.getUniqueId());
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    /** Re-applies the effect to a player who reconnected within the same server run. */
    public void restorePersonal(Player player) {
        if (personal.contains(player.getUniqueId())) {
            applyNightVision(player);
        }
    }

    /**
     * Strips the effect on quit while keeping the player flagged, so a reconnect
     * restores it but a shutdown does not leave night vision saved in playerdata.
     */
    public void suspendPersonal(Player player) {
        if (personal.contains(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    /** Drops every per-player grant. Called on disable so nothing survives a restart. */
    public void clearAllPersonal() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (personal.contains(player.getUniqueId())) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        }
        personal.clear();
    }

    private static void applyNightVision(Player player) {
        // ambient = true keeps the screen tint faint, particles = false hides the swirls.
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, true, false), true);
    }

    // -----------------------------------------------------------------
    //  Server-wide fullbright
    // -----------------------------------------------------------------

    /** Applies max lighting to all sections of a freshly loaded chunk. */
    public void relightChunk(Chunk chunk) {
        if (!enabled) return;
        applyMaxLight(chunk);
        chunk.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
    }

    private static final int CHUNKS_PER_TICK = 20; // process in batches to avoid stalling the main thread

    /** Called on plugin enable / toggle — processes every already-loaded chunk in batches. */
    public void relightAllLoaded() {
        // BUG-26 fix: if fullbright has never been enabled there is nothing to revert;
        // calling revertLight on unmodified chunks wastes CPU and causes lighting flicker.
        if (!enabled && !everEnabled) return;

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
                if (lightEnabled) {
                    // BUG-25 fix (enable path): only process chunks that are still loaded.
                    // Chunks that unloaded between snapshot and this batch will be lit by
                    // onChunkLoad when they next load, so skip them here to prevent double
                    // lighting + double refreshChunk packet spam.
                    if (!chunk.isLoaded()) continue;
                    applyMaxLight(chunk);
                    chunk.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
                } else {
                    // Disable path: force-load so we can revert lighting even if the chunk
                    // unloaded between snapshot collection and this batch.
                    boolean wasLoaded = chunk.isLoaded();
                    if (!wasLoaded && !chunk.load(false)) continue;
                    try {
                        revertLight(chunk);
                        chunk.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
                    } finally {
                        if (!wasLoaded) chunk.unload(false);
                    }
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
                // BUG-24: .clone() is required — NibbleArray may store the reference
                // directly; without it, NMS could mutate MAX_LIGHT through the NibbleArray.
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
