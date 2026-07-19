package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;
import org.bukkit.ChatColor;
import xyz.refinedev.phoenix.BukkitAPI;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.profile.IProfile;
import xyz.refinedev.phoenix.profile.grant.IGrant;
import xyz.refinedev.phoenix.profile.punishment.IPunishment;
import xyz.refinedev.phoenix.profile.punishment.PunishmentType;
import xyz.refinedev.phoenix.rank.IRank;
import xyz.refinedev.phoenix.scope.IScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Work the website asked the game to do.
 *
 * Spielplatz cannot punish anybody by itself and should not try. Phoenix holds
 * punishments and grants in memory and syncs them between servers over Redis, so
 * a row written into its database from outside reaches nobody who is already
 * online, gets overwritten the next time the core saves the profile it thinks it
 * owns, and fires none of the events the rest of the network hangs off — no ban
 * screen, no staff alert, no ladder step, not even our own PunishmentListener.
 * It would look correct in the database and do nothing at all to the player.
 *
 * So the website writes down what it wants and this executes it, from inside the
 * game, through the core's own API. Exactly the code path a moderator typing the
 * command would take, because it is that code path.
 *
 * Every server running MoreFeatures polls this table, and that is fine: a job is
 * claimed with a conditional UPDATE, so precisely one of them wins it and the
 * rest move on. Phoenix propagates the effect to the others regardless — that is
 * what the core is for.
 */
public class ActionQueue {

    /** Phoenix's console. Real — it is what the core writes when nobody in particular did it. */
    private static final UUID CONSOLE = new UUID(0L, 0L);

    /** Punishment IDs are six characters — the ones printed on the ban screen. */
    private static final int ID_LENGTH = 6;

    /** Claimed at a time. Small: a backlog is not an emergency, and 20 bans in one tick is. */
    private static final int BATCH = 20;

    private static final String CREATE = ""
            + "CREATE TABLE IF NOT EXISTS `mod_actions` ("
            + "  `id`            BIGINT       NOT NULL AUTO_INCREMENT,"
            + "  `action`        VARCHAR(24)  NOT NULL,"
            + "  `target_uuid`   VARCHAR(36)  NOT NULL,"
            + "  `target_name`   VARCHAR(100),"
            + "  `rank_name`     VARCHAR(64),"
            + "  `punishment_id` VARCHAR(16),"
            + "  `duration_ms`   BIGINT       NOT NULL DEFAULT 0,"
            + "  `permanent`     BOOLEAN      NOT NULL DEFAULT 0,"
            + "  `reason`        VARCHAR(255),"
            + "  `silent`        BOOLEAN      NOT NULL DEFAULT 0,"
            // Who did it. actor_uuid is their linked Minecraft account and becomes
            // Phoenix's issuedBy; actor_label is the Discord identity, and is the
            // record that survives them never having linked one.
            + "  `actor_uuid`    VARCHAR(36),"
            + "  `actor_label`   VARCHAR(100) NOT NULL,"
            + "  `status`        VARCHAR(16)  NOT NULL DEFAULT 'pending',"
            + "  `result`        VARCHAR(255),"
            + "  `created_at`    DATETIME     NOT NULL,"
            // Null means "any server will do", which is true of a ban. A restart
            // is the opposite: it has to happen on one named box.
            + "  `target_server` VARCHAR(64),"
            + "  `claimed_by`    VARCHAR(64),"
            + "  `claimed_at`    DATETIME,"
            + "  `done_at`       DATETIME,"
            + "  PRIMARY KEY (`id`),"
            // The poll is "the pending ones, oldest first", and it runs every
            // second on every server forever. It gets an index.
            + "  INDEX `idx_pending` (`status`, `id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    /** Existing installs predate server targeting. */
    private static final String MIGRATE =
            "ALTER TABLE `mod_actions` ADD COLUMN IF NOT EXISTS `target_server` VARCHAR(64)";

    // A job either names no server — anybody may take it — or names this one.
    private static final String SELECT_PENDING =
            "SELECT * FROM `mod_actions` WHERE `status` = 'pending'"
            + " AND (`target_server` IS NULL OR `target_server` = ?)"
            + " ORDER BY `id` LIMIT " + BATCH;

    private static final String CLAIM =
            "UPDATE `mod_actions` SET `status` = 'running', `claimed_by` = ?, `claimed_at` = NOW()"
            + " WHERE `id` = ? AND `status` = 'pending'";

    private static final String FINISH =
            "UPDATE `mod_actions` SET `status` = ?, `result` = ?, `done_at` = NOW() WHERE `id` = ?";

    private final Main plugin;
    private final DatabaseManager database;
    private final String node;

    public ActionQueue(Main plugin, DatabaseManager database, String node) {
        this.plugin = plugin;
        this.database = database;
        this.node = node;
    }

    public void createTables() throws SQLException {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(CREATE);
            try {
                st.executeUpdate(MIGRATE);
            } catch (SQLException ignored) {
                // Older MySQL lacks ADD COLUMN IF NOT EXISTS; the column is either
                // already there or this server predates it. Neither should stop
                // bans from landing.
            }
        }
    }

    /** One queued job, as read off the table. */
    private static final class Job {
        long id;
        String action;
        UUID target;
        String rankName;
        String punishmentId;
        long durationMs;
        boolean permanent;
        String reason;
        boolean silent;
        UUID actor;
    }

    /**
     * Called on a timer, off the main thread. Claims what it can and hands each
     * job to the server thread to run.
     */
    public void poll() {
        if (database == null) return;
        Phoenix phoenix = Phoenix.getInstance();
        // Nothing is claimed while the core is down. Claiming a job we cannot
        // execute would mark it running on a server that is about to fail it, and
        // no other server would ever pick it up.
        if (phoenix == null || !phoenix.isApiEnabled()) return;

        List<Job> claimed = new ArrayList<>();
        try (Connection conn = database.getConnection()) {
            for (Job job : readPending(conn)) {
                try (PreparedStatement ps = conn.prepareStatement(CLAIM)) {
                    ps.setString(1, node);
                    ps.setLong(2, job.id);
                    // Exactly one server's UPDATE matches. Everybody else loses
                    // the race here rather than double-banning somebody.
                    if (ps.executeUpdate() == 1) claimed.add(job);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the action queue: " + e.getMessage());
            return;
        }

        // Phoenix's handlers touch online players and the core's own caches, so
        // they are called from the server thread. The database work either side
        // of that is not.
        for (Job job : claimed) {
            plugin.getServer().getScheduler().runTask(plugin, () -> run(job));
        }
    }

    private List<Job> readPending(Connection conn) throws SQLException {
        List<Job> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_PENDING)) {
            ps.setString(1, node);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Job j = new Job();
                    j.id = rs.getLong("id");
                    j.action = rs.getString("action");
                    j.rankName = rs.getString("rank_name");
                    j.punishmentId = rs.getString("punishment_id");
                    j.durationMs = rs.getLong("duration_ms");
                    j.permanent = rs.getBoolean("permanent");
                    j.reason = rs.getString("reason");
                    j.silent = rs.getBoolean("silent");

                    String target = rs.getString("target_uuid");
                    try {
                        j.target = UUID.fromString(target);
                    } catch (IllegalArgumentException e) {
                        // Can never succeed, so it is failed now rather than
                        // claimed and retried until somebody notices.
                        finish(j.id, "failed", "target_uuid is not a UUID: " + target);
                        continue;
                    }
                    String actor = rs.getString("actor_uuid");
                    j.actor = actor == null ? CONSOLE : parseOr(actor, CONSOLE);
                    out.add(j);
                }
            }
        }
        return out;
    }

    private static UUID parseOr(String s, UUID fallback) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** On the server thread. */
    private void run(Job job) {
        try {
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix == null || !phoenix.isApiEnabled()) {
                finishAsync(job.id, "failed", "Phoenix API went away mid-job");
                return;
            }
            // null means it worked and there is nothing to add; anything else is
            // the reason it did not — except an "ok:" prefix, which is a success
            // that has something worth saying ("cleared 3", "restart in 60s").
            String result = execute(phoenix, job);
            boolean ok = result == null || result.startsWith("ok:");
            finishAsync(
                    job.id,
                    ok ? "done" : "failed",
                    result == null ? "ok" : (ok ? result.substring(3) : result));
        } catch (Exception e) {
            // A job that throws must be marked failed, never left running: the
            // website is watching this row to tell somebody whether their ban
            // landed, and "running forever" is the one answer that helps nobody.
            plugin.getLogger().log(Level.SEVERE, "Action #" + job.id + " (" + job.action + ") threw", e);
            finishAsync(job.id, "failed", String.valueOf(e.getMessage()));
        }
    }

    /** @return null on success, or a sentence saying why it did not happen. */
    private String execute(Phoenix phoenix, Job job) {
        if (job.action == null) return "no action";
        switch (job.action) {
            case "ban":       return punish(phoenix, job, PunishmentType.BAN);
            case "mute":      return punish(phoenix, job, PunishmentType.MUTE);
            case "kick":      return punish(phoenix, job, PunishmentType.KICK);
            case "blacklist": return punish(phoenix, job, PunishmentType.BLACKLIST);
            case "warn":      return punish(phoenix, job, PunishmentType.WARN);
            case "revoke":    return revoke(phoenix, job);
            case "grant":     return grant(phoenix, job);
            case "ungrant":   return ungrant(phoenix, job);
            case "alert":     return alert(job);
            case "logout":    return logout(phoenix, job);
            case "cooldowns": return clearCooldowns(phoenix, job);
            case "security":  return resetSecurity(phoenix, job);
            case "vpn_allow": return vpnBypass(phoenix, job, true);
            case "vpn_deny":  return vpnBypass(phoenix, job, false);
            case "undisguise": return undisguise(phoenix, job);
            case "reboot":    return reboot(phoenix, job);
            case "reboot_cancel": return rebootCancel(phoenix);
            default:          return "unknown action: " + job.action;
        }
    }

    private String punish(Phoenix phoenix, Job job, PunishmentType type) {
        IPunishment p = phoenix.getPunishmentHandler().createEmptyPunishment();
        p.setUuid(UUID.randomUUID());
        p.setPunishmentID(IPunishment.getRandomSaltedString(ID_LENGTH));
        p.setPunishmentType(type);
        p.setTarget(job.target);
        p.setIssuedBy(job.actor);
        p.setIssuedOn(node);
        p.setIssuedAt(System.currentTimeMillis());
        p.setReason(job.reason);
        p.setSilent(job.silent);
        p.setActive(true);

        // A kick is an event: it happens once and there is nothing to expire. The
        // core stores it permanent-and-active forever and that is correct — see
        // the website's lib/punishments for why anything reading `active: true`
        // as "restricted right now" gets kicks badly wrong. A warning is the same
        // shape: it is a thing that happened, not a state you are in.
        if (type == PunishmentType.KICK || type == PunishmentType.WARN) {
            p.setPermanent(true);
            p.setDuration(0L);
        } else {
            p.setPermanent(job.permanent);
            p.setDuration(job.permanent ? 0L : job.durationMs);
        }

        phoenix.getPunishmentHandler().executePunishment(p);
        return null;
    }

    private String revoke(Phoenix phoenix, Job job) {
        if (job.punishmentId == null) return "no punishment id";
        IPunishment p = phoenix.getPunishmentHandler().findByPunishmentID(job.punishmentId);
        if (p == null) return "no punishment with id " + job.punishmentId;
        if (!p.isActive()) return "punishment " + job.punishmentId + " is not active";

        p.setRemovedBy(job.actor);
        p.setRemovedOn(node);
        p.setRemovedAt(System.currentTimeMillis());
        p.setRemovedReason(job.reason);
        p.setActive(false);
        phoenix.getPunishmentHandler().executeRevokePunishment(p);
        return null;
    }

    private String grant(Phoenix phoenix, Job job) {
        IRank rank = phoenix.getRankHandler().getRank(job.rankName);
        if (rank == null) return "no rank named " + job.rankName;

        IProfile profile = phoenix.getProfileHandler().getProfile(job.target);
        if (profile == null) return "no profile for " + job.target;

        // Global scope: the website has no concept of a per-server rank, so it
        // must not quietly create one that applies only on whichever box happened
        // to claim the job.
        List<IScope> scopes = Collections.singletonList(phoenix.getRankHandler().getGlobalScope());
        phoenix.getGrantHandler().grant(
                job.actor, profile, job.permanent ? 0L : job.durationMs, job.permanent, job.reason, rank, scopes);
        return null;
    }

    private String ungrant(Phoenix phoenix, Job job) {
        IRank rank = phoenix.getRankHandler().getRank(job.rankName);
        if (rank == null) return "no rank named " + job.rankName;

        IProfile profile = phoenix.getProfileHandler().getProfile(job.target);
        if (profile == null) return "no profile for " + job.target;

        IGrant grant = profile.getGrantAllScopes(rank);
        // Not a failure: it means the rank is already gone, which is the state the
        // caller asked for. Demoting somebody twice should not read as an error.
        if (grant == null) return null;

        phoenix.getGrantHandler().ungrant(job.actor, profile, grant, job.reason);
        return null;
    }

    // --- the smaller powers -------------------------------------------------
    //
    // None of these punish anybody. They are the things a moderator needs when
    // something is stuck rather than when somebody misbehaved — a ghost session,
    // a cooldown that will not clear, a locked-out admin, a legitimate VPN.

    /** Clears a stale session — the fix for somebody the network thinks is still on. */
    private String logout(Phoenix phoenix, Job job) {
        phoenix.getLoginHandler().logoutPlayer(job.target);
        return null;
    }

    private String clearCooldowns(Phoenix phoenix, Job job) {
        int had = phoenix.getCooldownHandler().getCooldownCount(job.target);
        phoenix.getCooldownHandler().clearCooldowns(job.target);
        // Say how many there were: "cleared 0" is a useful answer when somebody
        // swears they are still on cooldown.
        return had == 0 ? "ok:there were none" : "ok:cleared " + had;
    }

    /**
     * Clears the core's security hold on an account — what produces
     * "[Security] Unverified User" and locks a staff member out of their own rank.
     */
    private String resetSecurity(Phoenix phoenix, Job job) {
        if (!phoenix.getSecurityHandler().hasSecurity(job.target)) return null; // already clear
        phoenix.getSecurityHandler().removeSecurity(job.target);
        return null;
    }

    private String vpnBypass(Phoenix phoenix, Job job, boolean allow) {
        if (allow) phoenix.getAntiVPNHandler().whitelist(job.target, true);
        else phoenix.getAntiVPNHandler().unwhitelist(job.target, true);
        return null;
    }

    private String undisguise(Phoenix phoenix, Job job) {
        IProfile profile = phoenix.getProfileHandler().getProfile(job.target);
        if (profile == null) return "no profile for " + job.target;
        phoenix.getDisguiseHandler().undisguise(profile, true);
        return null;
    }

    /**
     * A restart, with the countdown the players already know.
     *
     * This is why jobs can name a server: a reboot happens where it is run, so
     * "restart Bedwars-2" has to be claimed by Bedwars-2 and nobody else.
     */
    private String reboot(Phoenix phoenix, Job job) {
        long seconds = Math.max(0L, job.durationMs / 1000L);
        phoenix.getRebootHandler().reboot(plugin.getServer().getConsoleSender(), seconds);
        return "ok:restart scheduled in " + seconds + "s on " + node;
    }

    private String rebootCancel(Phoenix phoenix) {
        if (!phoenix.getRebootHandler().isRebootScheduled()) return "ok:no restart was scheduled";
        phoenix.getRebootHandler().cancel(plugin.getServer().getConsoleSender());
        return "ok:restart cancelled on " + node;
    }

    /** The core's own staff alert prefix, as it appears in game. */
    private static final String ALERT_PREFIX = "&8[&4Alert&8] &r";

    /**
     * A staff alert, sent the way the core sends its own.
     *
     * This goes through Phoenix rather than through Bukkit, which is the whole
     * point: BukkitAPI.broadcastToStaff puts it on the core's staff channel, so it
     * reaches staff on every server and every proxy — not only the players
     * connected to whichever box happened to claim the job. That is also why this
     * is a claimed action and not one of Broadcaster's fan-out rows: exactly one
     * server may send it, or the network sees it once per server.
     */
    private String alert(Job job) {
        if (job.reason == null || job.reason.isEmpty()) return "no message";
        BukkitAPI.broadcastToStaff(ChatColor.translateAlternateColorCodes('&', ALERT_PREFIX + job.reason));
        return null;
    }

    private void finishAsync(long id, String status, String result) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> finish(id, status, result));
    }

    private void finish(long id, String status, String result) {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(FINISH)) {
            ps.setString(1, status);
            ps.setString(2, result == null ? null : result.substring(0, Math.min(result.length(), 255)));
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not close out action #" + id + ": " + e.getMessage());
        }
    }
}
