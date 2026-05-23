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
import net.gravijet.morefeatures.autoplace.AutoPlaceConfig;
import net.gravijet.morefeatures.autoplace.AutoPlaceInjector;
import net.gravijet.morefeatures.autoplace.AutoPlaceListener;
import net.gravijet.morefeatures.fullbright.FullbrightCommand;
import net.gravijet.morefeatures.fullbright.FullbrightListener;
import net.gravijet.morefeatures.fullbright.FullbrightManager;
import net.gravijet.morefeatures.listener.PlayerListener;
import net.gravijet.morefeatures.listener.PunishmentListener;
import net.gravijet.morefeatures.listener.RankListener;
import net.gravijet.morefeatures.phoenix.NetworkStatsSync;
import net.gravijet.morefeatures.phoenix.PlaytimeSync;
import net.gravijet.morefeatures.task.SyncTask;
import xyz.refinedev.phoenix.Phoenix;
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
    private volatile DatabaseManager databaseManager;
    private PlaytimeSync playtimeSync;
    private NetworkStatsSync networkStatsSync;
    private SyncTask syncTask;

    // Music system
    private MusicConfig musicConfig;
    private MusicManager musicManager;
    private SongDownloader songDownloader;

    // Fullbright system
    private FullbrightManager fullbrightManager;

    // AutoPlace detection system
    private AutoPlaceInjector autoPlaceInjector;

    private final Map<UUID, Integer> playtimeTasks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();

        initMusic();
        initFullbright();
        initAutoPlace();

        if (!getConfig().getBoolean("phoenix-mysql.enabled", false)) {
            getLogger().info("Phoenix→MySQL sync is disabled in config.yml — plugin idle.");
            return;
        }

        saveResource("phoenix.yml", false);
        FileConfiguration phoenixCfg = YamlConfiguration.loadConfiguration(
                new File(getDataFolder(), "phoenix.yml"));
        bridgeConfig = new BridgeConfig(phoenixCfg, getLogger());

        try {
            databaseManager = new DatabaseManager(bridgeConfig, getLogger());
            databaseManager.createTables();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not connect to MySQL — disabling plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playtimeSync = new PlaytimeSync(this, databaseManager);
        networkStatsSync = new NetworkStatsSync(databaseManager);

        getServer().getPluginManager().registerEvents(new PlayerListener(this),    this);
        getServer().getPluginManager().registerEvents(new PunishmentListener(this), this);
        getServer().getPluginManager().registerEvents(new RankListener(this),      this);

        syncTask = new SyncTask(this);
        long syncInterval = Math.max(20L, bridgeConfig.getSyncIntervalTicks());
        syncTask.runTaskTimerAsynchronously(this, 100L, syncInterval);

        for (Player player : getServer().getOnlinePlayers()) {
            startPlaytimeTimer(player.getUniqueId());
        }

        getServer().getScheduler().runTaskAsynchronously(this, this::initPunishmentCounts);

        getLogger().info("Phoenix→MySQL sync enabled — network stats synced every "
                + (syncInterval / 20) + " seconds.");

    }

    @Override
    public void onDisable() {
        if (musicManager != null) {
            musicManager.stopAll();
        }
        if (syncTask != null) {
            syncTask.cancel();
        }
        // BUG-03 fix: cancel each timer explicitly before clearing the map so no
        // in-flight task can slip a DB write past the pool shutdown.
        playtimeTasks.forEach((uuid, taskId) -> getServer().getScheduler().cancelTask(taskId));
        playtimeTasks.clear();
        getServer().getScheduler().cancelTasks(this);
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Plugin disabled.");
    }

    // -------------------------------------------------------------------------
    //  Fullbright
    // -------------------------------------------------------------------------

    private void initFullbright() {
        boolean fbEnabled = getConfig().getBoolean("fullbright.enabled", false);
        fullbrightManager = new FullbrightManager(this, fbEnabled);

        FullbrightCommand fbCmd = new FullbrightCommand(fullbrightManager);
        org.bukkit.command.PluginCommand fbCommand = getCommand("fullbright");
        if (fbCommand != null) {
            fbCommand.setExecutor(fbCmd);
            fbCommand.setTabCompleter(fbCmd);
        } else {
            getLogger().severe("Command 'fullbright' not registered in plugin.yml — fullbright command unavailable.");
        }
        getServer().getPluginManager().registerEvents(new FullbrightListener(fullbrightManager), this);

        if (fbEnabled) {
            getServer().getScheduler().runTask(this, fullbrightManager::relightAllLoaded);
        }
        getLogger().info("Fullbright system initialised (enabled=" + fbEnabled + ").");
    }

    // -------------------------------------------------------------------------
    //  AutoPlace
    // -------------------------------------------------------------------------

    private void initAutoPlace() {
        if (!getConfig().getBoolean("autoplace.enabled", true)) {
            getLogger().info("AutoPlace detection disabled in config.yml.");
            return;
        }

        AutoPlaceConfig apConfig = new AutoPlaceConfig(this);
        autoPlaceInjector = new AutoPlaceInjector(this, apConfig);

        for (Player player : getServer().getOnlinePlayers()) {
            autoPlaceInjector.inject(player);
        }
        getServer().getPluginManager().registerEvents(new AutoPlaceListener(autoPlaceInjector), this);
        getLogger().info("AutoPlace detection enabled.");
    }

    // -------------------------------------------------------------------------
    //  Music
    // -------------------------------------------------------------------------

    private void initMusic() {
        musicConfig = new MusicConfig(this);
        File songsFolder = new File(getDataFolder(), "songs");
        songDownloader = new SongDownloader(getLogger(), songsFolder);

        // BUG-01 fix: assign musicManager before scheduling the async task so the
        // lambda can safely call musicManager.getAvailableSongs().
        musicManager = new MusicManager(this, songsFolder, musicConfig.getVolume());

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int count = songDownloader.downloadMissing(musicConfig);
            if (count > 0) getLogger().info("Downloaded " + count + " new song(s).");
            // Count available songs off the main thread to avoid blocking on directory I/O.
            getLogger().info("Music system ready. "
                    + musicManager.getAvailableSongs().size() + " song(s) available.");
        });

        // MusicManager registers SongEndEvent itself

        MusicCommand musicCmd = new MusicCommand(musicManager, songDownloader, musicConfig);
        org.bukkit.command.PluginCommand musicCommand = getCommand("music");
        if (musicCommand != null) {
            musicCommand.setExecutor(musicCmd);
            musicCommand.setTabCompleter(musicCmd);
        } else {
            getLogger().severe("Command 'music' not registered in plugin.yml — music command unavailable.");
        }

        RickrollCommand rickrollCmd = new RickrollCommand(musicManager, musicConfig);
        org.bukkit.command.PluginCommand rickrollCommand = getCommand("rickroll");
        if (rickrollCommand != null) {
            rickrollCommand.setExecutor(rickrollCmd);
            rickrollCommand.setTabCompleter(rickrollCmd);
        } else {
            getLogger().severe("Command 'rickroll' not registered in plugin.yml — rickroll command unavailable.");
        }

        getServer().getPluginManager().registerEvents(new MusicListener(musicManager), this);

        getLogger().info("Music system initialised.");
    }

    // -------------------------------------------------------------------------
    //  /bridgesync command
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
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix != null && phoenix.isApiEnabled()) {
                syncNetworkStats(phoenix);
            }
            sender.sendMessage("§aSync complete.");
        });
        return true;
    }

    // -------------------------------------------------------------------------
    //  Per-player playtime timer
    // -------------------------------------------------------------------------

    public void startPlaytimeTimer(UUID uuid) {
        if (databaseManager == null) return;
        // Atomically cancel any existing timer and schedule a new one so concurrent
        // calls for the same UUID cannot leak a duplicate timer.
        playtimeTasks.compute(uuid, (id, existingTaskId) -> {
            if (existingTaskId != null) {
                getServer().getScheduler().cancelTask(existingTaskId);
            }
            return getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (playtimeSync != null) playtimeSync.syncPlaytime(uuid);
            }, 6000L, 6000L).getTaskId();
        });
    }

    public void cancelPlaytimeTimer(UUID uuid) {
        Integer taskId = playtimeTasks.remove(uuid);
        if (taskId != null) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    // -------------------------------------------------------------------------
    //  Network stats helper — used by listeners
    // -------------------------------------------------------------------------

    public void syncNetworkStats(Phoenix phoenix) {
        if (networkStatsSync != null) networkStatsSync.sync(phoenix);
    }

    // -------------------------------------------------------------------------
    //  One-time punishment count seed
    // -------------------------------------------------------------------------

    private void initPunishmentCounts() {
        Long existingBans  = databaseManager.getStatValue("total_bans");
        Long existingMutes = databaseManager.getStatValue("total_mutes");
        Long existingKicks = databaseManager.getStatValue("total_kicks");

        if (existingBans != null && existingMutes != null && existingKicks != null) return;

        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) {
            getLogger().warning("Phoenix not ready during punishment count init — will retry on next restart.");
            return;
        }

        getLogger().info("First run: seeding punishment counts from Phoenix...");

        phoenix.getPunishmentHandler().getAllPunishments()
                .thenAccept((List<IPunishment> punishments) -> {
                    // Guard against the pool being closed while this future was pending.
                    DatabaseManager db = databaseManager;
                    if (db == null) return;
                    try {
                        long bans = 0, mutes = 0, kicks = 0;
                        for (IPunishment p : punishments) {
                            PunishmentType type = p.getPunishmentType();
                            if      (type == PunishmentType.BAN)  bans++;
                            else if (type == PunishmentType.MUTE) mutes++;
                            else if (type == PunishmentType.KICK) kicks++;
                        }
                        db.updateStat("total_bans",  bans);
                        db.updateStat("total_mutes", mutes);
                        db.updateStat("total_kicks", kicks);
                        getLogger().info("Seeded — bans: " + bans + ", mutes: " + mutes + ", kicks: " + kicks);
                    } catch (Exception ex) {
                        getLogger().log(Level.WARNING, "Failed to seed punishment counts", ex);
                    }
                })
                .exceptionally(ex -> {
                    getLogger().log(Level.WARNING, "Failed to load initial punishment counts", ex);
                    return null;
                });
    }
}
