package net.gravijet.morefeatures;

import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.gravijet.morefeatures.action.ActionQueue;
import net.gravijet.morefeatures.action.Broadcaster;
import net.gravijet.morefeatures.action.ConfigActionQueue;
import net.gravijet.morefeatures.action.QueuePoller;
import net.gravijet.morefeatures.action.ServerPublisher;
import net.gravijet.morefeatures.config.BridgeConfig;
import net.gravijet.morefeatures.database.DatabaseManager;
import net.gravijet.morefeatures.autoplace.AutoPlaceConfig;
import net.gravijet.morefeatures.autoplace.AutoPlaceInjector;
import net.gravijet.morefeatures.autoplace.AutoPlaceListener;
import net.gravijet.morefeatures.chat.ChatListener;
import net.gravijet.morefeatures.display.ArenaCache;
import net.gravijet.morefeatures.display.ArenaLookup;
import net.gravijet.morefeatures.display.DisplayConfig;
import net.gravijet.morefeatures.display.DisplayExpansion;
import net.gravijet.morefeatures.display.DisplayResolver;
import net.gravijet.morefeatures.fullbright.FullbrightCommand;
import net.gravijet.morefeatures.fullbright.FullbrightListener;
import net.gravijet.morefeatures.fullbright.FullbrightManager;
import net.gravijet.morefeatures.listener.PlayerListener;
import net.gravijet.morefeatures.listener.PunishmentListener;
import net.gravijet.morefeatures.listener.RankListener;
import net.gravijet.morefeatures.link.CommandOverride;
import net.gravijet.morefeatures.link.LinkCommand;
import net.gravijet.morefeatures.link.LinkStore;
import net.gravijet.morefeatures.link.UnlinkCommand;
import net.gravijet.morefeatures.phoenix.NetworkStatsSync;
import net.gravijet.morefeatures.phoenix.PlaytimeSync;
import net.gravijet.morefeatures.task.SyncTask;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.profile.punishment.IPunishment;
import xyz.refinedev.phoenix.profile.punishment.PunishmentType;

import java.io.File;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    // Fullbright system
    private FullbrightManager fullbrightManager;

    // AutoPlace detection system
    private AutoPlaceInjector autoPlaceInjector;

    // Tablist / nametag / chat, resolved from Phoenix and MBedwars
    private DisplayConfig displayConfig;
    private DisplayResolver displayResolver;
    private DisplayExpansion displayExpansion;
    private ArenaCache arenaCache;

    // Discord account linking (replaces Sync's /link and /unlink)
    private LinkStore linkStore;
    private ActionQueue actionQueue;
    private ConfigActionQueue configQueue;
    private Broadcaster broadcaster;
    private ServerPublisher serverPublisher;

    /**
     * Who is on this server, for the batched playtime write.
     *
     * This used to be a map of UUID to scheduler task id, because playtime was
     * written by a repeating task per player. One task and one batched UPDATE
     * covers all of them, so all that is needed now is the set — maintained from
     * join and quit, which are main-thread events, rather than read off
     * Bukkit.getOnlinePlayers() from the pool thread that does the writing.
     */
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();

        initFullbright();
        initAutoPlace();
        initDisplay();

        // Defaults to on when the key is absent. An existing config.yml is never
        // rewritten by saveDefaultConfig(), so a server that predates this default
        // still carries enabled: false — warn rather than log at info, because the
        // symptom (an empty database) otherwise looks like a connection problem.
        if (!getConfig().getBoolean("phoenix-mysql.enabled", true)) {
            getLogger().warning("phoenix-mysql.enabled is false in config.yml — "
                    + "nothing will be written to the database. Set it to true to turn the bridge on.");
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

        initLinking();
        initActionQueue();

        getServer().getPluginManager().registerEvents(new PlayerListener(this),    this);
        getServer().getPluginManager().registerEvents(new PunishmentListener(this), this);
        getServer().getPluginManager().registerEvents(new RankListener(this),      this);

        syncTask = new SyncTask(this);
        long syncInterval = Math.max(20L, bridgeConfig.getSyncIntervalTicks());
        syncTask.runTaskTimerAsynchronously(this, 100L, syncInterval);

        for (Player player : getServer().getOnlinePlayers()) {
            trackPlaytime(player.getUniqueId());
        }

        // Everyone's playtime, batched into one statement. See PlaytimeSync.
        long playtimeInterval = bridgeConfig.getPlaytimeIntervalTicks();
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (playtimeSync != null) playtimeSync.syncAll(tracked);
        }, playtimeInterval, playtimeInterval);

        getServer().getScheduler().runTaskAsynchronously(this, this::initPunishmentCounts);

        getLogger().info("Phoenix→MySQL sync enabled — network stats every "
                + (syncInterval / 20) + "s, playtime every " + (playtimeInterval / 20)
                + "s, queues polled every " + bridgeConfig.getQueuePollTicks() + " ticks.");

    }

    @Override
    public void onDisable() {
        if (fullbrightManager != null) {
            fullbrightManager.clearAllPersonal();
        }
        // persist() keeps the expansion alive across a /papi reload, so it also
        // has to be taken down explicitly here or a plugin reload leaves a dead
        // one registered against the old classloader.
        if (displayExpansion != null) {
            displayExpansion.unregister();
        }
        if (arenaCache != null) {
            arenaCache.stop();
        }
        if (syncTask != null) {
            syncTask.cancel();
        }
        getServer().getScheduler().cancelTasks(this);

        // One last batch on the way out. CraftBukkit disables plugins before it
        // disconnects anybody, so no quit event ever fires for the players who
        // were on at shutdown — without this every restart quietly threw away
        // however long everyone had been playing since the last batch. It is a
        // single statement and it has to run before the pool closes, so it runs
        // here, inline, rather than being handed to a scheduler that has already
        // stopped taking work.
        if (playtimeSync != null && !tracked.isEmpty()) {
            try {
                playtimeSync.syncAll(tracked);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Final playtime flush failed: " + e.getMessage());
            }
        }
        tracked.clear();

        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Plugin disabled.");
    }

    // -------------------------------------------------------------------------
    //  Fullbright
    // -------------------------------------------------------------------------

    /**
     * Discord account linking, taking /link and /unlink off Sync.
     *
     * Sync points those at a Nova panel on localhost:3000; ours points at
     * example.invalid, which is where the ranks, applications and appeals now live.
     * Sync itself is left alone — /sync and /verify still belong to it.
     *
     * This runs after the bridge pool is up because it borrows it, and one tick
     * later than that because the takeover has to happen after Sync has actually
     * registered. softdepend gets us enabled second; the delay covers the case
     * where Sync registers its commands from a scheduled task rather than
     * straight out of onEnable.
     */
    private void initLinking() {
        linkStore = new LinkStore(databaseManager);
        try {
            linkStore.createTables();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not create the link tables — /link is off.", e);
            linkStore = null;
            return;
        }

        final LinkCommand link = new LinkCommand(this, linkStore);
        final UnlinkCommand unlink = new UnlinkCommand(this, linkStore);

        getServer().getScheduler().runTask(this, () -> {
            CommandOverride.take(this, "link", link, link,
                    "Link your Minecraft account to Discord via example.invalid", "/link <code>");
            CommandOverride.take(this, "unlink", unlink, unlink,
                    "Unlink your Minecraft account from Discord", "/unlink");
        });

        // Unredeemed codes are guesses waiting to land, so they do not get to sit
        // there forever. Hourly, off-thread, and a failure is worth a line but
        // not a stack trace.
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int purged = linkStore.purgeExpired();
                if (purged > 0) getLogger().info("Purged " + purged + " expired link codes.");
            } catch (SQLException e) {
                getLogger().warning("Could not purge expired link codes: " + e.getMessage());
            }
        }, 20L * 60, 20L * 60 * 60);
    }

    /**
     * The queue Spielplatz drops work into. See ActionQueue for why the website
     * asks rather than writes.
     *
     * Every server that runs this polls, and only one of them wins each job — so
     * this needs no leader, no configuration, and no server nominated as the one
     * that matters. A box being down is not a moderation outage.
     */
    private void initActionQueue() {
        actionQueue = new ActionQueue(this, databaseManager, phoenixServerName());
        try {
            actionQueue.createTables();
        } catch (SQLException | RuntimeException e) {
            // RuntimeException too: the pool throws those, and this runs inside
            // onEnable. A moderation queue that cannot start is a bad evening;
            // one that takes /link and the anticheat down with it by throwing out
            // of onEnable is a bad night.
            getLogger().log(Level.SEVERE, "Could not create mod_actions — the website cannot punish anyone.", e);
            actionQueue = null;
        }

        // The config queue is the same story for edits to ranks and ladders.
        // Separate table, separate failure: a broken rank editor must not stop
        // bans landing, so its own createTables is guarded on its own.
        configQueue = new ConfigActionQueue(this, databaseManager, phoenixServerName());
        try {
            configQueue.createTables();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not create config_actions — the network editor is unavailable.", e);
            configQueue = null;
        }

        // Announcements the website sends to the game. Fan-out, not a claim queue:
        // every server shows each one once. Its own createTables so a failure here
        // does not take the moderation queues down with it.
        broadcaster = new Broadcaster(this, databaseManager);
        try {
            broadcaster.createTables();
            broadcaster.initCursor();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not create network_broadcasts — announcements are unavailable.", e);
            broadcaster = null;
        }

        // One timer for all three rather than three staggered ones, sharing a
        // single connection per cycle. See QueuePoller — this is both cheaper
        // than the old shape and twice as quick to notice a queued ban.
        //
        // Note the guards above no longer return: one queue failing to create its
        // table used to abandon everything set up after it, including the status
        // publisher, so a single broken table took the website's network page
        // down with it. Now each one is simply absent and the rest run.
        QueuePoller poller = new QueuePoller(this, databaseManager, actionQueue, configQueue, broadcaster);
        if (poller.hasWork()) {
            long pollTicks = bridgeConfig.getQueuePollTicks();
            getServer().getScheduler().runTaskTimerAsynchronously(this, poller, 100L, pollTicks);
        }

        // Moving a player between servers is the proxy's job, and it will only be
        // asked over its own channel — without this a Connect goes nowhere.
        getServer().getMessenger().registerOutgoingPluginChannel(this, Broadcaster.BUNGEE_CHANNEL);

        // What this server looks like, published for the website. Runs on the
        // server thread: it reads the player list.
        serverPublisher = new ServerPublisher(this, databaseManager,
                phoenixServerName(), phoenixServerGroup());
        try {
            serverPublisher.createTables();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not create network_servers — the website cannot show the network.", e);
            serverPublisher = null;
            return;
        }
        long statusTicks = bridgeConfig.getStatusIntervalTicks();
        getServer().getScheduler().runTaskTimer(this, () -> serverPublisher.tick(), 200L, statusTicks);
        // Once a second on the server thread: the sample *is* the measurement, so
        // it has to run where the ticks are.
        getServer().getScheduler().runTaskTimer(this, () -> serverPublisher.sampleTps(), 20L, 20L);
    }

    // -------------------------------------------------------------------------
    //  The core's own global.yml
    // -------------------------------------------------------------------------
    //
    // Read once and held. It was being loaded and parsed from disk three times
    // in a row during startup for the same two strings, and it never changes
    // while the server is up.

    private FileConfiguration phoenixGlobal;
    private boolean phoenixGlobalLoaded = false;

    private FileConfiguration phoenixGlobal() {
        if (!phoenixGlobalLoaded) {
            phoenixGlobalLoaded = true;
            File global = new File(getDataFolder().getParentFile(), "Phoenix/global.yml");
            if (global.isFile()) phoenixGlobal = YamlConfiguration.loadConfiguration(global);
        }
        return phoenixGlobal;
    }

    /**
     * What Phoenix calls this server.
     *
     * Read from the core's own global.yml rather than from Bukkit: this ends up
     * in a punishment's `issuedOn`, and it should say exactly what every
     * punishment issued in game on this box already says. Server.getServerName()
     * would have been the obvious answer and is gone from the API these servers
     * actually run on, whatever the 1.8.8 we compile against still offers.
     */
    private String phoenixServerName() {
        FileConfiguration global = phoenixGlobal();
        if (global != null) {
            String name = global.getString("server.name");
            if (name != null && !name.isEmpty()) return name;
        }
        return "unknown";
    }

    /**
     * Which group of servers this one belongs to — "bedwars", "lobby".
     *
     * The core's API can answer this, but only on a Phoenix new enough to have
     * INetworkHandler#getServerGroup(); on an older one the call raises
     * NoSuchMethodError, which is what {@link ServerPublisher} was falling over.
     * The same answer is in the core's config either way, so it is read here and
     * used whenever the API cannot be asked.
     *
     * @return the configured group, or null if the core does not name one.
     */
    private String phoenixServerGroup() {
        FileConfiguration global = phoenixGlobal();
        if (global == null) return null;
        // Both spellings appear across Phoenix versions; neither is wrong to try.
        for (String key : new String[]{"server.group", "server.server-group", "server.groupName"}) {
            String group = global.getString(key);
            if (group != null && !group.isEmpty()) return group;
        }
        return null;
    }

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
    //  Display — tablist sorting, nametags, chat
    // -------------------------------------------------------------------------

    /**
     * Rank, priority and bedwars team, resolved once and published to everyone
     * who needs them.
     *
     * TAB renders the tablist and the nametags here, and it renders them with
     * packets. A plugin that writes scoreboard teams alongside it loses that
     * race intermittently, which is what a name flickering white with no prefix
     * is. So this does not render anything — it answers TAB, and TAB draws. See
     * display.yml for the TAB config that goes with it.
     *
     * Chat is ours, because TAB does not do chat. Only outside an arena though;
     * MBedwars owns chat inside a game.
     *
     * Runs before the phoenix-mysql block below on purpose: the database bridge
     * returning early must not take the tablist down with it.
     */
    private void initDisplay() {
        if (!getConfig().getBoolean("display.enabled", true)) {
            getLogger().info("Display (tablist/nametag/chat) disabled in config.yml.");
            return;
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().warning("PlaceholderAPI is not installed — tablist sorting, prefixes "
                    + "and the chat format are off. Install it or set display.enabled to false.");
            return;
        }

        displayConfig = new DisplayConfig(this);

        // TAB refreshes placeholders asynchronously and chat arrives async, so
        // MBedwars is read on a main-thread timer and everyone else reads the
        // snapshot. See ArenaCache.
        arenaCache = new ArenaCache(ArenaLookup.create(getLogger()));
        arenaCache.start(this);

        displayResolver = new DisplayResolver(arenaCache, displayConfig);

        displayExpansion = new DisplayExpansion(this, displayResolver);
        if (!displayExpansion.register()) {
            getLogger().severe("PlaceholderAPI refused the %morefeatures_*% expansion — "
                    + "TAB will have nothing to sort on.");
            displayExpansion = null;
        }

        if (displayConfig.isChatEnabled()) {
            getServer().getPluginManager().registerEvents(
                    new ChatListener(displayResolver, displayConfig, getLogger()), this);
        }

        // The resolver keys its snapshots by UUID and nothing tells it when
        // somebody leaves. Once every ten seconds is far more often than needed
        // to keep a map of at-most-the-player-count entries from drifting.
        getServer().getScheduler().runTaskTimer(this, () -> {
            Set<UUID> online = new HashSet<>();
            for (Player player : getServer().getOnlinePlayers()) {
                online.add(player.getUniqueId());
            }
            displayResolver.sweep(online);
        }, 200L, 200L);

        getLogger().info("Display initialised — %morefeatures_*% published to PlaceholderAPI"
                + (displayConfig.isChatEnabled() ? ", lobby chat format active." : "."));
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
    //  Playtime tracking
    // -------------------------------------------------------------------------

    /** Include this player in the batched playtime write. */
    public void trackPlaytime(UUID uuid) {
        if (databaseManager == null) return;
        tracked.add(uuid);
    }

    /** Stop including them — they left, and their final sync is done separately. */
    public void untrackPlaytime(UUID uuid) {
        tracked.remove(uuid);
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
