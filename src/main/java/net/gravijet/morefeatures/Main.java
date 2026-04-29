package net.gravijet.morefeatures;

import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.gravijet.morefeatures.music.MusicConfig;
import net.gravijet.morefeatures.music.MusicManager;
import net.gravijet.morefeatures.music.command.MusicCommand;
import net.gravijet.morefeatures.music.command.RickrollCommand;
import net.gravijet.morefeatures.music.listener.MusicListener;
import net.gravijet.morefeatures.music.util.SongDownloader;
import net.gravijet.morefeatures.config.BridgeConfig;
import net.gravijet.morefeatures.database.DatabaseManager;
import net.gravijet.morefeatures.listener.PlayerListener;
import net.gravijet.morefeatures.listener.PunishmentListener;
import net.gravijet.morefeatures.listener.RankListener;
import net.gravijet.morefeatures.task.SyncTask;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.handler.ILoginHandler;
import xyz.refinedev.phoenix.handler.IProfileHandler;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.login.ILogin;
import xyz.refinedev.phoenix.profile.punishment.IPunishment;
import xyz.refinedev.phoenix.profile.punishment.PunishmentType;

import java.io.File;
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

    // Music system
    private MusicConfig musicConfig;
    private MusicManager musicManager;
    private SongDownloader songDownloader;

    // Tracks the per-player staggered playtime update task IDs
    private final Map<UUID, Integer> playtimeTasks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig(); // config.yml

        // --- Music system (always initializes) ---
        initMusic();

        if (!getConfig().getBoolean("phoenix-mysql.enabled", false)) {
            getLogger().info("Phoenix→MySQL sync is disabled in config.yml — plugin idle.");
            return;
        }

        // Create phoenix.yml from the bundled default if it doesn't exist yet
        saveResource("phoenix.yml", false);
        FileConfiguration phoenixCfg = YamlConfiguration.loadConfiguration(
                new File(getDataFolder(), "phoenix.yml"));
        bridgeConfig = new BridgeConfig(phoenixCfg);

        try {
            databaseManager = new DatabaseManager(bridgeConfig, getLogger());
            databaseManager.createTables();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not connect to MySQL — disabling plugin.", e);
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

        getLogger().info("Phoenix→MySQL sync enabled — network stats synced every "
                + (bridgeConfig.getSyncIntervalTicks() / 20) + " seconds.");
    }

    @Override
    public void onDisable() {
        // Stop all music playback
        if (musicManager != null) {
            musicManager.stopAll();
        }

        if (syncTask != null) {
            syncTask.cancel();
        }
        playtimeTasks.values().forEach(id -> getServer().getScheduler().cancelTask(id));
        playtimeTasks.clear();
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Plugin disabled.");
    }

    // -------------------------------------------------------------------------
    //  Music system initialisation
    // -------------------------------------------------------------------------

    private void initMusic() {
        musicConfig = new MusicConfig(this);
        File songsFolder = new File(getDataFolder(), "songs");
        songDownloader = new SongDownloader(getLogger(), songsFolder);

        // Download missing songs off the main thread
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int count = songDownloader.downloadMissing(musicConfig);
            if (count > 0) {
                getLogger().info("Downloaded " + count + " new song(s).");
            }
        });

        musicManager = new MusicManager(this, songsFolder, musicConfig.getVolume());

        // Register /music command
        MusicCommand musicCmd = new MusicCommand(musicManager, songDownloader, musicConfig);
        getCommand("music").setExecutor(musicCmd);
        getCommand("music").setTabCompleter(musicCmd);

        // Register /rickroll command
        RickrollCommand rickrollCmd = new RickrollCommand(musicManager, musicConfig);
        getCommand("rickroll").setExecutor(rickrollCmd);
        getCommand("rickroll").setTabCompleter(rickrollCmd);

        // Register quit listener for cleanup
        getServer().getPluginManager().registerEvents(new MusicListener(musicManager), this);

        getLogger().info("Music system initialised. "
                + musicManager.getAvailableSongs().size() + " song(s) available.");
    }

    // -------------------------------------------------------------------------
    // /bridgesync command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bridgesync")) return false;

        if (databaseManager == null) {
            sender.sendMessage("§cPhoenix→MySQL sync is disabled on this server.");
            return true;
        }

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
        if (databaseManager == null) return;
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
        long currentOnline = phoenix.getNetworkHandler().getOnline();
        long totalPlayers  = databaseManager.countPlayers();
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
