package net.gravijet.morefeatures.autoplace;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * Netty channel handler that sits in the player's pipeline and inspects
 * PacketPlayInBlockPlace / PacketPlayInFlying packets to detect AutoPlace.
 *
 * Detection principle: a legitimate block placement is always preceded (and
 * followed) by at least one flying packet. Two consecutive block-place packets
 * with no flying packet in between means the client is automating placements.
 *
 * Thread-safety: all mutable fields in this class are written and read
 * exclusively on the player's Netty I/O thread, except for the volatile fields
 * (bypassPermission, gameMode, worldServer) which are written on the main thread
 * and read on the Netty thread.
 */
public class AutoPlaceDecoder extends ChannelDuplexHandler {

    // Player bounding-box constants matching NMS EntityHuman
    private static final double HEIGHT     = 1.8;
    private static final double HALF_WIDTH = 0.6 / 2.0;

    // Size of the FastPlace sliding window
    private static final int PLACE_WINDOW_SIZE = 10;

    // NMS protocol value for "right-click air / item use" — not a real face direction
    private static final int FACE_ITEM_USE = 255;

    private static final Set<Material> INTERACTABLE_BLOCKS = EnumSet.of(
            Material.DISPENSER,
            Material.NOTE_BLOCK,
            Material.CHEST,
            Material.WORKBENCH,
            Material.FURNACE,
            Material.BURNING_FURNACE,
            Material.LEVER,
            Material.STONE_BUTTON,
            Material.FENCE,
            Material.TRAP_DOOR,
            Material.FENCE_GATE,
            Material.NETHER_FENCE,
            Material.ENCHANTMENT_TABLE,
            Material.ENDER_CHEST,
            Material.BEACON,
            Material.WOOD_BUTTON,
            Material.ANVIL,
            Material.TRAPPED_CHEST,
            Material.DAYLIGHT_DETECTOR,
            Material.DAYLIGHT_DETECTOR_INVERTED,
            Material.HOPPER,
            Material.DROPPER,
            Material.IRON_TRAPDOOR,
            Material.SPRUCE_FENCE,
            Material.BIRCH_FENCE,
            Material.JUNGLE_FENCE,
            Material.DARK_OAK_FENCE,
            Material.ACACIA_FENCE,
            Material.SPRUCE_FENCE_GATE,
            Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE,
            Material.DARK_OAK_FENCE_GATE,
            Material.ACACIA_FENCE_GATE,
            Material.SIGN_POST,
            Material.WALL_SIGN,
            Material.WOODEN_DOOR,
            Material.IRON_DOOR_BLOCK,
            Material.CAKE_BLOCK,
            Material.BED_BLOCK,
            Material.DIODE_BLOCK_ON,
            Material.DIODE_BLOCK_OFF,
            Material.BREWING_STAND,
            Material.CAULDRON,
            Material.ITEM_FRAME,
            Material.FLOWER_POT,
            Material.REDSTONE_COMPARATOR,
            Material.REDSTONE_COMPARATOR_OFF,
            Material.REDSTONE_COMPARATOR_ON,
            Material.SPRUCE_DOOR,
            Material.BIRCH_DOOR,
            Material.JUNGLE_DOOR,
            Material.ACACIA_DOOR,
            Material.DARK_OAK_DOOR,
            Material.COMMAND,
            Material.VINE
    );

    private final Player player;
    // Resolved once at construction; safe to hold — NMS handle never changes for a session.
    private final EntityPlayer entityPlayer;
    private final JavaPlugin plugin;
    private final AutoPlaceConfig config;

    // Volatile: written on the main thread (via updatePermission / updateGameMode /
    // updateWorld), read on the Netty I/O thread. This avoids calling Bukkit API
    // from the Netty thread (H3, H4, H5).
    private volatile boolean bypassPermission;
    private volatile GameMode gameMode;
    private volatile WorldServer worldServer;

    // Per-player mutable state — only ever touched by the Netty I/O thread for this player.
    // BUG-06 fix: null initial value so position (0,0,0) is never falsely deduplicated.
    private BlockPosition requestedBlock = null;

    // Replaced Bukkit Location with plain doubles to avoid sharing a mutable Bukkit
    // object between the Netty I/O thread and the Bukkit main thread.
    private double locX, locY, locZ;
    private float  locYaw, locPitch;

    private boolean sentBlock = false;

    // FastPlace detection: ring-buffer of the last PLACE_WINDOW_SIZE placement timestamps (ms)
    private final long[] placeTimes = new long[PLACE_WINDOW_SIZE];
    private int placeTimeIndex = 0;

    private long lastFlagTime = 0;
    private static final long FLAG_DECAY_MS = 10_000L;

    private int flags = 0;

    // One-shot guard: punishment fires at most once per session.
    // BUG-07 fix: volatile so main-thread reads (e.g. future inspection) see the Netty write.
    private volatile boolean punished = false;

    // Must be called from the main thread (e.g. AutoPlaceListener.onJoin or inject()).
    public AutoPlaceDecoder(Player player, JavaPlugin plugin, AutoPlaceConfig config) {
        this.player       = player;
        this.entityPlayer = ((CraftPlayer) player).getHandle();
        this.plugin       = plugin;
        this.config       = config;

        // Snapshot main-thread state into volatile fields.
        this.bypassPermission = player.hasPermission("morefeatures.autoplace.bypass");
        this.gameMode         = player.getGameMode();
        this.worldServer      = ((CraftWorld) player.getWorld()).getHandle();

        Location loc  = player.getLocation();
        if (loc != null) {
            this.locX     = loc.getX();
            this.locY     = loc.getY();
            this.locZ     = loc.getZ();
            this.locYaw   = loc.getYaw();
            this.locPitch = loc.getPitch();
        }
    }

    // -----------------------------------------------------------------
    //  Main-thread update hooks (called from AutoPlaceListener)
    // -----------------------------------------------------------------

    /** Call from the main thread when the player's permissions change. */
    public void updatePermission() {
        this.bypassPermission = player.hasPermission("morefeatures.autoplace.bypass");
    }

    /** Call from the main thread on PlayerGameModeChangeEvent. */
    public void updateGameMode(GameMode gm) {
        this.gameMode = gm;
    }

    /** Call from the main thread on PlayerChangedWorldEvent. */
    public void updateWorld() {
        this.worldServer = ((CraftWorld) player.getWorld()).getHandle();
    }

    // -----------------------------------------------------------------
    //  Netty entry point
    // -----------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Packet) {
            boolean allow = handlePacket((Packet<?>) msg);
            if (!allow) return;
        }
        super.channelRead(ctx, msg);
    }

    // -----------------------------------------------------------------
    //  Packet dispatch
    // -----------------------------------------------------------------

    private boolean handlePacket(Packet<?> packet) {
        if (packet instanceof PacketPlayInBlockPlace) {
            return handleBlockPlace((PacketPlayInBlockPlace) packet);
        }
        if (packet instanceof PacketPlayInFlying) {
            return handleFlying((PacketPlayInFlying) packet);
        }
        return true;
    }

    private boolean handleFlying(PacketPlayInFlying packet) {
        // BUG-04 fix: only reset sentBlock when the packet carries real movement data.
        // Empty flying packets (no position, no look) must not clear the flag — a hacked
        // client could spam them to evade AutoPlace detection.
        if (packet.g() || packet.h()) {
            sentBlock = false;
        }

        if (packet.g()) { // hasPos
            locX = packet.a();
            locY = packet.b();
            locZ = packet.c();
        }
        if (packet.h()) { // hasLook
            locYaw   = packet.d();
            locPitch = packet.e();
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleBlockPlace(PacketPlayInBlockPlace packet) {
        // Read volatile snapshots — safe from the Netty thread.
        if (bypassPermission) return true;

        GameMode gm = gameMode;
        if (gm == GameMode.ADVENTURE || gm == GameMode.SPECTATOR) return true;

        if (packet.getFace() == FACE_ITEM_USE) return true;

        ItemStack held = packet.getItemStack();
        if (held == null || held.getItem() == null) return true;

        if (!(held.getItem() instanceof ItemBlock)) return true;
        if (held.getItem() instanceof ItemStep) return true;

        // Use the cached volatile worldServer — written on the main thread via updateWorld().
        WorldServer ws = worldServer;
        BlockPosition position = packet.a();

        // entityPlayer.isSneaking() reads NMS state written by PacketPlayInEntityAction,
        // which is processed on this same Netty I/O thread — safe to call here.
        if (isTargetInteractable(ws, position) && !entityPlayer.isSneaking()) return true;

        EnumDirection direction = EnumDirection.fromType1(packet.getFace());
        BlockPosition shifted = position.shift(direction);

        if (ws.getType(shifted).getBlock() != Blocks.AIR) return true;

        // BUG-06 fix: null guard so (0,0,0) is not falsely deduplicated on the first packet.
        if (requestedBlock != null && requestedBlock.equals(shifted)) return true;
        requestedBlock = shifted;

        if (isPlayerInsideBlock(shifted)) return true;

        // ---- FASTPLACE DETECTION ----
        long now = System.currentTimeMillis();

        if (flags > 0 && (now - lastFlagTime) > FLAG_DECAY_MS) {
            flags = 0;
            lastFlagTime = 0;
        }

        // Advance the index first: the slot we are about to overwrite IS the oldest entry.
        // Reading it before the overwrite gives us the correct window start time.
        placeTimeIndex = (placeTimeIndex + 1) % PLACE_WINDOW_SIZE;
        long oldest = placeTimes[placeTimeIndex];
        placeTimes[placeTimeIndex] = now;
        if (oldest != 0) {
            long windowMs = now - oldest;
            if (windowMs < config.getFastPlaceWindowMs()) {
                flags++;
                lastFlagTime = now;
                if (flags >= config.getFlagThreshold()) {
                    handleDetection(ws, position, shifted, "FastPlace");
                }
                // Reset sentBlock unconditionally so the AutoPlace state machine stays
                // consistent whether or not packet cancellation is enabled.
                sentBlock = false;
                if (config.shouldCancel() && flags >= config.getFlagThreshold()) {
                    sendCancelPackets(ws, position, shifted);
                    return false;
                }
                return true;
            }
        }

        // ---- AUTOPLACE DETECTION ----
        // Note: flag decay already ran above before the FastPlace check; no second decay needed.
        if (!sentBlock) {
            sentBlock = true;
            return true;
        }

        flags++;
        lastFlagTime = now;
        // Reset sentBlock unconditionally so the next placement starts a fresh
        // two-packet sequence rather than immediately re-triggering AutoPlace.
        sentBlock = false;
        if (flags >= config.getFlagThreshold()) {
            handleDetection(ws, position, shifted, "AutoPlace");
            if (config.shouldCancel()) {
                sendCancelPackets(ws, position, shifted);
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------
    //  Detection response
    // -----------------------------------------------------------------

    private void handleDetection(WorldServer worldServer, BlockPosition position,
                                 BlockPosition shifted, String type) {
        final int flagSnapshot  = flags;
        final boolean doAlert   = config.shouldAlert();
        final boolean doPunish  = config.shouldPunish()
                && flagSnapshot >= config.getPunishThreshold()
                && !punished;
        if (doPunish) punished = true;

        if (!doAlert && !doPunish) return;

        final String playerName = player.getName();
        final String playerUuid = player.getUniqueId().toString();
        final String message    = doAlert  ? config.getAlertMessage(playerName, playerUuid, type, flagSnapshot) : null;
        final String command    = doPunish ? config.getPunishmentCommand(playerName, playerUuid)                : null;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (message != null) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.hasPermission("morefeatures.autoplace.alerts")) {
                        online.sendMessage(message);
                    }
                }
                Bukkit.getConsoleSender().sendMessage(message);
            }
            if (command != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });
    }

    private void sendCancelPackets(WorldServer ws, BlockPosition position, BlockPosition shifted) {
        PlayerInventory inventory = entityPlayer.inventory;
        // Always use the player's own inventory container (defaultContainer), not activeContainer,
        // so the slot index is correct regardless of whether a GUI is open (fixes M4).
        Container container = entityPlayer.defaultContainer;

        PlayerConnection connection = entityPlayer.playerConnection;
        connection.sendPacket(new PacketPlayOutBlockChange(ws, position));
        connection.sendPacket(new PacketPlayOutBlockChange(ws, shifted));
        if (container != null) {
            Slot slot = container.getSlot(inventory, inventory.itemInHandIndex);
            if (slot != null) {
                connection.sendPacket(new PacketPlayOutSetSlot(container.windowId, slot.rawSlotIndex, inventory.getItemInHand()));
            }
        }
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static boolean isTargetInteractable(WorldServer worldServer, BlockPosition position) {
        Block block   = worldServer.getType(position).getBlock();
        Material material = Material.getMaterial(Block.getId(block));
        return material != null && INTERACTABLE_BLOCKS.contains(material);
    }

    private boolean isPlayerInsideBlock(BlockPosition shifted) {
        AxisAlignedBB playerBox = new AxisAlignedBB(
                locX - HALF_WIDTH, locY,          locZ - HALF_WIDTH,
                locX + HALF_WIDTH, locY + HEIGHT, locZ + HALF_WIDTH);
        AxisAlignedBB blockBox = new AxisAlignedBB(
                shifted.getX(),       shifted.getY(),       shifted.getZ(),
                shifted.getX() + 1.0, shifted.getY() + 1.0, shifted.getZ() + 1.0);

        return playerBox.b(blockBox);
    }
}
