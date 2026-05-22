package net.gravijet.morefeatures.autoplace;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
 */
public class AutoPlaceDecoder extends ChannelDuplexHandler {

    // Player bounding-box constants matching NMS EntityHuman
    private static final double HEIGHT     = 1.8;
    private static final double HALF_WIDTH = 0.6 / 2.0;

    // Size of the FastPlace sliding window (must match AutoPlaceConfig.getFastPlaceWindow())
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
    private final JavaPlugin plugin;
    private final AutoPlaceConfig config;

    // Per-player mutable state — only ever touched by the Netty I/O thread for this player
    private BlockPosition requestedBlock = new BlockPosition(0, 0, 0);

    // BUG-20: replaced Bukkit Location with plain doubles to avoid sharing a mutable Bukkit
    // object between the Netty I/O thread and the Bukkit main thread
    private double locX, locY, locZ;
    private float  locYaw, locPitch;

    private boolean sentBlock = false;

    // FastPlace detection: ring-buffer of the last PLACE_WINDOW_SIZE placement timestamps (ms)
    private final long[] placeTimes = new long[PLACE_WINDOW_SIZE];
    private int placeTimeIndex = 0;

    // BUG-18: track last-flag timestamp so flags decay after a quiet period
    private long lastFlagTime = 0;
    private static final long FLAG_DECAY_MS = 10_000L; // reset flags after 10 s without violations

    // Flag accumulator — incremented on each violation, used to suppress noisy punishments
    private int flags = 0;

    // One-shot guard: the punishment command must fire at most once per session,
    // otherwise every subsequent placement packet re-dispatches it (ban/command spam).
    private boolean punished = false;

    public AutoPlaceDecoder(Player player, JavaPlugin plugin, AutoPlaceConfig config) {
        this.player   = player;
        this.plugin   = plugin;
        this.config   = config;
        // Snapshot initial location as plain primitives (BUG-20)
        Location loc  = player.getLocation();
        this.locX     = loc.getX();
        this.locY     = loc.getY();
        this.locZ     = loc.getZ();
        this.locYaw   = loc.getYaw();
        this.locPitch = loc.getPitch();
    }

    // -----------------------------------------------------------------
    //  Netty entry point
    // -----------------------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Packet) {
            boolean allow = handlePacket((Packet<?>) msg);
            if (!allow) return; // swallow — block the packet
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

    // Resets sentBlock on every movement tick — the critical sequencing reset
    private boolean handleFlying(PacketPlayInFlying packet) {
        sentBlock = false;

        // BUG-20: write to plain-double fields, not a shared Bukkit Location object
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
        // Bypass permission — skip entirely
        if (player.hasPermission("morefeatures.autoplace.bypass")) return true;

        // Adventure / spectator modes don't legitimately place blocks
        GameMode gm = player.getGameMode();
        if (gm == GameMode.ADVENTURE || gm == GameMode.SPECTATOR) return true;

        // getFace() == FACE_ITEM_USE means the player right-clicked air / used an item, not a real placement
        if (packet.getFace() == FACE_ITEM_USE) return true;

        // Must be holding something
        ItemStack held = packet.getItemStack();
        if (held == null || held.getItem() == null) return true;

        // Must be placing a block, not a non-block item
        if (!(held.getItem() instanceof ItemBlock)) return true;

        // Slabs use their own placement logic; skip them to avoid false positives
        if (held.getItem() instanceof ItemStep) return true;

        WorldServer worldServer = ((CraftWorld) player.getWorld()).getHandle();
        BlockPosition position = packet.a();

        // Clicking on interactable blocks while standing (not sneaking) is normal interaction
        if (isTargetInteractable(worldServer, position) && !player.isSneaking()) return true;

        // Determine the position where the new block would be placed
        EnumDirection direction = EnumDirection.fromType1(packet.getFace());
        BlockPosition shifted = position.shift(direction);

        // If the target cell is not air the block cannot be placed — ignore
        if (worldServer.getType(shifted).getBlock() != Blocks.AIR) return true;

        // Duplicate-position guard: same position as the last accepted request means
        // the client is just re-confirming the same placement — let it through
        if (requestedBlock.equals(shifted)) return true;
        requestedBlock = shifted;

        // Bounding-box guard: if the player's body already occupies that cell the
        // placement is geometrically impossible for a real client — skip
        if (isPlayerInsideBlock(shifted)) return true;

        // ---- FASTPLACE DETECTION ----
        // Record this placement timestamp in the ring buffer.
        long now = System.currentTimeMillis();

        // BUG-18: decay flags after a quiet period so false lag-spike flags don't accumulate forever
        if (flags > 0 && (now - lastFlagTime) > FLAG_DECAY_MS) {
            flags = 0;
        }

        placeTimes[placeTimeIndex] = now;
        placeTimeIndex = (placeTimeIndex + 1) % PLACE_WINDOW_SIZE;

        // Oldest slot in the ring is the one we just overwrote — i.e. PLACE_WINDOW_SIZE placements ago.
        long oldest = placeTimes[placeTimeIndex];
        if (oldest != 0) {
            long windowMs = now - oldest;
            // config: minimum ms allowed for PLACE_WINDOW_SIZE blocks (default 400 ms → 25 blocks/s)
            if (windowMs < config.getFastPlaceWindowMs()) {
                flags++;
                lastFlagTime = now; // BUG-18: update decay timestamp on every flag increment
                // BUG-24: mark sentBlock so the AutoPlace path is not confused by skipped packets
                sentBlock = true;
                if (flags >= config.getFlagThreshold()) {
                    handleDetection(worldServer, position, shifted, "FastPlace");
                    if (config.shouldCancel()) {
                        sendCancelPackets(worldServer, position, shifted);
                        return false;
                    }
                }
                return true;
            }
        }

        // ---- AUTOPLACE DETECTION ----
        // First block-place packet since the last flying packet → mark and allow.
        // Second block-place packet without an intervening flying → AutoPlace detected.
        if (!sentBlock) {
            sentBlock = true;
            return true;
        }

        flags++;
        lastFlagTime = now; // BUG-18
        if (flags >= config.getFlagThreshold()) {
            handleDetection(worldServer, position, shifted, "AutoPlace");
            if (config.shouldCancel()) {
                sendCancelPackets(worldServer, position, shifted);
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
        // Snapshot all state on the Netty thread before handing off to the main thread.
        // player.getName() / getUniqueId() read cached fields and are safe off-thread,
        // but we snapshot them here explicitly so the lambda captures plain Strings,
        // not live Bukkit API references.
        final int flagSnapshot  = flags;
        final boolean doAlert   = config.shouldAlert();
        final boolean doPunish  = config.shouldPunish()
                && flagSnapshot >= config.getPunishThreshold()
                && !punished;
        if (doPunish) punished = true; // one-shot — netty thread only, so safe

        if (!doAlert && !doPunish) return;

        // Build strings on the Netty thread using only cached/immutable player fields.
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

    private void sendCancelPackets(WorldServer worldServer, BlockPosition position, BlockPosition shifted) {
        EntityPlayer entityPlayer = ((CraftPlayer) player).getHandle();
        PlayerInventory inventory  = entityPlayer.inventory;
        Container container        = entityPlayer.activeContainer;
        Slot slot                  = container.getSlot(inventory, inventory.itemInHandIndex);

        PlayerConnection connection = entityPlayer.playerConnection;
        connection.sendPacket(new PacketPlayOutBlockChange(worldServer, position));
        connection.sendPacket(new PacketPlayOutBlockChange(worldServer, shifted));
        if (slot != null) {
            connection.sendPacket(new PacketPlayOutSetSlot(container.windowId, slot.rawSlotIndex, inventory.getItemInHand()));
        }
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static boolean isTargetInteractable(WorldServer worldServer, BlockPosition position) {
        Block block    = worldServer.getType(position).getBlock();
        Material material = Material.getMaterial(Block.getId(block));
        return material != null && INTERACTABLE_BLOCKS.contains(material);
    }

    private boolean isPlayerInsideBlock(BlockPosition shifted) {
        // BUG-20: use plain-double fields instead of Bukkit Location
        AxisAlignedBB playerBox = new AxisAlignedBB(
                locX - HALF_WIDTH, locY,          locZ - HALF_WIDTH,
                locX + HALF_WIDTH, locY + HEIGHT, locZ + HALF_WIDTH);
        AxisAlignedBB blockBox = new AxisAlignedBB(
                shifted.getX(),       shifted.getY(),       shifted.getZ(),
                shifted.getX() + 1.0, shifted.getY() + 1.0, shifted.getZ() + 1.0);

        return playerBox.b(blockBox); // NMS AxisAlignedBB.b() = intersects
    }
}
