# BTC-CORE

![Java Version](https://img.shields.io/badge/Java-21-orange)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Target](https://img.shields.io/badge/Target-BTCCORE%20-blue)
![Base](https://img.shields.io/badge/Base-ASPaper-purple)

**BTC-CORE** is a high-performance, specialized Minecraft server fork engineered specifically for the **BTC Studio** infrastructure. It combines the best features from multiple upstream forks into a single, cohesive platform.

## 🧬 Fork Heritage

BTC-CORE is built upon **Advanced Slime Paper (ASPaper)** and integrates features from:

| Fork | Integration | Key Features |
|------|-------------|--------------|
| **Paper** | 🧩 Base | Async chunk loading, modern API, performance patches |
| **Folia** | ✅ Regionized Threading | Multi-threaded world regions, region schedulers |
| **Purpur** | ✅ Gameplay Features | Controllable minecarts, rideable mobs, sign editing |
| **Pufferfish** | ✅ Entity Optimization | DEAR/DAB, SIMD vectorization, async mob spawning |
| **SparklyPaper** | ✅ Performance | Hopper throttling, parallel unticking, world sleep |
| **Leaf** | ✅ Async Processing | Async entity tracker, async pathfinding |
| **Canvas** | ✅ Chunk System | Rewritten chunk executor, priority-based loading |
| **SlimeWorld (SRF)** | ✅ Native | Fast world format, database backends, instant instancing |

## 🎯 Design Philosophy

BTC-CORE follows a **"cherry-picking"** strategy:
- We actively select the best optimizations and features from each fork
- All features are adapted for **Folia's regionized threading** model

> [!WARNING]
> **DEVELOPER COMPATIBILITY NOTICE**
>
> BTC-CORE introduces **deep architectural changes** that affect plugin compatibility:
>
> **Threading & Scheduling**
> - **Regionized Multithreading** (Folia): Plugins must use region-aware schedulers (`RegionScheduler`, `GlobalRegionScheduler`)
> - **Async Entity Tracker**: Entity tracking runs on separate threads, breaking plugins that expect sync access
> - **Async Pathfinding**: Mob pathfinding is asynchronous, affecting plugins that hook into AI
>
> **World Management**
> - **SlimeWorld Format (SRF)**: Worlds are not stored in vanilla format - plugins using `WorldCreator` or file-based world loading will fail
> - **Database Backends**: Worlds can be stored in MySQL/Redis instead of filesystem
> - **AdvancedSlimePaperAPI**: Required for world creation, cloning, and loading
>
> **Combat & Entity Mechanics**
> - **DEAR (Dynamic Entity Activation)**: Distant entities tick less frequently, affecting damage/AI plugins
> - **Invulnerability Ticks**: Configurable (default: 10), may break damage calculation plugins
> - **Attack Cooldown**: Can be disabled entirely via config
> - **Combat Log System**: Native combat tagging may conflict with external combat plugins
>
> **Network & Chat**
> - **FreedomChat Integration**: Chat packets are rewritten to system messages, breaking chat plugins
> - **Packet Limiters**: Aggressive rate limiting may kick plugins that send many packets
> - **Native Transport**: Uses epoll/io_uring on Linux, may affect packet manipulation plugins
>
> **Performance Optimizations**
> - **Hopper Throttling**: Hoppers pause when destination is full, affecting automation plugins
> - **Projectile Chunk Loading Limits**: Projectiles have limited chunk loading, breaking some minigame plugins
> - **Suffocation Optimization**: Suffocation checks are throttled, may affect custom death plugins
>
> Many standard Spigot/Paper plugins **will not work out of the box**. If you are not a BTC Studio developer, ensure you have the technical capacity to audit and modify your plugin stack.

> [!NOTE]
> **Recommended Plugin Ecosystem**
> - Use **Folia-compatible** plugins or adapt existing ones
> - Leverage the **AdvancedSlimePaperAPI** for world management
> - Use our custom events: `PreDamageCalculationEvent`, `EntityTargetPlayerEvent`, `AsyncChatFormatEvent`
> - Check `btccore.yml` to disable conflicting optimizations if needed

## 🚀 Key Features in Detail

### ⚡ Concurrency & Threading (Folia Integration)
- **Parallel World Ticking**: Unlike standard implementations, BTC-CORE can tick separate worlds concurrently on a dedicated thread pool, drastically improving performance for instance-heavy servers.
- **Regionized Multithreading**: Leverages Folia's core to handle different world regions on separate threads, eliminating global main-thread bottlenecks.
- **Mid-Tick Task Execution**: Custom scheduler for executing chunk-related tasks during idle mid-tick periods.

### 🌍 Next-Gen World Management (SlimeWorld)
- **Native SRF Support**: Deeply integrated Slime Region Format (SRF) for ultra-fast world loading and saving.
- **Database Backends**: High-performance storage options for MySQL, Redis, and MongoDB.
- **Instant Instancing**: Optimized for creating, cloning, and disposing of temporary worlds (dungeons, minigames, islands) without filesystem overhead.
- **SlimeWorld Game Rules Config**: Custom YAML configuration file (`config/advancedslimepaper/slimeworld-config.yml`) for setting default game rules per world or pattern.
- **Custom Game Rule: `copperFade`**: Controls the frequency at which copper blocks oxidize (0 = no fade / 100 = vanilla).

#### SlimeWorld Config Example
```yaml
# config/advancedslimepaper/slimeworld-config.yml
default:
  # Applied to ALL SlimeWorlds
  copperFade: 100
  randomTickSpeed: 3

worlds:
  # Specific world overrides
  "my_lobby":
    doDaylightCycle: false
    doMobSpawning: false

  # Pattern matching: [pattern] matches any world containing "pattern"
  "[minigame]":
    keepInventory: true
    doFireTick: false
```

### 🛠️ Core Optimizations & Specialized Patches
- **Async Entity Tracker (Leaf)**: Offloads entity tracking to separate threads, significantly reducing main thread load.
- **Async Pathfinding (Leaf)**: Multi-threaded pathfinding for mobs, eliminating lag spikes from complex entity AI.
- **Async Mob Spawning (Pufferfish)**: Spawns mobs asynchronously to prevent tick loss during high-volume spawning events.
- **Dynamic Entity Activation Range (DEAR/DAB)**: Intelligent entity ticking that reduces processing for distant entities.
- **Async Player Data**: Offloads player saving operations to prevent main-thread spikes during save cycles.
- **SQLite Stats**: Lightweight, efficient statistics tracking using SQLite instead of flat files.
- **Blazingly Simple Farm Checks**: Simplified crop growth logic designed to minimize CPU overhead on massive automated farms.
- **Hopper Optimization (SparklyPaper)**: Throttles hoppers when destinations are full to save resources.

### 🎮 Gameplay Refinement (Purpur & Canvas)
- **Controllable Minecarts**: Full WASD control, customizable hop boosts, step heights (up to 1 block), and material-based speed multipliers.
- **Advanced Elytra Physics**: Configure damage per second, damage per boost, speed-based damage scaling, and an option to ignore the Unbreaking enchantment.
- **Sign Enhancement**: Right-click to edit sign text instantly and native support for legacy/MiniMessage formatting.
- **Inventory Expansion**: Native support for 6-row (54 slot) Barrels and Ender Chests.
- **Generic Rideable Mobs**: Configure any mob to be rideable and controllable by players.
- **Silk Touch Spawners**: Native implementation for mining spawners without external plugins.
- **Ender Pearl Fixes**: Restores accurate Vanilla behavior for Ender Pearls.

### 🧪 Experimental & Hardcore (Canvas)
- **Advanced Chunk System**: Leverages Moonrise's `ChunkTaskScheduler` and `PrioritisedExecutor` for ultra-low latency chunk loading.
- **SpreadPlayers Async**: Custom asynchronous implementation of the SpreadPlayers command for massive world borders.
- **Tick Command**: Full support for `/tick` command manipulation without breaking regionized threading.

### 🛡️ Security & Privacy
- **Native FreedomChat Integration**: We have integrated [FreedomChat](https://github.com/ocelotpotpie/FreedomChat/) directly into the core preventing chat reporting and enforcing secure profiles without needing an external plugin.
- **Sentry Integration**: Built-in error reporting and crash analysis.
- **SIMD Vectorization**: Hardware-accelerated map rendering (8x faster).
- **Combat Log Prevention**: Native combat tagging with configurable kill-on-logout.
- **CPS Limiting**: Automatic clicks-per-second detection and limiting.
- **Reach Validation**: Server-side attack distance validation.
- **Exploit Logging**: Automatic logging of suspicious player behavior.

### 🌍 SlimeWorld Enhancements
- **Clone Unloaded Worlds**: Copy SlimeWorlds directly from storage without loading them first.
- **Cross-World Entity Transfer**: Pets and mounts follow players between worlds.
- **SlimeWorld Asset Loader**: API for loading assets/configs directly from SWA sources (bypassing filesystem).
- **SlimeWorld Game Rules Config**: YAML-based game rule configuration per world/pattern.

### ⚡ Advanced Performance
- **Entity Collision Throttle**: Reduces collision checks for crowded areas.
- **Particle/Sound Culling**: Skips particles and sounds beyond configurable distance.
- **Scoreboard Optimization**: Only sends updates to players with visible objectives.
- **Light Update Throttle**: Limits light recalculations per tick.
- **Lazy Chunk Tickets**: Extended chunk retention for frequently visited areas.
- **Batched Inventory Updates**: Combines inventory packets for efficiency.
- **NBT Compression Cache**: Caches compressed NBT for frequently accessed items.
- **Chunk Prefetch**: Pre-loads destination chunks before teleport.
- **Redstone Throttle**: Limits redstone updates per chunk to prevent lag.
- **BetterHUD Culling**: Distance-based filtering for high-frequency text display packets.
- **Vanilla Tick Suppression**: Toggles to disable vanilla AI/Brains/Sensors for custom entity handling.

### ✨ Quality of Life
- **Maintenance Mode**: Built-in maintenance mode with bypass permissions.
- **Join Queue**: Queue system for players when server is full.
- **Player Data Backup**: Automatic player data backups at configurable intervals.
- **Teleport Warmup**: Configurable teleport warmup timer.
- **Vanish Levels**: Native vanish system with visibility levels.

### 📡 Custom Events API
- `PreDamageCalculationEvent`: Modify damage before calculations.
- `EntityTargetPlayerEvent`: Custom aggro rules for mobs.
- `PlayerEnterRegionEvent`: Region entry/exit detection.
- `AsyncChatFormatEvent`: Async chat formatting for performance.
- `WorldLoadPriorityEvent`: Control world loading order.
- `ChunkPopulateEvent`: Custom chunk population hooks.

---

## 📊 Feature Comparison Table

> Legend: ✅ = Implemented | ❌ = Not Available | 🧩 = Native/Inherited

### Multi-Threading & Concurrency
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| Regionized Multithreading | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Parallel World Ticking | ❌ | ❌ | ❌ | ❌ | ⚠️ | ❌ | ✅ | ✅ |
| Async Entity Tracker | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Async Pathfinding | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Async Mob Spawning | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ |
| Rewritten Chunk System | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | 🧩 |
| Priority-Based Chunk Loading | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | 🧩 |

### Performance Optimizations
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| SIMD Vectorization | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Dynamic Entity Activation (DEAR) | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ |
| Inactive Goal Selector Throttle | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ |
| Hopper Throttling | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| Projectile Chunk Load Limits | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| Entity Collision Throttle | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Particle/Sound Culling | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Light Update Throttle | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Lazy Chunk Tickets | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Redstone Throttle | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| NBT Compression Cache | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Monitoring & Debugging
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| Sentry Integration | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ |
| Per-World MSPT Command | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| Exploit Logging | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Gameplay Features
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| Controllable Minecarts | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Advanced Elytra Physics | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Sign Right-Click Edit | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| 6-Row Barrels/Ender Chests | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Generic Rideable Mobs | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Silk Touch Spawners | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### World Management
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| Native SlimeWorld (SRF) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Database Backends (MySQL/Redis) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Instant World Instancing | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Clone Unloaded Worlds | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Cross-World Entity Transfer | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Per-World Game Rules Config | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Security & Network
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| Native FreedomChat | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Chat Report Prevention | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Native Transport (io_uring/epoll) | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ✅ | ✅ | ✅ |
| Combat Log Prevention | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| CPS Limiting | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Reach Validation | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Quality of Life
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| Maintenance Mode | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Join Queue System | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Player Data Backup | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Teleport Warmup | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Vanish Levels | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Custom Events API
| Feature | Paper | Folia | Purpur | Pufferfish | SparklyPaper | Leaf | Canvas | **BTC-CORE** |
|---------|-------|-------|--------|------------|--------------|------|--------|--------------|
| PreDamageCalculationEvent | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| EntityTargetPlayerEvent | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| PlayerEnterRegionEvent | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| AsyncChatFormatEvent | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| WorldLoadPriorityEvent | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| ChunkPopulateEvent | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

---

## ⚙️ Configuration

BTC-CORE is primarily tuned via `btccore.yml`.

### ⚡ Performance & Threading
| Key | Default | Description |
|-----|---------|-------------|
| `parallel-world-ticking` | `false` | Enables concurrent ticking for separate world instances. |
| `parallel-world-threads` | `4` | Dedicates specific threads to parallel world processing. |
| `async-entity-tracker` | `true` | Offloads entity tracking to async threads (Leaf). |
| `async-pathfinding` | `true` | Enables multi-threaded mob pathfinding (Leaf). |
| `async-mob-spawning` | `true` | Spawns mobs asynchronously (Pufferfish). |
| `cascadingEntitySpawnLimit` | `10` | Hard limit on recursive entity spawns to prevent infinite loops (Phase 5). |
| `rpgCollisionCap` | `2` | Hard-cap on entities pushed per tick, mitigating O(N^2) lag in highly dense areas. |

### 🎯 Dynamic Entity Activation (DEAR/DAB)
| Key | Default | Description |
|-----|---------|-------------|
| `dab.enabled` | `true` | Enables distance-based entity tick throttling. |
| `dab.start-distance` | `12` | Distance (blocks) where throttling begins. |
| `dab.max-tick-freq` | `20` | Max ticks between updates for distant entities. |
| `dab.activation-dist-mod` | `8` | Distance modifier for frequency calculation. |

### 🎮 Gameplay (Purpur)
| Key | Default | Description |
|-----|---------|-------------|
| `minecart.controllable.enabled` | `false` | Enables manual WASD controls for minecarts. |
| `minecart.controllable.step-height` | `1.0` | Max block height minecarts can climb. |
| `minecart.controllable.hop-boost` | `0.5` | Jump boost when pressing space in minecart. |
| `elytra.damage-per-second` | `1` | Damage taken per second of flight. |
| `elytra.damage-per-boost` | `5` | Damage taken per firework boost. |
| `elytra.ignore-unbreaking` | `false` | Ignores Unbreaking enchantment for damage. |
| `rideable-mobs.enabled` | `false` | Allows riding any mob with saddle. |
| `silk-touch-spawners` | `false` | Enables mining spawners with Silk Touch. |
| `sign-edit-right-click` | `true` | Allows editing signs by right-clicking. |

### 📦 Inventory Expansion
| Key | Default | Description |
|-----|---------|-------------|
| `barrel-rows` | `3` | Number of rows in barrels (max 6). |
| `ender-chest-rows` | `3` | Number of rows in ender chests (max 6). |

### ⚡ Advanced Optimizations
| Key | Default | Description |
|-----|---------|-------------|
| `hopper-throttle.enabled` | `true` | Throttles hoppers when destination is full. |
| `hopper-throttle.ticks` | `40` | Ticks to wait before retry. |
| `projectile-chunk-load-limit` | `10` | Max chunks a projectile can load per tick. |
| `inactive-goal-selector-throttle` | `true` | Reduces AI processing for inactive mobs. |
| `collision-throttle.enabled` | `true` | Reduces collision checks in crowded areas. |
| `collision-throttle.max-entities` | `10` | Entity threshold before throttling. |
| `particle-culling.enabled` | `true` | Skips particles beyond distance. |
| `particle-culling.distance` | `64` | Max particle render distance. |
| `sound-culling.enabled` | `true` | Skips sounds beyond distance. |
| `sound-culling.distance` | `48` | Max sound audible distance. |
| `scoreboard-optimization` | `true` | Only updates visible scoreboards. |
| `light-throttle.enabled` | `true` | Limits light updates per tick. |
| `light-throttle.max-per-tick` | `500` | Max light updates per tick. |
| `lazy-chunk-tickets.enabled` | `true` | Extended chunk retention. |
| `lazy-chunk-tickets.retention-ticks` | `6000` | Ticks to keep chunks loaded. |
| `redstone-throttle.enabled` | `true` | Limits redstone updates per chunk. |
| `redstone-throttle.max-per-chunk` | `100` | Max redstone updates per chunk. |

### 🧙 Typewriter RPG Extensions & Optimizations
| Key | Default | Description |
|-----|---------|-------------|
| `rpg.world-events.enabled` | `true` | Enables vanilla world events (Raids, Dragon Fights). |
| `rpg.weather-ticks.enabled` | `true` | Enables global vanilla weather cycle. |
| `rpg.spawns.vanilla-spawns-enabled` | `true` | Controls natural spawns, spawners, phantoms, and traders. |
| `rpg.ai.optimized-goal-selectors` | `false` | Throttles vanilla Goal Selectors according to distance (MythicMobs/BTCMob bypass). |
| `rpg.redstone.static-graph-enabled` | `false` | Fully neutralizes Vanilla redstone BlockUpdates in favor of a Static Directed Weighted Graph. |
| `rpg.redstone.static-graph-worlds` | `["redstone_plots"]` | List of worlds where the static redstone algorithm operates. |

### ⚔️ Native Sentinel Anticheat Engine (Asynchronous)
BTC-CORE integrates a 100% native asynchronous anticheat capability directly in the NMS Packet Handling pipeline (`ServerGamePacketListenerImpl`), eliminating the need for `PacketEvents` and external AC hooks.
*Inspired by the network predictability math of **LightningGrim AC**, this native engine stores a `PlayerSimulationCache` (Ghost state latency compensator) to detect Reach, Raytrace LOS, and Velocity natively with 0.0 MSPT overhead on the Main Thread.*

**Phase 3 Features:**
- **Latency Compensation**: Uses historical ghost states from `PlayerSimulationCache` to validate hits based on the attacker's actual view.
- **Ray-AABB Intersection**: Mathematical Line-of-Sight validation against historical hitboxes.
- **Synchronous Attack Filter**: Implements a "Violation Buffer" that synchronously blocks illegal attack packets before they reach the game world.

These settings are governed by `btccore.yml` under the `rpg-optimization.sentinel` section.

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Enables the Sentinel native detection engine and async verification system. |
| `max-reach-distance` | `3.01` | Mathematical maximum distance permitted for a valid combat interaction. |
| `reach-violation-buffer-limit` | `5` | Hits allowed over the limit before synchronous attack blocking triggers. |
| `reach-raytrace-enabled` | `true` | Enables Line-of-Sight validation against historical bounding boxes. |
| `reach-raytrace-leniency` | `0.05` | Margin of error for ray-box intersection math. |
| `max-speed-buffer` | `1.0` | Threshold over vanilla max speed allowed to compensate for sudden lag bursts. |
| `mysql-logging.enabled` | `false` | Asynchronously logs all violation traces into an external SQL pool. |
| `auto-notify-admins` | `true` | Broadcast warnings to players with `sentinel.admin`. |

**In-Game Admin Tools:**
Staff can manage real-time alerts or check historical database traces directly from the chat:
- `/sentinel notify` - Toggles the real-time alerting system for the executor natively.
- `/sentinel check <player>` - Fetches the last 10 violation metadata records straight from MySQL async pool.

### 🎨 Visual Core APIs (Asynchronous)
Dedicated to massive Display Entity handling (*BetterModel*, *TextDisplayDialogue*) and Virtual GUIs (*AdvancedMenu*), `BTCCoreVisualAPI` bypasses native Bukkit Thread locks for instantiating purely UI-focused networks.
- `BTCCoreVisualAPI.sendAsyncVirtualInventory` : Generates an un-tracked container update entirely off the Main Thread.
- `BTCCoreVisualAPI.spawnAsyncDisplayEntity` : Casts a Display Entity exclusively network-side with 0 MSPT logic overhead.

### 🛡️ Security (FreedomChat)
| Key | Default | Description |
|-----|---------|-------------|
| `freedom-chat.enabled` | `true` | Enables Freedom Chat integrations. |
| `freedom-chat.rewrite-chat` | `true` | Rewrites chat packets to system messages. |
| `freedom-chat.force-secure-chat-enforced` | `false` | Forces secure chat advertisement. |
| `freedom-chat.prevent-chat-reports` | `true` | Disables chat reporting in server status. |
| `freedom-chat.bedrock-only-rewrite` | `false` | Only rewrites chat for Bedrock players. |

### � Combat & Anti-Cheat
| Key | Default | Description |
|-----|---------|-------------|
| `combat-log.enabled` | `true` | Enables combat tagging system. |
| `combat-log.tag-duration` | `10` | Seconds player stays in combat. |
| `combat-log.kill-on-logout` | `true` | Kill players who log out in combat. |
| `cps-limit.enabled` | `true` | Enables clicks-per-second limiting. |
| `cps-limit.max` | `20` | Maximum allowed CPS. |
| `reach-validation` | `true` | Validates attack reach server-side. |
| `exploit-logging` | `true` | Logs suspicious player behavior. |

### �📊 Monitoring & Debugging
| Key | Default | Description |
|-----|---------|-------------|
| `sentry-dsn` | `""` | Sentry DSN for error tracking. |
| `flare.enabled` | `true` | Enables `/flare` profiler command. |
| `flare.url` | `https://flare.airplane.gg` | Flare profiler endpoint. |

### ✨ Quality of Life
| Key | Default | Description |
|-----|---------|-------------|
| `maintenance-mode.enabled` | `false` | Enables maintenance mode. |
| `maintenance-mode.message` | `<red>Server is under maintenance.` | Kick message for maintenance. |
| `join-queue.enabled` | `false` | Enables join queue when full. |
| `join-queue.max-size` | `50` | Maximum queue size. |
| `teleport-warmup-ticks` | `60` | Teleport warmup in ticks (3 seconds). |
| `vanish-levels` | `true` | Enables visibility level vanish. |
| `player-data-backup.enabled` | `true` | Enables auto player data backups. |
| `player-data-backup.interval-ticks` | `6000` | Backup interval (5 minutes). |

### 🌐 Network
| Key | Default | Description |
|-----|---------|-------------|
| `packet-limiter.all-packets.max-rate` | `500.0` | Global packet rate limit. |
| `nettyBurstLimiter` | `200` | Limits burst packet floods at the native Netty pipeline level (Phase 5). |
| `native-transport` | `auto` | Transport type: `auto`, `epoll`, `io_uring`. |


---

## 🛠 Building & Deployment

Requires **Java 21** and a strong understanding of Gradle.

```bash

# 🚨 IMPORTANT: Development Rules 
# If you manually modify /aspaper-server/src, YOU MUST rebuild and serialize patches before applying or you will lose your work!
# To commit NMS changes in the git tree, run standard git commands inside /aspaper-server/ or run the appropriate Paperweight command.

# Compile standard build completely natively (Recommended for immediate tests)
./gradlew build

# Generate reobfuscated Paperclip JAR (recommended for production)
./gradlew createMojmapPaperclipJar
```

---

## 🧩 Credits & Ecosystem
BTC-CORE is a "Frankenstein" fork, stitching together the most advanced components from the entire Minecraft server ecosystem. We owe our existence to the innovation of these projects:

### ⚙️ Core Modules (Inspirations)
- **[MCHPRS / RedPillar](https://github.com/MCHPR/MCHPRS)**: Inspiration for the `Static Directed Weighted Graph` underlying our Custom Redstone system. Wait-less block updates.
- **[Pufferfish]**: Inspiration for the `Dynamic Activation of Brains (DAB)` throttling algorithm governing the Mob AI performance.
- **[LightningGrim](https://github.com/Axionize/LightningGrim)**: Core combat maths translated natively into JVM Asynchronous thread pools for the Native Anticheat.

### 🏗️ Foundation
- **[Advanced Slime Paper](https://github.com/InfernalSuite/AdvancedSlimePaper)**: The backbone of our world management, providing the Slime Region Format (SRF) and instantaneous world instancing.
- **[Folia](https://github.com/PaperMC/Folia)**: The revolutionary multi-threading architecture that allows BTC-CORE to scale beyond a single CPU core.
- **[Paper](https://github.com/PaperMC/Paper)**: The standard for high-performance Minecraft servers, upon which all our patches rely.

### 🧬 Genetic Contributors (Patch Sources)
We have manually ported and adapted specific features from these specialized forks:

- **[Leaf](https://github.com/Winds-Studio/Leaf)** (Performance & Async):
  - *Contribution*: Async Entity Tracker, Async Pathfinding, Network IO optimizations, and detailed performance metrics.
- **[Pufferfish](https://github.com/pufferfish-gg/Pufferfish)** (Optimization):
  - *Contribution*: Dynamic Entity Activation Range (DEAR), Async Mob Spawning, Flare Profiler, and entity AI throttling.
- **[Canvas](https://github.com/CraftCanvas/Canvas)** (Experimental/Technical):
  - *Contribution*: Advanced Chunk System integration (Moonrise), SpreadPlayers Async rewrite, and Tick Command compatibility patches.
- **[Purpur](https://github.com/PurpurMC/Purpur)** (Gameplay):
  - *Contribution*: The robust gameplay configuration system (Minecarts, Elytra, Signs) and extended API events.
- **[SparklyPaper](https://github.com/SparklyPower/SparklyPaper)** (Micro-Optimizations):
  - *Contribution*: Hopper optimizations (throttling), farm checks, and event-based movement validations.
- **[FreedomChat](https://github.com/ocelotpotpie/FreedomChat)** (Privacy):
  - *Contribution*: Integrated directly into the core to provide native chat reporting protection.

---

## 📜 License & disclaimer
- **Custom BTC-CORE Patches**: Proprietary to **BTC Studio**.
- **Upstream Source**: Original licenses (GPLv3 / MIT) apply to their respective components from Paper, Folia, ASP, etc.
- **Liability**: This software is provided "as is". BTC Studio is not responsible for data loss or corruption resulting from the use of experimental world formats (SRF).
