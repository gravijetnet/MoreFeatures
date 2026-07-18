# MoreFeatures

A Minecraft 1.8.8 plugin providing fullbright, anti-autoplace detection, and a PhoenixAPI–MySQL bridge.

---

## Table of Contents

- [Features](#features)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [Requirements](#requirements)

---

## Features

| Feature | Description |
|---------|-------------|
| **Fullbright** | Max light level server-wide, or night vision for a single player |
| **Anti-AutoPlace** | Detect and punish players placing blocks suspiciously fast |
| **PhoenixAPI Bridge** | Sync playtime, ranks, and punishment stats to a MySQL database |
| **Display** | Tablist sorting, nametag prefixes and the chat format, resolved from Phoenix and MBedwars |

---

## Commands

### `/fullbright`

Two independent modes.

**Server-wide** — all loaded chunks are forced to maximum light level for every player.

| Usage | Description |
|-------|-------------|
| `/fullbright on` | Enable server-wide fullbright |
| `/fullbright off` | Disable server-wide fullbright |
| `/fullbright toggle` | Toggle it (default if no argument given) |
| `/fullbright status` | Show whether it is currently enabled |

**Per player** — gives one player endless night vision, which admins can hand out to anyone. Requires `morefeatures.fullbright.others` when the target is someone else.

| Usage | Description |
|-------|-------------|
| `/fullbright <player>` | Toggle fullbright for that player |
| `/fullbright <player> on\|off` | Turn it on or off for that player |
| `/fullbright <player> status` | Show whether that player has it |

> A per-player grant lives in memory only. It survives a reconnect but is dropped when the server restarts.

---

### `/bridgesync`

Force an immediate sync of PhoenixAPI data (playtime, network stats) to the MySQL database. Runs asynchronously.

---

## Permissions

### Fullbright

| Permission | Description | Default |
|-----------|-------------|---------|
| `morefeatures.fullbright` | Use `/fullbright` at all, and toggle it server-wide | op |
| `morefeatures.fullbright.others` | Toggle fullbright for another player | op |

### Anti-AutoPlace

| Permission | Description | Default |
|-----------|-------------|---------|
| `morefeatures.autoplace.bypass` | Bypass AutoPlace detection entirely | op |
| `morefeatures.autoplace.alerts` | Receive staff alerts when a player is flagged | op |

### Display

| Permission | Default | Description |
|---|---|---|
| `morefeatures.chat.color` | op | Use `&`-colour codes in your own chat messages |

### Bridge

| Permission | Description | Default |
|-----------|-------------|---------|
| `bridge.sync` | Run `/bridgesync` | op |

---

## Configuration

### `config.yml` — Feature toggles

```yaml
phoenix-mysql:
  enabled: true     # Enable the PhoenixAPI → MySQL bridge

fullbright:
  enabled: true     # Enable the fullbright system on startup

autoplace:
  enabled: true     # Enable AutoPlace detection

display:
  enabled: true     # Publish %morefeatures_*% and manage the lobby chat format
```

---

### `display.yml` — Tablist, nametags and chat

Lobby and bedwars are worlds on the same server, and **TAB** draws the tablist and
the nametags. It draws them with packets, so a second plugin writing scoreboard
teams alongside it loses the race intermittently — which is what a name
flickering white with no prefix is.

So MoreFeatures does not render anything. It resolves rank, priority, tag and
bedwars team once, publishes them to PlaceholderAPI, and TAB keeps drawing.
One source of truth, no two plugins fighting over the same teams.

| Placeholder | Value |
|---|---|
| `%morefeatures_sortweight%` | Sort TAB on this — numeric, for `PLACEHOLDER_HIGH_TO_LOW` |
| `%morefeatures_sortkey%` | The same ordering as a string, for `PLACEHOLDER_A_TO_Z` |
| `%morefeatures_prefix%` | Rank prefix, never empty |
| `%morefeatures_tabprefix%` | Playerlist prefix, falls back to the rank prefix |
| `%morefeatures_suffix%` | Rank suffix |
| `%morefeatures_namecolor%` | Team colour in a game, rank colour outside one |
| `%morefeatures_tag%` | The player's Phoenix tag |
| `%morefeatures_teamcolor%` | Team colour, empty outside a game |
| `%morefeatures_ingame%` | `true` / `false` |

TAB cannot switch sorting rules per world, so the rule lives inside the value
instead. `%morefeatures_sortweight%` is one number carrying both, highest first:

```
991000   yellow team, owner      team band 99, rank 1000
990000   yellow team, default    team band 99, rank 0
971000   red team, owner         team band 97, rank 1000
 10000   spectator               team band 1
  1000   lobby, owner            team band 0, rank 1000
     0   lobby, default
```

The team band counts *down* from 99, because `HIGH_TO_LOW` puts the biggest
number on top while teams should read in their normal order. A rank score can
never reach a full band, so no rank can promote a player out of their own team —
which is the entire point of grouping by team first. Outside a game the band is
0 and the weight collapses to rank alone, so the lobby sorts purely by rank.

**TAB config** — in `config.yml`:

```yaml
scoreboard-teams:
  enabled: true
  sorting-types:
    - "PLACEHOLDER_HIGH_TO_LOW:%morefeatures_sortweight%"
    - "PLACEHOLDER_A_TO_Z:%player%"
```

The second line only breaks ties between two players of the same rank in the
same team, so they do not swap places at random. `PLACEHOLDER_HIGH_TO_LOW`
requires a numeric placeholder — `%morefeatures_sortweight%` always returns a
plain integer, even while Phoenix is still loading, so it can never produce the
non-numeric warning TAB logs for placeholders that fail to resolve.

If you prefer string sorting, `PLACEHOLDER_A_TO_Z:%morefeatures_sortkey%` gives
the identical order with the name tiebreak already built in.

and in `groups.yml` under the default group:

```yaml
tabprefix: "%morefeatures_tabprefix%"
tagprefix: "%morefeatures_prefix%"
tabsuffix: "%morefeatures_suffix%"
tagsuffix: "%morefeatures_suffix%"
customtabname: "%morefeatures_namecolor%%player%"
```

Any per-rank `sorting-types: GROUPS:...` or per-group prefixes already in TAB
should be removed — they are what the Phoenix priority was competing with.

**`display.yml` itself:**

```yaml
sorting:
  higher-priority-first: true   # false if your ranks number the other way (1 = owner)

prefix:
  fallback: "&7"                # Shown when Phoenix has no prefix — never leave blank

chat:
  enabled: true                 # Only outside an arena; MBedwars keeps its own chat
  format: "<prefix>%pxcosmetics_player_color%%phoenix_player_name%<suffix>%phoenix_player_tag%&7: %pxcosmetics_player_chat_color%<message>"
  # Tokens: <prefix> <suffix> <tag> <name> <message>. Everything else is PlaceholderAPI.
  color-permission: "morefeatures.chat.color"
```

`<message>` is inserted last and is never read as a placeholder, a colour code
or a format specifier, so players cannot inject any of the three through chat.

---

### `antiautoplace.yml` — Anti-AutoPlace settings

```yaml
cancel: true    # Cancel the block placement when a violation is detected

alerts:
  enabled: true
  message: "&cAnticheat &8» &f%player% &chas been flagged for AutoPlace!"
  # Placeholders: %player%, %uuid%, %type%, %flags%

punishments:
  enabled: false
  command: "tempban %player% 30d AutoPlace/FastPlace"   # Run as console when threshold is reached
  # Placeholder: %player%

fastplace:
  window-ms: 200    # Players may not place 10 blocks within this many milliseconds (lower = stricter)

flags:
  alert-threshold: 3      # Flags before staff receive an alert
  punish-threshold: 10    # Flags before the punishment command runs
```

---

### `phoenix.yml` — MySQL bridge settings

```yaml
database:
  host: "localhost"
  port: 3306
  name: "phoenixbridge"
  username: "phoenixbridge"
  password: "phoenixbridge"
  pool-size: 10
  use-ssl: false         # Encrypt the connection to the database server

sync:
  interval-ticks: 6000   # How often to sync (20 ticks = 1 second, 6000 = 5 minutes)
```

---

## Requirements

- **Minecraft server:** 1.8.8 (Paper/Spigot)
- **MySQL database:** Required only if `phoenix-mysql.enabled: true` in `config.yml`
- **PlaceholderAPI:** Required for the display feature — without it the `%morefeatures_*%`
  placeholders and the chat format are skipped, and a warning is logged
- **MBedwars:** Optional. Without it tablist sorting falls back to rank priority only
- **TAB:** Renders what the display feature resolves. Nothing breaks without it,
  but nothing sorts either
