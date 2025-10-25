# queue
A simple queue mod for Minecraft Java Edition. Lightweight, works on essentially any server types, (Spigot/Bukkit/Fabric/Paper), Tiered Priority Queue, and simple at its core.

**Table of Contents**
- [Features](#features)
- [Commands](#commands)
- [Requirements](#requirements)
- [Configuration](#configuration)

## Features

### Core Queue System
- **Automatic Queue Management** - Players are automatically sent to a queue server when the main server is full
- **Point-Based Queue System** - Players accumulate points over time; the player with the most points gets the next available slot
- **Tiered Priority Queue System** - Support for multiple VIP/donor tiers with configurable point accumulation rates
- **Configurable Point Tiers** - Define custom permission nodes with different point accumulation rates (e.g., VIP tiers get points faster)
- **Fair Tiebreaker** - If two players have the same points, the one with the better tier (lower seconds-per-point) goes first
- **Queue Persistence** - Players reconnecting to the queue server are automatically re-added to their queue position
- **Real-Time Position Updates** - Action bar displays current queue position and points

### Admin Access System
- **Admin Bypass** (`queue.admin`) - Always bypass the queue, can join even when server is over max capacity
- **Force Pull Players** - Admins can force pull any player from the queue directly to the main server, bypassing max capacity

### Server Management
- **Automatic Pause on Server Down** - Queue processing pauses when main server goes offline
- **Server Status Monitoring** - Checks main server status every 5 seconds
- **Manual Queue Control** - Admins can pause/resume queue processing at any time
- **Player Notifications** - All queue players are notified when server status changes or queue is paused/resumed

### Additional Features
- **Hot Reload** - Use `/queue reload` to reload all configurations without restart
- **Tab Completion** - All commands have tab completion support
- **Thread-Safe** - Uses concurrent collections to prevent crashes from simultaneous access
- **Graceful Disconnects** - Players are properly removed from queue when they disconnect

## Commands

### Player Commands
- `/queue info` - View your current queue position, points, and point accumulation rate
- `/queue status` - View overall queue system status (players in queue, paused state, server online status, player count)
- `/queue credits` - View plugin credits with clickable GitHub link

### Admin Commands (Require `queue.admin` permission)
- `/queue pull <player>` - Force pull a specific player from queue to main server (bypasses max capacity)
- `/queue pause` - Pause the queue from processing new players
- `/queue resume` - Resume queue processing
- `/queue list` - List all players in queue with their position, points, and priority tier
- `/queue reload` - Reload all configuration files without restarting

## Requirements

### What You Need:
- **A Velocity Proxy Server**
  - Permission System (_Optional but required for tiered queue and admin account bypasses_ **LuckPerms Velocity Recommended**).
  - The Queue plugin installed on the velocity server.
- **Your Main Server:**
  - This can be any type of Minecraft Server, **as long as it connects to the velocity proxy it will work**.
  - Fabric Proxy Lite is recommended for **fabric servers.**
- **The Queue Server**
  - This can be any type of Minecraft Server, **as long as it connects to the velocity proxy it will work**.
  - If you don't want to deploy yet another heavy load Minecraft Server it is recommended to use [PicoLimbo](https://github.com/Quozul/PicoLimbo) or [NanoLimbo](https://github.com/Nan1t/NanoLimbo).

## Configuration

The plugin uses two YAML configuration files, all located in the `plugins/queue/` directory. These files are automatically generated with default values on first run.

### server-config.yml

Defines the main server, queue server, and player capacity settings.
```yaml
# The name of the main server in your Velocity configuration
main-server: main

# The name of the queue/lobby server in your Velocity configuration
queue-server: queue

# Maximum players allowed on the main server (admins can bypass this)
main-server-max-players: 100
```

**Configuration Options:**
- **main-server**: The name of your main/survival server as defined in Velocity's `velocity.toml`
- **queue-server**: The name of your queue/lobby server as defined in Velocity's `velocity.toml`
- **main-server-max-players**: Maximum player capacity before queue activates (admins with `queue.admin` can bypass this limit)

### queue-points.yml

Configures the point-based queue system with priority tiers.
```yaml
tiers:
  queue.vip.diamond: 10  # 1 point every 10 seconds
  queue.vip.gold: 20     # 1 point every 20 seconds
  queue.vip.silver: 30   # 1 point every 30 seconds
  queue.vip: 45          # 1 point every 45 seconds

default-seconds: 60      # Default for players without permissions
```

**How It Works:**
- Players earn points while waiting in queue
- Lower seconds = faster points = higher priority
- Player with most points gets the next slot
- First matching permission in the list is used

**Example:** A VIP Diamond player earns 6 points per minute, while a default player earns 1 point per minute.

### Permission Nodes

**Player Permissions:**
- Custom tier permissions defined in `queue-points.yml` (e.g., `queue.vip.diamond`, `queue.vip.gold`, etc.)

**Admin Permissions:**
- `queue.admin` - Full admin access:
  - Bypass queue entirely
  - Join even when server is over max capacity
  - Access to all admin commands
  - If server is down, placed at front of queue with maximum priority

### Reloading Configuration

Use `/queue reload` in-game or console to reload all configuration files without restarting the proxy.

Changes will take effect immediately for:
- Server names and max players
- Point tier configuration

**Note**: Players already in the queue will keep their current point accumulation rate until they reconnect.
