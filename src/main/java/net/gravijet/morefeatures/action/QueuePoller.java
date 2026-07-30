package net.gravijet.morefeatures.action;

import net.gravijet.morefeatures.Main;
import net.gravijet.morefeatures.database.DatabaseManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * One timer, one connection, all three queues.
 *
 * Each queue used to run its own repeating task and take its own connection out
 * of the pool every time it looked. Three servers' worth of that is three
 * borrows and three returns per second per server for work that is almost always
 * "nothing pending" — and the three tasks were staggered, so the pool never got
 * a quiet moment either.
 *
 * They read from the same database on the same schedule, so they share the trip:
 * one borrow, three cheap indexed lookups, one return. That leaves enough
 * headroom to poll <i>twice</i> as often as before while still costing the pool
 * a third of what the old shape did — the website's Ban button lands sooner and
 * the database sees less of us, both.
 *
 * A cycle that overruns is skipped rather than queued behind the last one. If
 * the database is slow enough that a poll takes longer than the interval,
 * stacking more pollers on top of it is the last thing that helps.
 */
public final class QueuePoller implements Runnable {

    private final Main plugin;
    private final DatabaseManager database;
    private final ActionQueue actions;
    private final ConfigActionQueue configs;
    private final Broadcaster broadcasts;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Any of the queues may be null — each one is allowed to fail to start on its own. */
    public QueuePoller(Main plugin, DatabaseManager database, ActionQueue actions,
                       ConfigActionQueue configs, Broadcaster broadcasts) {
        this.plugin = plugin;
        this.database = database;
        this.actions = actions;
        this.configs = configs;
        this.broadcasts = broadcasts;
    }

    /** True when there is anything at all to poll for. */
    public boolean hasWork() {
        return actions != null || configs != null || broadcasts != null;
    }

    @Override
    public void run() {
        if (database == null) return;
        if (!running.compareAndSet(false, true)) return;
        try (Connection conn = database.getConnection()) {
            // Each queue is tried on its own so a failure in one — a missing
            // table, a malformed row, a handler the core no longer has — does not
            // silently stop the others. A ban must still land when the rank
            // editor's table is broken.
            if (actions != null)    poll(conn, "action",    actions::poll);
            if (configs != null)    poll(conn, "config",    configs::poll);
            if (broadcasts != null) poll(conn, "broadcast", broadcasts::poll);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not reach the queue database: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    @FunctionalInterface
    private interface Poll {
        void on(Connection conn) throws SQLException;
    }

    private void poll(Connection conn, String what, Poll poll) {
        try {
            poll.on(conn);
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read the " + what + " queue: " + e.getMessage());
        }
    }
}
