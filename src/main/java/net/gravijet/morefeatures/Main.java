package net.gravijet.morefeatures;

import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.gravijet.morefeatures.config.BridgeConfig;
import net.gravijet.morefeatures.database.DatabaseManager;
import net.gravijet.morefeatures.listener.PlayerListener;
import net.gravijet.morefeatures.listener.PunishmentListener;
import net.gravijet.morefeatures.listener.RankListener;
import net.gravijet.morefeatures.task.SyncTask;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.handler.ILoginHandler;
import xyz.refinedev.phoenix.handler.INetworkHandler;
import xyz.refinedev.phoenix.handler.IProfileHandler;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.login.ILogin;
import xyz.refinedev.phoenix.profile.punishment.IPunishment;
import xyz.refinedev.phoenix.profile.punishment.PunishmentType;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@Getter
public class Main extends JavaPlugin {

    private BridgeConfig bridgeConfig;
    private DatabaseManager databaseManager;
    private SyncTask syncTask;

    // Tracks the per-player staggered playtime update task IDs
    private final Map<UUID, Integer> playtimeTasks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bridgeConfig = new BridgeConfig(getConfig());

        try {
            databaseManager = new DatabaseManager(bridgeConfig, getLogger());
            databaseManager.createTables();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not connect to MySQL — disabling Bridge.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getScheduler().runTaskAsynchronously(this, this::initPunishmentCounts);

        getServer().getPluginManager().registerEvents(new PlayerListener(this),    this);
        getServer().getPluginManager().registerEvents(new PunishmentListener(this), this);
        getServer().getPluginManager().registerEvents(new RankListener(this),      this);

        // Start staggered timers for players already online when the plugin loads
        for (Player player : getServer().getOnlinePlayers()) {
            startPlaytimeTimer(player.getUniqueId());
        }

        // Periodic network-stats drift-correction (player data is handled by events + timers)
        syncTask = new SyncTask(this);
        syncTask.runTaskTimerAsynchronously(this, 100L, bridgeConfig.getSyncIntervalTicks());

        getLogger().info("Bridge enabled — network stats synced every "
                + (bridgeConfig.getSyncIntervalTicks() / 20) + " seconds.");
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            syncTask.cancel();
        }
        playtimeTasks.values().forEach(id -> getServer().getScheduler().cancelTask(id));
        playtimeTasks.clear();
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Bridge disabled.");
    }

    // -------------------------------------------------------------------------
    // /bridgesync command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bridgesync")) return false;

        if (!sender.hasPermission("bridge.sync")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        sender.sendMessage("§aBridge: running immediate sync...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            new SyncTask(this).run();
            sender.sendMessage("§aSync complete.");
        });
        return true;
    }

    // -------------------------------------------------------------------------
    // Per-player playtime timer management
    // -------------------------------------------------------------------------

    public void startPlaytimeTimer(UUID uuid) {
        // Cancel any existing timer for this UUID first (handles re-joins without quit)
        cancelPlaytimeTimer(uuid);

        int taskId = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix == null || !phoenix.isApiEnabled()) return;

            IProfileHandler profileHandler = phoenix.getProfileHandler();
            ILoginHandler loginHandler = phoenix.getLoginHandler();

            IProfile profile = profileHandler.getProfile(uuid);
            if (profile == null) return;

            List<ILogin> logins = loginHandler.getCachedLogins(uuid);
            int playtime = (int) (profile.getPlayTime(logins) / 1000L);
            databaseManager.updatePlayerPlaytime(uuid.toString(), playtime);
        }, 6000L, 6000L).getTaskId(); // first fire + repeat every 5 minutes

        playtimeTasks.put(uuid, taskId);
    }

    public void cancelPlaytimeTimer(UUID uuid) {
        Integer taskId = playtimeTasks.remove(uuid);
        if (taskId != null) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    // -------------------------------------------------------------------------
    // Shared network stats helper — used by all listeners
    // -------------------------------------------------------------------------

    public void syncNetworkStats(Phoenix phoenix) {
        INetworkHandler net = phoenix.getNetworkHandler();
        long currentOnline = net.getAllOnline();
        long totalPlayers  = net.getAllUuids().size();
        databaseManager.updateNetworkStats(currentOnline, totalPlayers);
    }

    // -------------------------------------------------------------------------
    // One-time initialisation of punishment counts
    // -------------------------------------------------------------------------

    private void initPunishmentCounts() {
        Long existingBans  = databaseManager.getStatValue("total_bans");
        Long existingMutes = databaseManager.getStatValue("total_mutes");
        Long existingKicks = databaseManager.getStatValue("total_kicks");

        // null means the column was never written; any non-null value (even 0) means already seeded
        if (existingBans != null || existingMutes != null || existingKicks != null) {
            return;
        }

        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) {
            getLogger().warning("Phoenix not ready during punishment count init — will retry on next restart.");
            return;
        }

        getLogger().info("First run detected: loading all punishments from Phoenix to seed counts...");

        phoenix.getPunishmentHandler().getAllPunishments()
                .thenAccept((List<IPunishment> punishments) -> {
                    long bans = 0, mutes = 0, kicks = 0;
                    for (IPunishment p : punishments) {
                        PunishmentType type = p.getPunishmentType();
                        if      (type == PunishmentType.BAN)  bans++;
                        else if (type == PunishmentType.MUTE) mutes++;
                        else if (type == PunishmentType.KICK) kicks++;
                    }

                    databaseManager.updateStat("total_bans",  bans);
                    databaseManager.updateStat("total_mutes", mutes);
                    databaseManager.updateStat("total_kicks", kicks);

                    getLogger().info("Seeded punishment counts — bans: " + bans
                            + ", mutes: " + mutes + ", kicks: " + kicks + ".");
                })
                .exceptionally(ex -> {
                    getLogger().log(Level.WARNING,
                            "Failed to load initial punishment counts from Phoenix", ex);
                    return null;
                });
    }
}
