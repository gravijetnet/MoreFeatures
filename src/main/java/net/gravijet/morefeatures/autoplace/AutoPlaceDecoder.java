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
 */
public class AutoPlaceDecoder extends ChannelDuplexHandler {

    // Player bounding-box constants matching NMS EntityHuman
    private static final double HEIGHT     = 1.8;
    private static final double HALF_WIDTH = 0.6 / 2.0;

    // Size of the FastPlace sliding window (must match AutoPlaceConfig.getFastPlaceWindow())
    private static final int PLACE_WINDOW_SIZE = 10;

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
    private final Location lastLocation;
    private boolean sentBlock = false;

    // FastPlace detection: ring-buffer of the last PLACE_WINDOW_SIZE placement timestamps (ms)
    private final long[] placeTimes = new long[PLACE_WINDOW_SIZE];
    private int placeTimeIndex = 0;

    // Flag accumulator — incremented on each violation, used to suppress noisy punishments
    private int flags = 0;

    public AutoPlaceDecoder(Player player, JavaPlugin plugin, AutoPlaceConfig config) {
        this.player       = player;
        this.plugin       = plugin;
        this.config       = config;
        this.lastLocation = player.getLocation();
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

        if (packet.g()) { // hasPos
            lastLocation.setX(packet.a());
            lastLocation.setY(packet.b());
            lastLocation.setZ(packet.c());
        }
        if (packet.h()) { // hasLook
            lastLocation.setYaw(packet.d());
            lastLocation.setPitch(packet.e());
        }
        lastLocation.setWorld(player.getWorld());
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleBlockPlace(PacketPlayInBlockPlace packet) {
        // Bypass permission — skip entirely
        if (player.hasPermission("morefeatures.autoplace.bypass")) return true;

        // Adventure / spectator modes don't legitimately place blocks
        GameMode gm = player.getGameMode();
        if (gm == GameMode.ADVENTURE || gm == GameMode.SPECTATOR) return true;

        // getFace() == 255 means the player right-clicked air / used an item, not a real placement
        if (packet.getFace() == 255) return true;

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
        placeTimes[placeTimeIndex] = now;
        placeTimeIndex = (placeTimeIndex + 1) % PLACE_WINDOW_SIZE;

        // Oldest slot in the ring is the one we just overwrote — i.e. PLACE_WINDOW_SIZE placements ago.
        long oldest = placeTimes[placeTimeIndex];
        if (oldest != 0) {
            long windowMs = now - oldest;
            // config: minimum ms allowed for PLACE_WINDOW_SIZE blocks (default 400 ms → 25 blocks/s)
            if (windowMs < config.getFastPlaceWindowMs()) {
                flags++;
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
        if (config.shouldAlert()) {
            String message = config.getAlertMessage(player, type, flags);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("morefeatures.autoplace.alerts")) {
                    online.sendMessage(message);
                }
            }
            Bukkit.getConsoleSender().sendMessage(message);
        }

        if (config.shouldPunish() && flags >= config.getPunishThreshold()) {
            String command = config.getPunishmentCommand(player);
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    private void sendCancelPackets(WorldServer worldServer, BlockPosition position, BlockPosition shifted) {
        EntityPlayer entityPlayer = ((CraftPlayer) player).getHandle();
        PlayerInventory inventory  = entityPlayer.inventory;
        Container container        = entityPlayer.activeContainer;
        Slot slot                  = container.getSlot(inventory, inventory.itemInHandIndex);
        int windowId               = container.windowId;
        int rawSlot                = slot.rawSlotIndex;

        PlayerConnection connection = entityPlayer.playerConnection;
        connection.sendPacket(new PacketPlayOutBlockChange(worldServer, position));
        connection.sendPacket(new PacketPlayOutBlockChange(worldServer, shifted));
        connection.sendPacket(new PacketPlayOutSetSlot(windowId, rawSlot, inventory.getItemInHand()));
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
        double px = lastLocation.getX();
        double py = lastLocation.getY();
        double pz = lastLocation.getZ();

        AxisAlignedBB playerBox = new AxisAlignedBB(
                px - HALF_WIDTH, py,          pz - HALF_WIDTH,
                px + HALF_WIDTH, py + HEIGHT, pz + HALF_WIDTH);
        AxisAlignedBB blockBox = new AxisAlignedBB(
                shifted.getX(),       shifted.getY(),       shifted.getZ(),
                shifted.getX() + 1.0, shifted.getY() + 1.0, shifted.getZ() + 1.0);

        return playerBox.b(blockBox); // NMS AxisAlignedBB.b() = intersects
    }
}
