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
```

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
