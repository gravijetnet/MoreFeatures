# Bug Report — MoreFeatures Plugin

Exhaustive audit of all 23 Java source files. Issues are grouped by severity.

---

## CRITICAL

### C1 — SQL injection via stat key in `getStatValue` / `updateStat` / `incrementStat`
**File:** `database/DatabaseManager.java` — lines 208, 229, 244  
The `keyToColumn()` whitelist runs before the pre-built SQL maps are looked up, so the protection is correct **today**. However, nothing stops a future caller from calling `GET_STAT_SQL.get(arbitraryKey)` directly, bypassing `keyToColumn()`. The map is package-accessible (`static final`) and uses the raw column name as the key. A caller in the same package that skips the public API methods could call the map directly with a crafted key. The map should be `private`.

---

## HIGH

### H1 — Race condition: `databaseManager` null-check inside async `thenAccept` is not atomic with subsequent use
**File:** `Main.java` — lines 302–316  
`databaseManager` is checked for null inside a `thenAccept` callback that runs on the Phoenix thread pool (line 305). Between the null check and the subsequent `databaseManager.updateStat(...)` calls (lines 313–315), `onDisable()` can run on the main thread: it calls `databaseManager.close()` (line 130) and the pool is shut down. The `databaseManager` field itself is never set to `null`, so the null guard never fires, but `updateStat` then executes against a closed HikariCP pool and throws a `SQLException` that is not caught in this path (no try-catch wraps `thenAccept`). The exception propagates as an unhandled future failure; `exceptionally` at line 318 only catches exceptions from `getAllPunishments`, not from `thenAccept`.

### H2 — `cancelTasks(this)` races against the final async quit-sync submitted in `PlayerListener.onPlayerQuit`
**File:** `Main.java` — line 128; `listener/PlayerListener.java` — lines 73–75  
`onDisable()` calls `cancelPlaytimeTimer` (line 124) then `cancelTasks(this)` (line 128). But `PlayerListener.onPlayerQuit` submits a **new** async task (`runTaskAsynchronously`) to do the final playtime sync *before* `cancelPlaytimeTimer` is called (because quit events fire before disable). `cancelTasks(this)` races against that submitted-but-not-yet-started task and may cancel it, silently losing the last playtime sync for all players online at shutdown.

### H3 — Netty I/O thread calls `player.hasPermission()` — not thread-safe
**File:** `autoplace/AutoPlaceDecoder.java` — line 184  
`handleBlockPlace` runs on the Netty I/O thread. `player.hasPermission()` iterates the player's effective permissions map, which can be written by permission plugins on the main thread at the same time. This is an unsynchronised concurrent read/write that can cause a `ConcurrentModificationException` or return stale permission data.

### H4 — Netty I/O thread calls `player.getWorld()` — not thread-safe
**File:** `autoplace/AutoPlaceDecoder.java` — line 203  
`((CraftWorld) player.getWorld()).getHandle()` is called on the Netty thread. `CraftPlayer.getWorld()` reads the player's current world reference, which is updated on the main thread during teleports and dimension changes. Reading it from the Netty thread is an unsynchronised access that can return a stale world reference or throw NPE during a world change.

### H5 — Netty I/O thread calls `player.isSneaking()` — not thread-safe
**File:** `autoplace/AutoPlaceDecoder.java` — line 207  
`player.isSneaking()` reads NMS state that is written from the main thread. Reading it on the Netty thread is a data race and can observe stale values.

### H6 — `uninject` removes the decoder from the map before the pipeline removal executes, creating a window where re-injection is not guarded
**File:** `autoplace/AutoPlaceInjector.java` — lines 47–61  
`decoders.remove(player)` runs immediately (main thread), but the actual `pipeline.remove(HANDLER_NAME)` is submitted to the event loop and runs asynchronously. If `inject()` is called again before the event-loop task runs (e.g. rapid login/logout), `inject()` calls `uninject()` first; `decoders.remove` returns null (already removed), so `uninject` thinks nothing needs to be removed from the pipeline. Then `inject` adds a new handler. Meanwhile the original event-loop removal task fires and removes the *new* handler, leaving the player without any AutoPlace detection.

### H7 — `RankListener.updateRank` calls `getDatabaseManager().updatePlayerRank(...)` without a null check
**File:** `listener/RankListener.java` — lines 37–38  
If the plugin is loaded with `phoenix-mysql.enabled: false`, `databaseManager` is null. The async lambda calls `plugin.getDatabaseManager().updatePlayerRank(...)` without any null guard, causing NPE. `PunishmentListener` has a null check at line 21; this listener does not.

---

## MEDIUM

### M1 — `PunishmentListener` async lambda re-calls `getDatabaseManager()` without null check
**File:** `listener/PunishmentListener.java` — line 33  
The outer handler guards against null at line 21, but the inner async lambda at line 33 calls `plugin.getDatabaseManager().incrementStat(...)` — a second dereference. If `onDisable()` runs between the outer check and the lambda execution, the pool is closed (though the reference is non-null), causing use-after-close.

### M2 — `setVolume` clamps a `byte` parameter, making the clamp useless for values > 127
**File:** `music/MusicManager.java` — lines 203–205  
The method signature is `setVolume(byte v)`. If called with `(byte) 200`, Java wraps it to `-56` before the method sees it. `Math.max(1, -56)` yields `1`, silently clamping an intended 200 to 1. The int-range validation in `MusicCommand.handleVolume` prevents this in the command path, but the method itself is publicly callable and the signature is misleading.

### M3 — `FullbrightManager.relightAllLoaded` collects chunks on the main thread but processes them in future ticks; unloaded chunks are silently skipped on disable
**File:** `fullbright/FullbrightManager.java` — lines 62–89  
When fullbright is toggled off, `revertLight(chunk)` must be called for every chunk. Chunks collected into `toProcess` may unload between collection and their batch turn. Line 82 checks `chunk.isLoaded()` and skips unloaded chunks — so those chunks are never reverted. Players in those chunks will see incorrect (maximum) lighting until the chunks are reloaded, at which point `ChunkLoadEvent` fires `relightChunk` which checks `if (!enabled)` and returns immediately, leaving maximum light permanently.

### M4 — `sendCancelPackets` may send the wrong slot update when a container GUI is open
**File:** `autoplace/AutoPlaceDecoder.java` — lines 320–329  
`container.getSlot(inventory, inventory.itemInHandIndex)` retrieves a slot from `entityPlayer.activeContainer`. If the player has a chest or crafting table open, `activeContainer` is the GUI container, not the player inventory. `itemInHandIndex` (0–8) may not map to a valid hotbar slot in the GUI container, returning a null slot (handled) or mapping to a GUI slot (wrong item sent). This can desync the client-side inventory display.

### M5 — `MusicConfig` accepts `url: null` YAML entries as the string `"null"` which passes the empty-check
**File:** `music/MusicConfig.java` — lines 42–49  
`String.valueOf(map.getOrDefault("url", ""))` converts a YAML `null` value to the string `"null"`. `!url.isEmpty()` is true for `"null"`, so it is stored as a valid entry. `SongDownloader` will attempt to fetch URL `"null"`, fail with "Rejected non-HTTP URL", and log a confusing error. It should use an explicit null check instead of `String.valueOf`.

### M6 — `SongDownloader.downloadFile` has no maximum download size limit
**File:** `music/util/SongDownloader.java` — lines 117–126  
The read loop has no byte-count cap. A URL pointing to an arbitrarily large file will be written to disk until storage is exhausted or an OOM occurs. NBS files are typically a few hundred KB at most; an upper bound (e.g. 20 MB) should be enforced.

### M7 — `SongDownloader` logs the full download URL including any embedded tokens or credentials
**File:** `music/util/SongDownloader.java` — line 61  
`logger.info("Downloading song: " + filename + " from " + url)` logs the raw URL. If the URL contains an API key or signed token in a query parameter, it is written to the server log and any log aggregator.

### M8 — `BridgeConfig` port is never validated to be in the range 1–65535
**File:** `config/BridgeConfig.java` — line 38  
`cfg.getInt("database.port", 3306)` accepts any integer. A misconfigured value like `0` or `99999` produces an invalid JDBC URL; HikariCP's connection error won't mention port range, making diagnosis harder. A simple `if (port < 1 || port > 65535)` guard should be added.

### M9 — `onNetworkLeave` async lambda calls `getDatabaseManager()` without rechecking for null
**File:** `listener/PlayerListener.java` — lines 87–93  
The outer guard on line 83 checks `getDatabaseManager() == null`, but the lambda on line 89 calls `plugin.getDatabaseManager().setPlayerOnline(...)` again. If `onDisable()` runs in the 5-tick delay window, the pool is closed and this throws use-after-close.

### M10 — `initPunishmentCounts` exceptions from `updateStat` inside `thenAccept` are not caught
**File:** `Main.java` — lines 313–316  
If any of the three `updateStat` calls throw (e.g. `SQLException` from a closed pool), the exception propagates out of `thenAccept` as a failed future stage. The `exceptionally` handler at line 318 only catches upstream exceptions from `getAllPunishments`; a downstream `thenAccept` failure creates a new failed future that is immediately discarded, silently dropping the error.

---

## LOW / INFORMATIONAL

### L1 — `startPlaytimeTimer` cancel + put are not atomic; concurrent calls for the same UUID can leak a timer
**File:** `Main.java` — lines 256–264  
`cancelPlaytimeTimer(uuid)` removes and cancels, then `playtimeTasks.put(uuid, taskId)` stores the new ID. If two threads call `startPlaytimeTimer` for the same UUID simultaneously (unlikely but possible during `PlayerJoinEvent` + plugin reload), both pass the remove check, both schedule timers, and only one ID is stored. The other timer runs indefinitely (leaking async DB writes every 5 minutes).

### L2 — `firstSeenFromPhoenix` falls back to `System.currentTimeMillis()` on exception, creating an incorrect `first_seen` for new players
**File:** `listener/PlayerListener.java` — lines 107–111  
The warning is logged, but using the current time as `first_seen` for a player who joined for the first time produces a `first_seen` equal to now rather than their actual first login from Phoenix history. For truly new players this is unavoidable; for existing players the UPSERT `LEAST(COALESCE(...))` prevents overwriting an earlier date, but the confusion arises when the fallback fires for an existing player who has never been upserted before.

### L3 — `AutoPlaceDecoder.requestedBlock` duplicate-position guard can mask one AutoPlace flag per block position
**File:** `autoplace/AutoPlaceDecoder.java` — lines 218–219  
When the same `shifted` position is placed twice in a row (client re-sends, or two-block AutoPlace that happens to target the same cell), the second packet is silently allowed through. This is intentional per the comment but means one flag is not counted per unique position, which can inflate the threshold required before detection fires.

### L4 — `TimeParser` parses bare integers (e.g. `"5"`) as zero total, with an error message that doesn't mention units
**File:** `music/util/TimeParser.java` — lines 45–61  
Input `"5"` matches the pattern with all three groups null (digits not followed by a unit letter don't match any group). `total = 0`, and the error is "Time value must be greater than zero." A more helpful message would say "Missing unit — use s, m, or h (e.g. 5s, 1m30s)."

### L5 — `MusicManager.stopAll()` copies keySet to ArrayList but iterates sequentially; not a bug but wastes an allocation
**File:** `music/MusicManager.java` — lines 171–176  
Minor allocation; informational.

### L6 — `FullbrightManager.MAX_LIGHT` byte array is cloned on every `applyMaxLight` call (once per section per chunk); for large worlds this is many small allocations per tick
**File:** `fullbright/FullbrightManager.java` — lines 100–101  
Each `applyMaxLight` call clones `MAX_LIGHT` twice per non-null section (sky + block light). A 20-section chunk produces 40 clone allocations. For 100 loaded chunks that is 4,000 allocations on enable. Not a correctness bug, but relevant for large servers.

### L7 — `AutoPlaceListener` uses `EventPriority.MONITOR` for inject/uninject; if another MONITOR listener at the same priority modifies the pipeline, ordering is undefined
**File:** `autoplace/AutoPlaceListener.java` — lines 17–25  
Informational; no current conflict known.

### L8 — `MusicCommand.handleForce` sends a message to `finalTarget` inside `onSuccess` without checking `finalTarget.isOnline()`
**File:** `music/command/MusicCommand.java` — line 169  
`playSongAsync` checks `player.isOnline()` before starting playback, returning without calling `onSuccess` if offline. So this is currently safe. However, if the online check is ever removed from `MusicManager`, `finalTarget.sendMessage(...)` on line 169 would be called for an offline player.

### L9 — `RickrollCommand` tab-completion at arg 1 suggests only online player names; a sender trying to rickroll themselves with a time arg must type the time without completion hints
**File:** `music/command/RickrollCommand.java` — lines 157–165  
UX issue only; not a bug.

### L10 — `BridgeConfig.SAFE_HOST` regex allows a hyphen anywhere in the host (including at the start or end of a label), which is invalid per RFC 1123
**File:** `config/BridgeConfig.java` — line 12  
Pattern `^[a-zA-Z0-9.\\-_]+$` allows `-.example.com` or `example-.com`. While MySQL's JDBC driver would reject such a hostname, the validation gives a false sense of security. The hyphen should only be allowed in the middle of a label.

### L11 — `SongDownloader` SSRF guard only checks `startsWith("http://")` / `startsWith("https://")` but does not reject `http://localhost`, `http://127.x.x.x`, `http://10.x.x.x`, etc.
**File:** `music/util/SongDownloader.java` — lines 94–96  
Redirects are disabled (correct), but the initial URL itself can point directly to internal network addresses. Since only admins configure `music.yml`, the threat model is low, but worth noting.

### L12 — `NetworkStatsSync.sync` issues a full `SELECT COUNT(*)` on every call (join, quit, punishment, periodic tick); no caching
**File:** `phoenix/NetworkStatsSync.java` — line 21  
`countPlayers()` is an unbounded index scan. On a busy server (frequent joins/quits) this may cause unnecessary DB load. The count could be cached with a short TTL.

### L13 — `AutoPlaceDecoder` does not reset `requestedBlock` when the player switches held item; a second placement of the same block at the same position with a different item type bypasses the duplicate guard
**File:** `autoplace/AutoPlaceDecoder.java` — lines 218–219  
Minor false-negative: if a player places block A at position P, swaps to block B, and places at position P again, `requestedBlock.equals(shifted)` returns true and the second placement is not evaluated. One AutoPlace flag may be missed.
