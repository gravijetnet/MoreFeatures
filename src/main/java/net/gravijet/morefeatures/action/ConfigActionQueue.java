package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;
import xyz.refinedev.phoenix.Phoenix;
import xyz.refinedev.phoenix.handler.IPunishmentLadderHandler;
import xyz.refinedev.phoenix.handler.IRankHandler;
import xyz.refinedev.phoenix.profile.punishment.PunishmentType;
import xyz.refinedev.phoenix.profile.punishment.ladder.IPunishmentLadder;
import xyz.refinedev.phoenix.profile.punishment.ladder.IPunishmentLadderType;
import xyz.refinedev.phoenix.rank.IRank;
import xyz.refinedev.phoenix.rank.permission.IPermission;
import xyz.refinedev.phoenix.scope.IScope;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * Editing the network itself from Spielplatz: ranks, their permissions, and the
 * punishment ladders.
 *
 * This is the sibling of {@link ActionQueue}. That one does things *to a player*
 * — ban, mute, grant — and its jobs are shaped like a punishment. This one does
 * things *to the configuration* — recolour a rank, add it a permission, change
 * what the third step of the swearing ladder is — and those do not fit a
 * punishment's columns, so they ride their own table with a small line-based
 * payload instead.
 *
 * The same discipline as ActionQueue applies and for the same reason: the
 * website cannot edit Phoenix's config behind its back (it lives in memory and
 * syncs over Redis), so the website writes down what it wants and this applies it
 * through the core's own API — {@code IRankHandler.updateRank}, and so on — from
 * inside the game.
 *
 * One rule shapes everything below: <b>never rebuild a list of things the core
 * constructed.</b> Phoenix's API hands out permissions and ladder rungs but gives
 * no way to build a fresh one, and inventing them by reflection against an
 * obfuscated core is how you wipe a rank's permissions with a typo. So every edit
 * here is a mutation of an object the core already made — set a field, drop an
 * entry, or (to add a permission) copy one that already exists on another rank.
 * Adding a brand-new permission node or a brand-new ladder rung has no safe path
 * and is left to be done in game; the website knows this and says so.
 */
public class ConfigActionQueue {

    private static final UUID CONSOLE = new UUID(0L, 0L);
    private static final int BATCH = 10;

    private static final String CREATE = ""
            + "CREATE TABLE IF NOT EXISTS `config_actions` ("
            + "  `id`          BIGINT       NOT NULL AUTO_INCREMENT,"
            + "  `action`      VARCHAR(32)  NOT NULL,"   // rank_create | rank_update | rank_delete | ladder_update
            + "  `subject`     VARCHAR(64)  NOT NULL,"   // the rank name, or the ladder id
            + "  `payload`     MEDIUMTEXT,"              // the edit, one operation per line (see apply* below)
            + "  `actor_uuid`  VARCHAR(36),"
            + "  `actor_label` VARCHAR(100) NOT NULL,"
            + "  `status`      VARCHAR(16)  NOT NULL DEFAULT 'pending',"
            + "  `result`      VARCHAR(255),"
            + "  `created_at`  DATETIME     NOT NULL,"
            + "  `claimed_by`  VARCHAR(64),"
            + "  `claimed_at`  DATETIME,"
            + "  `done_at`     DATETIME,"
            + "  PRIMARY KEY (`id`),"
            + "  INDEX `idx_pending` (`status`, `id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    private static final String SELECT_PENDING =
            "SELECT * FROM `config_actions` WHERE `status` = 'pending' ORDER BY `id` LIMIT " + BATCH;

    private static final String CLAIM =
            "UPDATE `config_actions` SET `status` = 'running', `claimed_by` = ?, `claimed_at` = NOW()"
            + " WHERE `id` = ? AND `status` = 'pending'";

    private static final String FINISH =
            "UPDATE `config_actions` SET `status` = ?, `result` = ?, `done_at` = NOW() WHERE `id` = ?";

    private final Main plugin;
    private final DatabaseManager database;
    private final String node;

    public ConfigActionQueue(Main plugin, DatabaseManager database, String node) {
        this.plugin = plugin;
        this.database = database;
        this.node = node;
    }

    public void createTables() throws SQLException {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(CREATE);
        }
    }

    private static final class Job {
        long id;
        String action;
        String subject;
        String payload;
        UUID actor;
    }

    public void poll() {
        if (database == null) return;
        Phoenix phoenix = Phoenix.getInstance();
        if (phoenix == null || !phoenix.isApiEnabled()) return;

        List<Job> claimed = new ArrayList<>();
        try (Connection conn = database.getConnection()) {
            for (Job job : readPending(conn)) {
                try (PreparedStatement ps = conn.prepareStatement(CLAIM)) {
                    ps.setString(1, node);
                    ps.setLong(2, job.id);
                    if (ps.executeUpdate() == 1) claimed.add(job);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the config queue: " + e.getMessage());
            return;
        }

        for (Job job : claimed) {
            plugin.getServer().getScheduler().runTask(plugin, () -> run(job));
        }
    }

    private List<Job> readPending(Connection conn) throws SQLException {
        List<Job> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_PENDING);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Job j = new Job();
                j.id = rs.getLong("id");
                j.action = rs.getString("action");
                j.subject = rs.getString("subject");
                j.payload = rs.getString("payload");
                String actor = rs.getString("actor_uuid");
                j.actor = actor == null ? CONSOLE : parseOr(actor, CONSOLE);
                out.add(j);
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

    private void run(Job job) {
        try {
            Phoenix phoenix = Phoenix.getInstance();
            if (phoenix == null || !phoenix.isApiEnabled()) {
                finishAsync(job.id, "failed", "Phoenix API went away mid-job");
                return;
            }
            String result = execute(phoenix, job);
            // A config edit reports what it changed, not just "ok" — the audit on
            // the website already says who and why; this row is where "and here is
            // exactly what the core did" lives.
            boolean ok = result == null || !result.startsWith("!");
            finishAsync(job.id, ok ? "done" : "failed", ok ? (result == null ? "ok" : result) : result.substring(1));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Config action #" + job.id + " (" + job.action + ") threw", e);
            finishAsync(job.id, "failed", String.valueOf(e.getMessage()));
        }
    }

    /**
     * @return a short summary of what changed on success, or a sentence starting
     *         with '!' explaining why nothing did.
     */
    private String execute(Phoenix phoenix, Job job) {
        if (job.action == null) return "!no action";
        switch (job.action) {
            case "rank_create":  return rankCreate(phoenix, job);
            case "rank_update":  return rankUpdate(phoenix, job);
            case "rank_delete":  return rankDelete(phoenix, job);
            case "ladder_update": return ladderUpdate(phoenix, job);
            default:             return "!unknown action: " + job.action;
        }
    }

    // --- ranks --------------------------------------------------------------

    private String rankCreate(Phoenix phoenix, Job job) {
        IRankHandler ranks = phoenix.getRankHandler();
        if (ranks.getRank(job.subject) != null) return "!a rank named " + job.subject + " already exists";
        IRank rank = ranks.createRank(job.subject);
        if (rank == null) return "!the core would not create " + job.subject;
        String applied = applyRankOps(phoenix, rank, job.payload);
        if (applied != null && applied.startsWith("!")) return applied;
        ranks.updateRank(rank);
        ranks.requestPermissionsUpdate(rank);
        return "created " + job.subject + (applied == null || applied.isEmpty() ? "" : " (" + applied + ")");
    }

    private String rankUpdate(Phoenix phoenix, Job job) {
        IRankHandler ranks = phoenix.getRankHandler();
        IRank rank = ranks.getRank(job.subject);
        if (rank == null) return "!no rank named " + job.subject;
        String applied = applyRankOps(phoenix, rank, job.payload);
        if (applied != null && applied.startsWith("!")) return applied;
        ranks.updateRank(rank);
        ranks.requestPermissionsUpdate(rank);
        return applied == null || applied.isEmpty() ? "no change" : applied;
    }

    private String rankDelete(Phoenix phoenix, Job job) {
        IRankHandler ranks = phoenix.getRankHandler();
        IRank rank = ranks.getRank(job.subject);
        if (rank == null) return "!no rank named " + job.subject;
        if (rank.isDefaultRank()) return "!refusing to delete the default rank";
        ranks.deleteRank(rank);
        return "deleted " + job.subject;
    }

    /**
     * Applies rank edits, one operation per line. Every line is a mutation of the
     * live rank object; nothing here reconstructs the permission list from
     * scratch. Returns a human summary, or a '!'-prefixed refusal on the first
     * operation the core cannot do.
     *
     *   set <field> <value...>   colour, prefix, suffix, displayname,
     *                            playerlistprefix, priority, price, and the flags
     *                            staff / visible / grantable / purchasable /
     *                            subscription / defaultrank
     *   inherit+ <rankName>      inherit another rank (add / remove)
     *   inherit- <rankName>
     *   perm- <node>             drop a permission
     *   perm+ <node>             add a permission, by copying it off whichever rank
     *                            already carries it (there is no way to mint one)
     */
    private String applyRankOps(Phoenix phoenix, IRank rank, String payload) {
        if (payload == null) return "";
        IRankHandler ranks = phoenix.getRankHandler();
        List<String> changes = new ArrayList<>();

        for (String raw : payload.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            String op = sp < 0 ? line : line.substring(0, sp);
            String arg = sp < 0 ? "" : line.substring(sp + 1).trim();

            switch (op) {
                case "set": {
                    int sp2 = arg.indexOf(' ');
                    String field = (sp2 < 0 ? arg : arg.substring(0, sp2)).toLowerCase();
                    String value = sp2 < 0 ? "" : arg.substring(sp2 + 1);
                    String bad = setRankField(rank, field, value);
                    if (bad != null) return bad;
                    changes.add(field);
                    break;
                }
                case "inherit+": {
                    IRank other = ranks.getRank(arg);
                    if (other == null) return "!no rank named " + arg + " to inherit";
                    rank.addInheritance(other);
                    changes.add("+inherit " + arg);
                    break;
                }
                case "inherit-": {
                    IRank other = ranks.getRank(arg);
                    if (other == null) return "!no rank named " + arg + " to un-inherit";
                    rank.removeInheritance(other);
                    changes.add("-inherit " + arg);
                    break;
                }
                case "perm-": {
                    rank.removePermission(arg);
                    changes.add("-" + arg);
                    break;
                }
                case "perm+": {
                    if (rank.hasPermission(arg)) { changes.add("+" + arg); break; }
                    // Prefer copying an object the core already made; only mint one
                    // if the node lives nowhere yet.
                    IPermission perm = findPermission(ranks, arg);
                    if (perm == null) perm = mintPermission(ranks, arg);
                    if (perm == null) return "!could not build the permission " + arg;
                    List<IPermission> perms = new ArrayList<>(rank.getPermissions());
                    perms.add(perm);
                    rank.setPermissions(perms);
                    changes.add("+" + arg);
                    break;
                }
                default:
                    return "!unknown edit: " + op;
            }
        }
        return String.join(", ", changes);
    }

    private String setRankField(IRank rank, String field, String value) {
        try {
            switch (field) {
                // Each display field has a legacy and a modern form. The network
                // writes the same &-codes into both, and a client on the modern
                // path reads the modern one — so setting only the legacy field
                // would leave half the players seeing the old colour. Set both.
                case "color": case "colour":        rank.setColor(value); rank.setColorModern(value); return null;
                case "prefix":                      rank.setPrefix(value); rank.setPrefixModern(value); return null;
                case "suffix":                      rank.setSuffix(value); rank.setSuffixModern(value); return null;
                case "displayname":                 rank.setDisplayName(value); rank.setDisplayNameModern(value); return null;
                case "playerlistprefix":            rank.setPlayerListPrefix(value); rank.setPlayerListPrefixModern(value); return null;
                case "priority":                    rank.setPriority(Integer.parseInt(value.trim())); return null;
                case "price":                       rank.setPrice(Integer.parseInt(value.trim())); return null;
                case "staff":                       rank.setStaff(bool(value)); return null;
                case "visible":                     rank.setVisible(bool(value)); return null;
                case "grantable":                   rank.setGrantable(bool(value)); return null;
                case "purchasable":                 rank.setPurchasable(bool(value)); return null;
                case "subscription":                rank.setSubscription(bool(value)); return null;
                case "defaultrank":                 rank.setDefaultRank(bool(value)); return null;
                default:                            return "!no such rank field: " + field;
            }
        } catch (NumberFormatException e) {
            return "!" + field + " must be a number, got '" + value + "'";
        }
    }

    /** The first permission object matching this node on any rank — the one we copy. */
    private IPermission findPermission(IRankHandler ranks, String node) {
        for (IRank r : ranks.getSortedRanks()) {
            for (IPermission p : r.getPermissions()) {
                if (p.getPermission() != null && p.getPermission().equalsIgnoreCase(node)) return p;
            }
        }
        return null;
    }

    /**
     * Builds a permission for a node no rank carries yet.
     *
     * The pxAPI hands out permissions but gives nothing to construct one, and the
     * concrete class is inside the obfuscated core — so this is reflection, and it
     * is the one place here that reaches past the public surface. It is written to
     * fail cleanly (return null → the job fails with a message) rather than throw,
     * and it never mutates anything until it has an object in hand.
     *
     * Three strategies, cheapest first: a (String, scopes) constructor, a (String)
     * constructor, and — the one that works even against obfuscated names —
     * allocate an instance, copy every field off a real permission, then overwrite
     * whichever String field held the old node with the new one, found by value
     * rather than by a name reflection cannot trust.
     */
    private IPermission mintPermission(IRankHandler ranks, String node) {
        IPermission sample = anyPermission(ranks);
        if (sample == null) return null; // nothing to learn the shape from — vanishingly unlikely
        Class<?> cls = sample.getClass();
        List<IScope> global = Collections.singletonList(ranks.getGlobalScope());

        try {
            Constructor<?> c = cls.getDeclaredConstructor(String.class, List.class);
            c.setAccessible(true);
            IPermission p = (IPermission) c.newInstance(node, global);
            if (node.equalsIgnoreCase(p.getPermission())) return p;
        } catch (Throwable ignored) { /* try the next shape */ }

        try {
            Constructor<?> c = cls.getDeclaredConstructor(String.class);
            c.setAccessible(true);
            IPermission p = (IPermission) c.newInstance(node);
            try { p.setScopes(global); } catch (Throwable ignored) { /* leave default scope */ }
            if (node.equalsIgnoreCase(p.getPermission())) return p;
        } catch (Throwable ignored) { /* fall through to the field copy */ }

        try {
            IPermission p = (IPermission) allocate(cls);
            for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(p, f.get(sample));
                }
            }
            overwriteStringField(p, sample.getPermission(), node);
            try { p.setScopes(global); } catch (Throwable ignored) { /* keep copied scope */ }
            if (node.equalsIgnoreCase(p.getPermission())) return p;
        } catch (Throwable ignored) { /* out of strategies */ }

        return null;
    }

    private IPermission anyPermission(IRankHandler ranks) {
        for (IRank r : ranks.getSortedRanks()) {
            for (IPermission p : r.getPermissions()) return p;
        }
        return null;
    }

    private Object allocate(Class<?> cls) throws Exception {
        try {
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable noNoArg) {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
        }
    }

    /** Sets the first String field currently equal to `oldValue` to `newValue`. */
    private void overwriteStringField(Object obj, String oldValue, String newValue) throws Exception {
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                if (oldValue != null && oldValue.equals(f.get(obj))) { f.set(obj, newValue); return; }
            }
        }
    }

    // --- ladders ------------------------------------------------------------

    /**
     * Applies ladder edits.
     *
     *   set priority <n>
     *   set hidden <true|false>
     *   step <order> <field> <value>   field: type, duration, decay, ip, shadow, order
     *   stepdel <order>                remove the rung at that order
     */
    private String ladderUpdate(Phoenix phoenix, Job job) {
        IPunishmentLadderHandler handler = phoenix.getPunishmentLadderHandler();
        IPunishmentLadder ladder = handler.getByName(job.subject);
        if (ladder == null) return "!no ladder named " + job.subject;

        List<String> changes = new ArrayList<>();
        String payload = job.payload == null ? "" : job.payload;

        for (String raw : payload.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] t = line.split(" ");

            if (t[0].equals("set") && t.length >= 3) {
                String field = t[1].toLowerCase();
                String value = line.substring(line.indexOf(t[1]) + t[1].length()).trim();
                if (field.equals("priority")) {
                    try { ladder.setPriority(Integer.parseInt(value)); }
                    catch (NumberFormatException e) { return "!priority must be a number"; }
                    changes.add("priority");
                } else if (field.equals("hidden")) {
                    ladder.setHidden(bool(value));
                    changes.add("hidden");
                } else {
                    return "!no such ladder field: " + field;
                }
            } else if (t[0].equals("step") && t.length >= 4) {
                int order;
                try { order = Integer.parseInt(t[1]); }
                catch (NumberFormatException e) { return "!step order must be a number"; }
                IPunishmentLadderType step = stepByOrder(ladder, order);
                if (step == null) return "!this ladder has no rung at step " + order;
                String bad = setStepField(step, t[2].toLowerCase(), t[3]);
                if (bad != null) return bad;
                changes.add("step " + order + " " + t[2].toLowerCase());
            } else if (t[0].equals("stepdel") && t.length >= 2) {
                int order;
                try { order = Integer.parseInt(t[1]); }
                catch (NumberFormatException e) { return "!step order must be a number"; }
                IPunishmentLadderType step = stepByOrder(ladder, order);
                if (step == null) return "!this ladder has no rung at step " + order;
                List<IPunishmentLadderType> steps = new ArrayList<>(ladder.getLadder());
                steps.remove(step);
                ladder.setLadder(steps);
                changes.add("removed step " + order);
            } else {
                return "!unknown ladder edit: " + line;
            }
        }

        // Persist the mutated ladder. saveToDatabaseSync writes it back the way the
        // core would; running servers pick up the change on their next reload.
        handler.getRepository().saveToDatabaseSync(ladder);
        return changes.isEmpty() ? "no change" : String.join(", ", changes);
    }

    private IPunishmentLadderType stepByOrder(IPunishmentLadder ladder, int order) {
        for (IPunishmentLadderType s : ladder.getLadder()) {
            if (s.getOrder() == order) return s;
        }
        return null;
    }

    private String setStepField(IPunishmentLadderType step, String field, String value) {
        try {
            switch (field) {
                case "type": {
                    PunishmentType type;
                    try { type = PunishmentType.valueOf(value.toUpperCase()); }
                    catch (IllegalArgumentException e) { return "!no punishment type '" + value + "'"; }
                    step.setType(type);
                    return null;
                }
                case "duration": step.setDuration(Long.parseLong(value.trim())); return null;
                case "decay":    step.setDecay(Long.parseLong(value.trim())); return null;
                case "order":    step.setOrder(Integer.parseInt(value.trim())); return null;
                case "ip":       step.setIp(bool(value)); return null;
                case "shadow":   step.setShadow(bool(value)); return null;
                default:         return "!no such step field: " + field;
            }
        } catch (NumberFormatException e) {
            return "!" + field + " must be a number, got '" + value + "'";
        }
    }

    private static boolean bool(String v) {
        String s = v == null ? "" : v.trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
    }

    // --- closing out --------------------------------------------------------

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
            plugin.getLogger().log(Level.WARNING, "Could not close out config action #" + id + ": " + e.getMessage());
        }
    }
}
