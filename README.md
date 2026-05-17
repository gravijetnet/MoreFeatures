# MoreFeatures

A Minecraft 1.8.8 plugin providing music playback, fullbright, anti-autoplace detection, and a PhoenixAPI–MySQL bridge.

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
| **Music** | Play NoteBlock songs (`.nbs` files) for players, with volume control and auto-download |
| **Rickroll** | Force-play "Never Gonna Give You Up" on a player for a set duration |
| **Fullbright** | Set all chunks to maximum light level server-side |
| **Anti-AutoPlace** | Detect and punish players placing blocks suspiciously fast |
| **PhoenixAPI Bridge** | Sync playtime, ranks, and punishment stats to a MySQL database |

---

## Commands

### `/music`
**Aliases:** `/m`

Control NoteBlock song playback.

| Subcommand | Description | Permission |
|-----------|-------------|------------|
| `/music play [song]` | Play a random song, or a specific song by name | `morefeatures.music.play` |
| `/music stop` | Stop your currently playing song | `morefeatures.music.stop` |
| `/music force <player> [song]` | Force a song to play for another player | `morefeatures.music.force` |
| `/music list` | List all available songs | `morefeatures.music.list` |
| `/music info` | Show which song is currently playing for you | `morefeatures.music.info` |
| `/music volume [1-100]` | Get or set your default playback volume | `morefeatures.music.volume` |
| `/music download` | Manually trigger a re-download of configured songs | `morefeatures.music.download` |

---

### `/rickroll [player] [time]`

Play "Never Gonna Give You Up" on a player, optionally stopping after a set time.

**Time format:** Combine `h` (hours), `m` (minutes), `s` (seconds) — e.g. `30s`, `1m30s`, `1h`, `2m`

| Usage | Description |
|-------|-------------|
| `/rickroll` | Rickroll yourself indefinitely |
| `/rickroll 30s` | Rickroll yourself for 30 seconds |
| `/rickroll <player>` | Rickroll another player indefinitely |
| `/rickroll <player> 1m30s` | Rickroll another player for 1 minute 30 seconds |

> Console must specify a player: `/rickroll <player> [time]`

---

### `/fullbright [on|off|toggle|status]`

Control server-side fullbright. When enabled, all loaded chunks are forced to maximum light level for every player.

| Subcommand | Description |
|-----------|-------------|
| `on` | Enable fullbright |
| `off` | Disable fullbright |
| `toggle` | Toggle fullbright (default if no argument given) |
| `status` | Show whether fullbright is currently enabled |

---

### `/bridgesync`

Force an immediate sync of PhoenixAPI data (playtime, network stats) to the MySQL database. Runs asynchronously.

---

## Permissions

### Music

| Permission | Description | Default |
|-----------|-------------|---------|
| `morefeatures.music.play` | Play songs for yourself | everyone |
| `morefeatures.music.stop` | Stop your own song | everyone |
| `morefeatures.music.list` | List available songs | everyone |
| `morefeatures.music.info` | View what is currently playing | everyone |
| `morefeatures.music.volume` | Change your playback volume | everyone |
| `morefeatures.music.force` | Force-play a song for another player | op |
| `morefeatures.music.download` | Trigger a manual song download | op |

### Rickroll

| Permission | Description | Default |
|-----------|-------------|---------|
| `morefeatures.rickroll` | Rickroll yourself | everyone |
| `morefeatures.rickroll.others` | Rickroll other players | op |

### Fullbright

| Permission | Description | Default |
|-----------|-------------|---------|
| `morefeatures.fullbright` | Toggle fullbright via `/fullbright` | op |

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
  enabled: false    # Enable the PhoenixAPI → MySQL bridge

fullbright:
  enabled: true     # Enable the fullbright system on startup

autoplace:
  enabled: true     # Enable AutoPlace detection
```

---

### `music.yml` — Music settings

```yaml
rickroll_file: "NeverGonnaGiveYouUp.nbs"   # Song file used by /rickroll

volume: 80    # Default playback volume (1–100)

songs:
  - url: "https://example.com/song.nbs"    # Remote URL to download the file from
    filename: "Song.nbs"                   # Local filename to save it as
```

Songs listed under `songs` are downloaded automatically on plugin startup. Use `/music download` to re-download them manually.

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

sync:
  interval-ticks: 6000   # How often to sync (20 ticks = 1 second, 6000 = 5 minutes)
```

---

## Requirements

- **Minecraft server:** 1.8.8 (Paper/Spigot)
- **NoteBlockAPI:** Required for music features — add as a plugin on your server. If not present, music commands are disabled.
- **MySQL database:** Required only if `phoenix-mysql.enabled: true` in `config.yml`
